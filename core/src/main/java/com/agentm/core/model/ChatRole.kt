package com.agentm.core.model

/**
 * 聊天消息角色。
 *
 * 与 OpenAI Chat Completions 协议对齐：
 * - [system]：系统提示词，约束 agent 行为
 * - [user]：用户输入
 * - [assistant]：模型回复（可携带 toolCalls）
 * - [tool]：工具执行结果（必须带 [ChatMessage.toolCallId] 指回发起调用的 assistant 消息）
 *
 * 本地 Ollama /api/chat 目前只认识 system/user/assistant，
 * 序列化时由 gateway 侧把 [tool] 收敛为 user（见 OllamaGateway）。
 */
enum class ChatRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}
