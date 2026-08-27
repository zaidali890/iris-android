package com.iris.android.agent

import com.iris.android.data.IrisSettings
import com.iris.android.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentLoop(
    private val llm: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val memoryDao: MemoryDao
) {
    private val history = mutableListOf<CanonicalMessage>()
    private val maxSteps = 8

    private fun systemPrompt(settings: IrisSettings): String = buildString {
        append("You are ${settings.personaName}, a voice/text assistant running directly on the user's Android phone ")
        append("with real ability to control it: opening apps, toggling flashlight/volume/DND, reading and replying ")
        append("to notifications, sending WhatsApp messages, placing calls, taking screenshots, and remembering context.\n")
        append("Your personality: ${settings.personaStyle}.\n")
        append(
            "Always prefer calling a tool over guessing when the request involves the phone itself. Be concise and " +
                "confident about what you did. If a tool reports a permission was denied or a feature isn't enabled, " +
                "explain that plainly instead of retrying blindly. Some actions (WiFi/Bluetooth toggles) can only be " +
                "opened for the user to tap, since Android restricts apps from flipping those directly — say so if asked."
        )
    }

    suspend fun runTurn(settings: IrisSettings, userText: String, emit: suspend (AgentEvent) -> Unit) {
        val relevant = withContext(Dispatchers.IO) {
            memoryDao.getAll().filter { entry ->
                val terms = userText.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
                terms.any { entry.text.lowercase().contains(it) }
            }.take(4)
        }
        val memoryNote = if (relevant.isNotEmpty()) {
            "\n\n[Relevant memory]\n" + relevant.joinToString("\n") { "- ${it.text}" }
        } else ""

        history.add(CanonicalMessage(CanonicalMessage.Role.USER, userText + memoryNote))

        try {
            for (step in 0 until maxSteps) {
                emit(AgentEvent.Thinking)
                val result = withContext(Dispatchers.IO) {
                    llm.chatComplete(settings, systemPrompt(settings), history, ToolDefs.ALL)
                }

                if (result.toolCalls.isEmpty()) {
                    history.add(CanonicalMessage(CanonicalMessage.Role.ASSISTANT, result.text))
                    trimHistory()
                    emit(AgentEvent.Final(result.text))
                    return
                }

                history.add(
                    CanonicalMessage(CanonicalMessage.Role.ASSISTANT, result.text, toolCalls = result.toolCalls)
                )

                for (call in result.toolCalls) {
                    emit(AgentEvent.ToolCall(call.id, call.name, call.input))
                    var resultText: String
                    var error = false
                    try {
                        resultText = withContext(Dispatchers.Main) { toolExecutor.execute(call.name, call.input) }
                    } catch (e: Exception) {
                        resultText = "Error: ${e.message}"
                        error = true
                    }
                    emit(AgentEvent.ToolResult(call.id, call.name, resultText, error))
                    history.add(
                        CanonicalMessage(CanonicalMessage.Role.TOOL, resultText, toolCallId = call.id)
                    )
                }
            }

            val fallback = "I made several tool calls but didn't reach a final answer — try a smaller request."
            history.add(CanonicalMessage(CanonicalMessage.Role.ASSISTANT, fallback))
            trimHistory()
            emit(AgentEvent.Final(fallback))
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: e.toString()))
        }
    }

    fun reset() {
        history.clear()
    }

    private fun trimHistory() {
        val max = 60
        if (history.size > max) {
            val overflow = history.size - max
            repeat(overflow) { history.removeAt(0) }
        }
    }
}
