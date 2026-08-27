package com.iris.android.agent

sealed class AgentEvent {
    data object Thinking : AgentEvent()
    data class ToolCall(val id: String, val name: String, val input: Map<String, Any?>) : AgentEvent()
    data class ToolResult(val id: String, val name: String, val result: String, val error: Boolean = false) :
        AgentEvent()
    data class Final(val text: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

data class ToolCallRequest(val id: String, val name: String, val input: Map<String, Any?>)

data class CanonicalMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class ChatResult(val text: String, val toolCalls: List<ToolCallRequest>)

data class ToolParam(val type: String, val description: String, val items: ToolParam? = null)

data class ToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParam>,
    val required: List<String> = emptyList(),
    val dangerous: Boolean = false
)
