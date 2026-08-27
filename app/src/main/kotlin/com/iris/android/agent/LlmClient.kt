package com.iris.android.agent

import com.iris.android.data.IrisSettings
import com.iris.android.data.LlmProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()

class LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun chatComplete(
        settings: IrisSettings,
        systemPrompt: String,
        history: List<CanonicalMessage>,
        tools: List<ToolDef>
    ): ChatResult = when (settings.llmProvider) {
        LlmProvider.GROK -> chatOpenAiCompatible(
            baseUrl = "https://api.x.ai/v1/chat/completions",
            apiKey = settings.grokApiKey,
            model = settings.grokModel,
            systemPrompt = systemPrompt,
            history = history,
            tools = tools,
            providerLabel = "Grok"
        )
        LlmProvider.OPENAI -> chatOpenAiCompatible(
            baseUrl = "https://api.openai.com/v1/chat/completions",
            apiKey = settings.openaiApiKey,
            model = settings.openaiModel,
            systemPrompt = systemPrompt,
            history = history,
            tools = tools,
            providerLabel = "OpenAI"
        )
        LlmProvider.OLLAMA -> chatOllama(settings, systemPrompt, history, tools)
        LlmProvider.ANTHROPIC -> chatAnthropic(settings, systemPrompt, history, tools)
    }

    // -------------------------------------------------------------------
    // Grok / OpenAI share the same request/response shape
    // -------------------------------------------------------------------
    private fun chatOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<CanonicalMessage>,
        tools: List<ToolDef>,
        providerLabel: String
    ): ChatResult {
        if (apiKey.isBlank()) throw IllegalStateException("No $providerLabel API key set in Settings.")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (m in history) {
            when (m.role) {
                CanonicalMessage.Role.TOOL -> messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.content)
                )
                CanonicalMessage.Role.ASSISTANT -> {
                    val obj = JSONObject().put("role", "assistant")
                    obj.put("content", if (m.content.isBlank()) JSONObject.NULL else m.content)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        for (tc in m.toolCalls) {
                            calls.put(
                                JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject().put("name", tc.name).put("arguments", JSONObject(tc.input))
                                    )
                            )
                        }
                        obj.put("tool_calls", calls)
                    }
                    messages.put(obj)
                }
                else -> messages.put(JSONObject().put("role", "user").put("content", m.content))
            }
        }

        val body = JSONObject().put("model", model).put("messages", messages)
        if (tools.isNotEmpty()) body.put("tools", toolDefsToOpenAiJson(tools))

        val request = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("$providerLabel request failed (${resp.code}): $text")
            val data = JSONObject(text)
            val msg = data.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val content = if (msg.isNull("content")) "" else msg.optString("content", "")
            val toolCalls = mutableListOf<ToolCallRequest>()
            if (msg.has("tool_calls") && !msg.isNull("tool_calls")) {
                val arr = msg.getJSONArray("tool_calls")
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    val args = safeJsonObject(fn.optString("arguments", "{}"))
                    toolCalls.add(ToolCallRequest(tc.getString("id"), fn.getString("name"), jsonObjectToMap(args)))
                }
            }
            return ChatResult(content, toolCalls)
        }
    }

    // -------------------------------------------------------------------
    // Anthropic
    // -------------------------------------------------------------------
    private fun chatAnthropic(
        settings: IrisSettings,
        systemPrompt: String,
        history: List<CanonicalMessage>,
        tools: List<ToolDef>
    ): ChatResult {
        if (settings.anthropicApiKey.isBlank()) throw IllegalStateException("No Anthropic API key set in Settings.")

        val messages = JSONArray()
        for (m in history) {
            when (m.role) {
                CanonicalMessage.Role.TOOL -> messages.put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", m.toolCallId)
                                .put("content", m.content)
                        )
                    )
                )
                CanonicalMessage.Role.ASSISTANT -> {
                    val content = JSONArray()
                    if (m.content.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", m.content))
                    for (tc in m.toolCalls) {
                        content.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", tc.id)
                                .put("name", tc.name)
                                .put("input", JSONObject(tc.input))
                        )
                    }
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                }
                else -> messages.put(JSONObject().put("role", "user").put("content", m.content))
            }
        }

        val body = JSONObject()
            .put("model", settings.anthropicModel)
            .put("max_tokens", 1024)
            .put("system", systemPrompt)
            .put("messages", messages)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("input_schema", toolDefToJsonSchema(t))
                )
            }
            body.put("tools", arr)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", settings.anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Anthropic request failed (${resp.code}): $text")
            val data = JSONObject(text)
            var outText = ""
            val toolCalls = mutableListOf<ToolCallRequest>()
            val content = data.getJSONArray("content")
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> outText += block.getString("text")
                    "tool_use" -> toolCalls.add(
                        ToolCallRequest(
                            block.getString("id"),
                            block.getString("name"),
                            jsonObjectToMap(block.optJSONObject("input") ?: JSONObject())
                        )
                    )
                }
            }
            return ChatResult(outText, toolCalls)
        }
    }

    // -------------------------------------------------------------------
    // Ollama (LAN, optional — phone reaches a computer running Ollama)
    // -------------------------------------------------------------------
    private fun chatOllama(
        settings: IrisSettings,
        systemPrompt: String,
        history: List<CanonicalMessage>,
        tools: List<ToolDef>
    ): ChatResult {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (m in history) {
            when (m.role) {
                CanonicalMessage.Role.TOOL -> messages.put(JSONObject().put("role", "tool").put("content", m.content))
                CanonicalMessage.Role.ASSISTANT -> {
                    val obj = JSONObject().put("role", "assistant").put("content", m.content)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        for (tc in m.toolCalls) {
                            calls.put(
                                JSONObject().put(
                                    "function",
                                    JSONObject().put("name", tc.name).put("arguments", JSONObject(tc.input))
                                )
                            )
                        }
                        obj.put("tool_calls", calls)
                    }
                    messages.put(obj)
                }
                else -> messages.put(JSONObject().put("role", "user").put("content", m.content))
            }
        }

        val body = JSONObject()
            .put("model", settings.ollamaModel)
            .put("messages", messages)
            .put("stream", false)
        if (tools.isNotEmpty()) body.put("tools", toolDefsToOpenAiJson(tools))

        val request = Request.Builder()
            .url("${settings.ollamaBaseUrl.trimEnd('/')}/api/chat")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Ollama request failed (${resp.code}). Is it reachable at ${settings.ollamaBaseUrl}?")
            }
            val data = JSONObject(text)
            val msg = data.getJSONObject("message")
            val content = msg.optString("content", "")
            val toolCalls = mutableListOf<ToolCallRequest>()
            if (msg.has("tool_calls") && !msg.isNull("tool_calls")) {
                val arr = msg.getJSONArray("tool_calls")
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    val args = fn.opt("arguments")
                    val argObj = if (args is JSONObject) args else safeJsonObject(args?.toString() ?: "{}")
                    toolCalls.add(ToolCallRequest("ollama-$i-${System.nanoTime()}", fn.getString("name"), jsonObjectToMap(argObj)))
                }
            }
            return ChatResult(content, toolCalls)
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------
    private fun toolDefsToOpenAiJson(tools: List<ToolDef>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", toolDefToJsonSchema(t))
                    )
            )
        }
        return arr
    }

    private fun toolDefToJsonSchema(t: ToolDef): JSONObject {
        val props = JSONObject()
        for ((key, param) in t.parameters) {
            val p = JSONObject().put("type", param.type).put("description", param.description)
            if (param.items != null) p.put("items", JSONObject().put("type", param.items.type))
            props.put(key, p)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(t.required))
    }

    private fun safeJsonObject(s: String): JSONObject = try {
        JSONObject(s)
    } catch (e: Exception) {
        JSONObject()
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.get(k)
        }
        return map
    }
}
