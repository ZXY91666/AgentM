package com.agentm.localllm.client

import com.agentm.localllm.model.OllamaChatRequest
import com.agentm.localllm.model.OllamaChatResponse
import com.agentm.localllm.model.OllamaChunk
import com.agentm.localllm.model.OllamaMessage
import com.agentm.localllm.model.OllamaModel
import com.agentm.localllm.model.OllamaModelDetails
import com.agentm.localllm.model.OllamaOptions
import com.agentm.localllm.model.OllamaShowResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ollama HTTP 客户端（同步 IO，调用方在 IO 协程上下文使用）。
 *
 * baseUrl 形如 `http://192.168.1.10:11434`（结尾斜杠会被去掉）。
 * 同步方法直接返回；[streamChat] 返回逐 chunk 的 [Flow]（NDJSON 解析）。
 */
class OllamaClient(
    baseUrl: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs * 4, TimeUnit.MILLISECONDS)
        .build()

    // ---- 模型 ----

    /** GET /api/tags */
    suspend fun listModels(): List<OllamaModel> {
        val body = httpGet("/api/tags")
        val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray ?: return emptyList()
        val detailsSer = serializer<JsonModelDetails>()
        return models.mapNotNull { el ->
            val obj = (el as? JsonObject)?.jsonObject ?: return@mapNotNull null
            val id = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val details = obj["details"]?.let { d ->
                runCatching { json.decodeFromJsonElement(detailsSer, d) }.getOrNull()
            }
            OllamaModel(
                id = id,
                displayName = id,
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                quantization = details?.quantizationLevel
                    ?: obj["quantization_level"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                parameters = details?.parameterCount
                    ?: obj["parameter_count"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                modifiedAt = obj["modified_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                details = details?.let {
                    OllamaModelDetails(
                        family = it.family,
                        parameterCount = it.parameterCount,
                        quantizationLevel = it.quantizationLevel,
                    )
                },
            )
        }
    }

    /** GET /api/show */
    suspend fun showModel(id: String): OllamaShowResponse =
        httpPost("/api/show", json.encodeToString(ShowRequest.serializer(), ShowRequest(id))) {
            json.decodeFromString(OllamaShowResponse.serializer(), it)
        }

    // ---- 拉取 / 创建 ----

    /** POST /api/pull，阻塞直到拉取完成。 */
    suspend fun pullModel(id: String): PullResult =
        httpPost("/api/pull", json.encodeToString(ShowRequest.serializer(), ShowRequest(id))) {
            parsePull(it)
        }

    /** POST /api/create。 */
    suspend fun createModel(id: String, from: String? = null, modelfile: String? = null): PullResult =
        httpPost(
            "/api/create",
            json.encodeToString(CreateRequest.serializer(), CreateRequest(id, from, modelfile)),
        ) { parsePull(it) }

    // ---- 对话 ----

    /** 非流式 chat（stream=false）。 */
    suspend fun chat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions? = null,
    ): OllamaChatResponse = httpPost(
        "/api/chat",
        json.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest(model, messages, stream = false, options),
        ),
    ) { json.decodeFromString(OllamaChatResponse.serializer(), it) }

    /** 流式 chat，逐 chunk 产出。 */
    fun streamChat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions? = null,
    ): Flow<OllamaChunk> = flow {
        val body = json.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest(model, messages, stream = true, options),
        ).toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("$baseUrl/api/chat").post(body).build()
        http.newCall(request).execute().use { resp ->
            if (resp.code !in CODES_OK) {
                throw OllamaException(resp.code, "stream chat 失败: ${resp.body?.string().orEmpty().take(200)}")
            }
            val bodyStream = resp.body ?: throw IOException("空响应体")
            val source = bodyStream.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                currentCoroutineContext().ensureActive()
                emit(json.decodeFromString(OllamaChunk.serializer(), line))
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---- 健康检查 ----

    /** GET /api/version。 */
    suspend fun healthCheck(): Boolean = runCatching { httpGet("/api/version") }.isSuccess

    // ---- 内部 ----

    private fun httpGet(path: String): String {
        val req = Request.Builder().url(baseUrl + path).get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code !in CODES_OK) throw OllamaException(resp.code, "GET $path 失败: ${text.take(200)}")
            return text
        }
    }

    private fun <T> httpPost(path: String, payload: String, parse: (String) -> T): T {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code !in CODES_OK) throw OllamaException(resp.code, "POST $path 失败: ${text.take(200)}")
            return parse(text)
        }
    }

    private fun parsePull(body: String): PullResult {
        val obj = json.parseToJsonElement(body).jsonObject
        return PullResult(
            model = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            completed = obj["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    // ---- 请求体 ----

    @kotlinx.serialization.Serializable
    private data class ShowRequest(val model: String)

    @kotlinx.serialization.Serializable
    private data class CreateRequest(
        val model: String,
        val from: String? = null,
        val modelfile: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class JsonModelDetails(
        val family: String = "",
        val parameterCount: String = "",
        val quantizationLevel: String = "",
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        val JSON_MEDIA = "application/json".toMediaType()
        val CODES_OK = setOf(200, 201, 204)
    }
}

/** 拉取/创建进度结果。 */
data class PullResult(
    val model: String,
    val status: String,
    val completed: Boolean,
)

/** Ollama 调用失败。 */
class OllamaException(val code: Int, message: String) : Exception(message)
