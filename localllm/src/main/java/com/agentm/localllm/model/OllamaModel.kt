package com.agentm.localllm.model

import kotlinx.serialization.Serializable

/** Ollama 模型（对应 GET /api/tags 中的一项）。 */
@Serializable
data class OllamaModel(
    val id: String,
    val displayName: String = "",
    val size: Long = 0L,
    val quantization: String = "",
    val parameters: String = "",
    val modifiedAt: String = "",
    /** Ollama tags API 返回的 details（量化、family 等）。 */
    val details: OllamaModelDetails? = null,
) {
    fun displayLabel(): String = if (displayName.isNotEmpty()) displayName else id
}

@Serializable
data class OllamaModelDetails(
    val family: String = "",
    val parameterCount: String = "",
    val quantizationLevel: String = "",
)
