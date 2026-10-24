package com.agentm.core.model

import kotlinx.serialization.Serializable

/**
 * 模型/能力元数据，供上层做路由与展示（app 设置页、orchestrator 选模型）。
 *
 * @property id 模型标识，如 `llama3.1:8b`（Ollama）或 `gpt-4o-mini`（云端）。
 * @property displayName 人类可读名称。
 * @property provider 提供方：`ollama` / `openai` / `openrouter` / 自定义 baseUrl 域名等。
 * @property capabilities 能力标签集合，[Capability] 的字符串形式（wire 用 string 数组，避免枚举演进冲突）。
 * @property contextLength 上下文窗口（token 数），0 = 未知。
 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val provider: String = "unknown",
    val capabilities: Set<String> = emptySet(),
    val contextLength: Int = 0,
) {
    fun supports(capability: Capability): Boolean = capability.wire in capabilities
}

/** 模型能力标签。wire 值与 OpenAI 模型目录习惯保持一致。 */
enum class Capability(val wire: String) {
    CHAT("chat"),
    VISION("vision"),
    TOOL_CALLING("tool_calling"),
    STREAMING("streaming"),
    EMBEDDING("embedding"),
    REASONING("reasoning"),
}
