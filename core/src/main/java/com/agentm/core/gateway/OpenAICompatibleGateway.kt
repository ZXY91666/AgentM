package com.agentm.core.gateway

import com.agentm.core.LlmException
import com.agentm.core.json.JsonUtil
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.core.model.ChatRole
import com.agentm.core.model.LlmConfig
import com.agentm.core.model.ToolCall
import com.agentm.core.model.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容网关：POST {baseUrl}/chat/completions，同时支持流式（SSE）与非流式（JSON）。
 * 适用于 OpenAI / DeepSeek / Moonshot / OpenRouter / OneAPI / LiteLLM 等一切 OpenAI 协议端点。
 *
 * SSE 真流式：逐行读取 data: 帧，content 片段实时派生 [AgentEvent.Delta]，
 * tool_calls 按 index 聚合后在结束时一次性派生 [AgentEvent.ToolCall]。
 */
class OpenAICompatibleGateway(
    private val client: OkHttpClient,
    override val config: LlmConfig,
) : LlmGateway {

    override val channelName: String get() = "openai(${config.baseUrl})"

    override fun isUsable(): Boolean = config.isUsable

    override fun chat(
        scope: CoroutineScope,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        onEvent: (AgentEvent) -> Unit,
        onDone: (AgentEvent) -> Unit,
    ): Job = scope.launch {
        val runId = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.Started(runId))
        try {
            val done = withContext(Dispatchers.IO) {
                postSSE(buildRequest(messages, tools, stream = true), onEvent)
            }
            if (done is AgentEvent.Done) {
                val tcs = done.finalMessage.toolCalls
                if (!tcs.isNullOrEmpty()) {
                    onEvent(AgentEvent.ToolRound(1, tcs))
                    tcs.forEach { onEvent(AgentEvent.ToolCall(it, it.function.arguments)) }
                }
            }
            onEvent(done); onDone(done)
        } catch (e: LlmException) {
            val ev = AgentEvent.Error(e.message ?: "LLM error", e)
            onEvent(ev); onDone(ev)
        } catch (e: Exception) {
            val m = LlmException(LlmException.mapCause(e), e.message ?: "LLM call failed", e)
            val ev = AgentEvent.Error(m.message ?: "LLM call failed", m)
            onEvent(ev); onDone(ev)
        }
    }

    override fun stream(
        scope: CoroutineScope,
        messages: List<ChatMessage>,
        emitDelta: (text: String, isFinal: Boolean) -> Unit,
        onDone: (finalText: String) -> Unit,
        onError: (cause: Throwable) -> Unit,
        tools: List<ToolSpec>?,
    ): Job = scope.launch {
        val full = StringBuilder()
        chat(
            scope = scope,
            messages = messages,
            tools = tools,
            onEvent = { ev ->
                when (ev) {
                    is AgentEvent.Delta -> { full.append(ev.text); emitDelta(ev.text, ev.isFinal) }
                    is AgentEvent.Done -> onDone(ev.finalMessage.content ?: full.toString())
                    is AgentEvent.Error -> onError(ev.cause ?: LlmException.unknown(ev.message))
                    else -> Unit
                }
            },
            onDone = {},
        ).join()
    }

    // ---------- 请求体构造 ----------
    private fun buildRequest(messages: List<ChatMessage>, tools: List<ToolSpec>?, stream: Boolean): JsonObject =
        buildJsonObject {
            put("model", config.model)
            put("stream", stream)
            config.temperature?.let { put("temperature", JsonPrimitive(it)) }
            config.maxTokens?.let { put("max_tokens", it) }
            config.topP?.let { put("top_p", JsonPrimitive(it)) }
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(JsonUtil.DEFAULT.encodeToJsonElement(ChatMessage.serializer(), m))
                }
            })
            if (!tools.isNullOrEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { s ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", s.name)
                                if (s.description.isNotBlank()) put("description", s.description)
                                put("parameters", JsonUtil.parseObject(s.parametersSchemaJson))
                            })
                        })
                    }
                })
                put("tool_choice", "auto")
            }
        }

    // ---------- SSE POST ----------
    private fun postSSE(body: JsonObject, onEvent: (AgentEvent) -> Unit): AgentEvent {
        val reqBody = JsonUtil.DEFAULT.encodeToString(JsonElement.serializer(), body)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" })
            .apply {
                if (config.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${config.apiKey}")
            }
            .header("Accept", "text/event-stream")
            .post(reqBody)
            .build()
        return client.newCall(request).execute().use { resp ->
            when (resp.code) {
                200 -> parseSseStream(resp, onEvent)
                401, 403 -> AgentEvent.Error("认证失败 HTTP ${resp.code}: ${resp.body?.string()?.take(200)}", LlmException.auth("HTTP ${resp.code}"))
                400 -> AgentEvent.Error("请求无效 HTTP 400: ${resp.body?.string()?.take(200)}", LlmException.badRequest("HTTP 400"))
                429 -> AgentEvent.Error("模型过载/限流 HTTP 429", LlmException.server("HTTP 429"))
                in 500..599 -> AgentEvent.Error("服务端错误 HTTP ${resp.code}", LlmException.server("HTTP ${resp.code}"))
                else -> AgentEvent.Error("意外状态 HTTP ${resp.code}", LlmException.server("HTTP ${resp.code}"))
            }
        }
    }

    // ---------- SSE 流式解析 ----------
    private fun parseSseStream(resp: okhttp3.Response, onEvent: (AgentEvent) -> Unit): AgentEvent {
        val contentType = resp.header("Content-Type") ?: ""
        val full = StringBuilder()
        val toolCalls = sortedMapOf<Int, ToolCall>()
        var usage: AgentEvent.Usage? = null

        if (!contentType.contains("text/event-stream")) {
            return parseNonStream(resp.body?.string() ?: "")
        }
        val body = resp.body ?: return AgentEvent.Error("空响应体", LlmException.server("empty body"))
        body.charStream().buffered().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = runCatching { JsonUtil.parseObject(payload) }.getOrNull() ?: continue
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject
                val piece = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (!piece.isNullOrEmpty()) {
                    full.append(piece)
                    onEvent(AgentEvent.Delta(piece))
                }
                delta?.get("tool_calls")?.jsonArray?.forEach { el ->
                    val tc = el.jsonObject
                    val idx = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val prev = toolCalls[idx]
                    val fn = tc["function"]?.jsonObject
                    toolCalls[idx] = ToolCall(
                        id = tc["id"]?.jsonPrimitive?.contentOrNull ?: prev?.id ?: "call_$idx",
                        function = ToolCall.FunctionDef(
                            name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                                ?: prev?.function?.name ?: "",
                            arguments = (prev?.function?.arguments ?: "") +
                                (fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""),
                        ),
                    )
                }
                obj["usage"]?.jsonObject?.let { u ->
                    usage = AgentEvent.Usage(
                        promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalTokens = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
            }
        }
        onEvent(AgentEvent.Delta("", isFinal = true))
        val tcs = toolCalls.values.toList()
        val final = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = full.toString().ifBlank { null },
            toolCalls = tcs.ifEmpty { null },
        )
        return AgentEvent.Done(final, usage)
    }

    /** 非流式：整体是一个 JSON 对象 */
    private fun parseNonStream(text: String): AgentEvent {
        val obj = JsonUtil.parseObject(text)
        val choices = obj["choices"]?.jsonArray
        if (choices == null || choices.isEmpty()) {
            val err = obj["error"]?.jsonObject
            return AgentEvent.Error("无结果: ${err ?: text.take(200)}", LlmException.server("empty choices"))
        }
        val msg = choices[0].jsonObject["message"]?.jsonObject
        val content = msg?.get("content")?.jsonPrimitive?.contentOrNull
        val tcs = msg?.get("tool_calls")?.jsonArray?.map { e ->
            val tc = e.jsonObject
            val fn = tc["function"]?.jsonObject
            ToolCall(
                id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "call_0",
                type = tc["type"]?.jsonPrimitive?.contentOrNull ?: "function",
                function = ToolCall.FunctionDef(
                    name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}",
                ),
            )
        }
        val usage = obj["usage"]?.jsonObject?.let { u ->
            AgentEvent.Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                totalTokens = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
        val final = ChatMessage(role = ChatRole.ASSISTANT, content = content, toolCalls = tcs)
        return AgentEvent.Done(final, usage)
    }
}
