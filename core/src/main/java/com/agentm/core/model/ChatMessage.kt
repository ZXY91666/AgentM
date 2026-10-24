package com.agentm.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一条聊天消息——整个系统（chat / orchestrator / gateway / 工具循环）的基本数据单元。
 *
 * 设计要点（对齐 OpenAI Agents SDK 的 message 模型）：
 * - role 与 content 之外，assistant 消息可携带 [toolCalls]；
 * - role=TOOL 的消息必须带 [toolCallId]，可选 [name] 指回工具名（OpenAI 要求）；
 * - 所有可选字段用 [defaults] 保持向后兼容，序列化时自动省略 null。
 *
 * 注意：这里用 `@SerialName` 保持与 OpenAI 线格式（wire format）逐字段一致，
 * 因此 gateway 侧可以直接用 `Json.encodeToString` 生成请求体。
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(content: String) = ChatMessage(role = ChatRole.SYSTEM, content = content)
        fun user(content: String) = ChatMessage(role = ChatRole.USER, content = content)
        fun assistant(content: String) = ChatMessage(role = ChatRole.ASSISTANT, content = content)

        /** 模型回复了工具调用（content 可为空或附带说明文字）。 */
        fun assistant(toolCalls: List<ToolCall>, content: String? = null) =
            ChatMessage(role = ChatRole.ASSISTANT, content = content, toolCalls = toolCalls)

        /** 工具执行结果；[toolCallId] 必须与对应 [ToolCall.id] 一致，[name] 为工具名。 */
        fun tool(toolCallId: String, name: String, content: String) =
            ChatMessage(role = ChatRole.TOOL, content = content, toolCallId = toolCallId, name = name)
    }
}
