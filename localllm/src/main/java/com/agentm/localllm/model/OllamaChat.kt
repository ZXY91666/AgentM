package com.agentm.localllm.model

import kotlinx.serialization.Serializable

/** 对话消息（/api/chat 请求体格式，兼容 open-webui 用法）。 */
@Serializable
data class OllamaMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean,
    val options: OllamaOptions? = null,
)

@Serializable
data class OllamaOptions(
    val temperature: Double? = null,
    val top_p: Double? = null,
    val top_k: Int? = null,
    val num_ctx: Int? = null,
)

/** /api/chat 非流式响应。 */
@Serializable
data class OllamaChatResponse(
    val model: String = "",
    val created_at: String = "",
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    val total_duration: Long = 0L,
) {
    val content: String get() = message?.content ?: ""
}

/** /api/chat 流式响应中的单个 chunk。 */
@Serializable
data class OllamaChunk(
    val model: String = "",
    val created_at: String = "",
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    val done_reason: String = "",
    val total_duration: Long = 0L,
) {
    val token: String get() = if (done) "" else (message?.content ?: "")
}

/** /api/show 响应（模型信息，可能包含 modelfile）。 */
@Serializable
data class OllamaShowResponse(
    val model: String = "",
    val modelfile: String? = null,
    val parameters: String = "",
)
