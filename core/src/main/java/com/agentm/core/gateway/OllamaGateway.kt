package com.agentm.core.gateway

import com.agentm.core.LlmException
import com.agentm.core.json.JsonUtil
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.core.model.ChatRole
import com.agentm.core.model.LlmConfig
import com.agentm.core.model.ModelInfo
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Ollama 本地通道网关：POST {baseUrl}/api/chat（NDJSON 流）。
 *
 * 协议适配（Ollama 无原生 function calling）：
 * - role=TOOL 收敛为 user，tool_calls 序列化成 `tool_call <id> <name>(<args>)` 文本
 *   回灌进 content（open-webui 同款做法）；
 * - 模型若按 `<tool_call>{"name":..,"arguments":..}</tool_call>` 或
 *   `tool_call <name>(<argsJson>)` 文本格式回工具调用，由 [FunctionCallParser] 在外层解析，
 *   本 gateway 只透传文本。
 */
class OllamaGateway(
    override val config: LlmConfig,
) : LlmGateway {

    override val channelName: String get() = "ollama(${config.baseUrl})"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(if (config.timeoutMs > 0) config.timeoutMs else 30_000, TimeUnit.MILLISECONDS)
        .readTimeout(if (config.timeoutMs > 0) config.timeoutMs else 60_000, TimeUnit.MILLISECONDS)
        .callTimeout(if (config.timeoutMs > 0) config.timeoutMs * 4 else 300_000, TimeUnit.MILLISECONDS)
        .build()

    override fun isUsable(): Boolean = config.baseUrl.isNotBlank() && config.model.isNotBlank()

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
            val done = withContext(Dispatchers.IO) { postNdjson(messages, tools, onEvent) }
            onEvent(done); onDone(done)
        } catch (e: LlmException) {
            val ev = AgentEvent.Error(e.message ?: "ollama error", e)
            onEvent(ev); onDone(ev)
        } catch (e: Exception) {
            val m = LlmException(LlmException.mapCause(e), e.message ?: "ollama call failed", e)
            val ev = AgentEvent.Error(m.message ?: "ollama call failed", m)
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
            scope = scope, messages = messages, tools = tools,
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

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${config.baseUrl.trimEnd('/')}/api/tags").get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw LlmException.server("tags HTTP ${resp.code}")
            val arr = JsonUtil.parseObject(resp.body?.string() ?: "")["models"]?.jsonArray
            arr?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ModelInfo(
                    id = id, provider = "ollama",
                    contextLength = 0,
                    capabilities = setOf("chat", "streaming"),
                )
            } ?: emptyList()
        }
    }

    // ---------- NDJSON POST ----------
    private fun postNdjson(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        onEvent: (AgentEvent) -> Unit,
    ): AgentEvent {
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            config.temperature?.let { t ->
                put("options", buildJsonObject { put("temperature", JsonPrimitive(t)) })
            }
            put("messages", buildJsonArray {
                toOllamaMessages(messages, tools).forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
        }
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/chat")
            .post(JsonUtil.DEFAULT.encodeToString(JsonElement.serializer(), body)
                .toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { resp ->
            when (resp.code) {
                200 -> parseNdjson(resp, onEvent)
                400 -> AgentEvent.Error("请求无效 HTTP 400: ${resp.body?.string()?.take(200)}", LlmException.badRequest("HTTP 400"))
                404 -> AgentEvent.Error("模型未找到 HTTP 404", LlmException.badRequest("model not found"))
                in 500..599 -> AgentEvent.Error("服务端错误 HTTP ${resp.code}", LlmException.server("HTTP ${resp.code}"))
                else -> AgentEvent.Error("意外状态 HTTP ${resp.code}", LlmException.server("HTTP ${resp.code}"))
            }
        }
    }

    private fun parseNdjson(resp: okhttp3.Response, onEvent: (AgentEvent) -> Unit): AgentEvent {
        val full = StringBuilder()
        val body = resp.body ?: return AgentEvent.Error("空响应体", LlmException.server("empty body"))
        var evalCount = 0
        var promptEvalCount = 0
        body.charStream().buffered().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val obj = runCatching { JsonUtil.parseObject(line) }.getOrNull() ?: continue
                val piece = obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                if (!piece.isNullOrEmpty()) {
                    full.append(piece)
                    onEvent(AgentEvent.Delta(piece))
                }
                evalCount = obj["eval_count"]?.jsonPrimitive?.longOrNull?.toInt() ?: evalCount
                promptEvalCount = obj["prompt_eval_count"]?.jsonPrimitive?.longOrNull?.toInt() ?: promptEvalCount
                if (obj["done"]?.jsonPrimitive?.contentOrNull == "true") break
            }
        }
        onEvent(AgentEvent.Delta("", isFinal = true))
        // 尝试从完整文本中解析工具调用（Ollama 模型按提示词格式回 tool_call）
        val text = full.toString()
        val parsedCalls = com.agentm.core.tool.FunctionCallParser.parse(text)
        val final = if (parsedCalls.isNotEmpty()) {
            ChatMessage(role = ChatRole.ASSISTANT, content = text.ifBlank { null }, toolCalls = parsedCalls)
        } else {
            ChatMessage(role = ChatRole.ASSISTANT, content = text)
        }
        val usage = if (evalCount > 0 || promptEvalCount > 0)
            AgentEvent.Usage(promptEvalCount, evalCount, promptEvalCount + evalCount) else null
        return AgentEvent.Done(final, usage)
    }

    // ---------- 消息适配 ----------
    companion object {
        /**
         * core 消息 → Ollama (role, content) 对。
         * 有 tools 时把工具说明拼进最后一条 system（Ollama 无 tools 字段，走提示词协议）。
         */
        fun toOllamaMessages(
            messages: List<ChatMessage>,
            tools: List<ToolSpec>? = null,
        ): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>(messages.size + 1)
            if (!tools.isNullOrEmpty()) {
                val toolDoc = tools.joinToString("\n") { s ->
                    "- ${s.name}: ${s.description} 参数JSON Schema: ${s.parametersSchemaJson}"
                }
                out += "system" to buildString {
                    append("你可以调用以下工具。调用时严格输出一行：tool_call <name>(<argsJson>)\n")
                    append(toolDoc)
                }
            }
            messages.forEach { m ->
                val role = when (m.role) {
                    ChatRole.SYSTEM -> "system"
                    ChatRole.USER -> "user"
                    ChatRole.ASSISTANT -> "assistant"
                    ChatRole.TOOL -> "user"
                }
                val content = buildString {
                    m.content?.let { append(it) }
                    m.toolCalls?.forEach { tc ->
                        if (isNotBlank()) append("\n")
                        append("tool_call ${tc.id} ${tc.function.name}(${tc.function.arguments})")
                    }
                    if (m.toolCallId != null) {
                        if (isNotBlank()) append("\n")
                        append("tool_result ${m.toolCallId}${m.name?.let { " $it" } ?: ""} ${m.content ?: ""}")
                    }
                }
                out += role to content
            }
            return out
        }
    }
}
