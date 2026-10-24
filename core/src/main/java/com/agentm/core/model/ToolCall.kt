package com.agentm.core.model

import kotlinx.serialization.Serializable

/**
 * 模型发起的一次工具调用（OpenAI function-calling 协议）。
 *
 * [function] 里的 [arguments] 是 JSON 字符串（OpenAI 原生如此，而非嵌套对象）：
 * 解析器拿到后再反序列化为具体参数。
 */
@Serializable
data class ToolCall(
    /** 由模型生成（OpenAI 保证 24h 内唯一），工具结果消息用同一个 id 回指。 */
    val id: String,
    /** OpenAI 目前恒为 "function"；保留字段便于未来扩展。 */
    val type: String = "function",
    val function: FunctionDef = FunctionDef(),
) {
    @Serializable
    data class FunctionDef(
        val name: String = "",
        /** JSON 字符串形式的参数，如 `{"query":"weather"}`；可能为空对象 `{}`。 */
        val arguments: String = "{}",
    )
}
