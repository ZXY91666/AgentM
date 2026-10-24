package com.agentm.core.model

import kotlinx.serialization.Serializable

/**
 * 一条 LLM 通道的连接配置——双通道（本地 / 云端）里的"一个通道"。
 *
 * 本地通道示例：`LlmConfig(provider="ollama", baseUrl="http://10.0.2.2:11434")`
 * 云端通道示例：`LlmConfig(provider="openai", baseUrl="https://api.openai.com/v1", apiKey="sk-...", model="gpt-4o-mini")`
 *
 * @property provider 协议族标识：`ollama` = Ollama /api/chat（NDJSON）；
 * `openai` = OpenAI 兼容 /chat/completions（SSE）。其它 OpenAI 兼容
 * 网关（OneAPI、LiteLLM、OpenRouter、DeepSeek、Moonshot…）都归到 `openai` 家族，
 * 靠 [baseUrl] 区分实际端点。
 * @property baseUrl 完整前缀（含 path），如 `http://host:11434` 或 `https://api.x.com/v1`。
 * @property apiKey 云端密钥；本地可为空。
 * @property model 模型名。
 * @property temperature / [maxTokens] / [topP] 采样参数，null = 使用服务端默认。
 * @property timeoutMs 单请求超时，0 = 使用 gateway 默认。
 */
@Serializable
data class LlmConfig(
    val provider: String = "openai",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val timeoutMs: Long = 0,
) {
    /** 是否本地通道（Ollama 家族，免 key）。 */
    val isLocal: Boolean get() = provider.equals("ollama", ignoreCase = true)

    /** 该通道当前是否配置完整到可发起请求。 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && (isLocal || apiKey.isNotBlank())
}
