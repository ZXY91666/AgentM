package com.agentm.localllm.core

import com.agentm.core.model.ChatMessage
import com.agentm.core.model.ChatRole
import com.agentm.core.model.ToolCall
import com.agentm.localllm.model.OllamaMessage
import com.agentm.localllm.model.OllamaOptions

/**
 * core ↔ localllm 消息互转。
 *
 * Ollama 的 /api/chat 协议没有 function calling，因此：
 * - assistant 消息携带 [ChatMessage.toolCalls] 时，Ollama 侧把 toolCalls 序列化进 content，
 *   便于 Ollama 模型学到"先调工具"的格式（与 open-webui 的做法一致）；
 * - role=TOOL 的消息（工具结果）直接收敛为 role=user（Ollama 协议只有 system/user/assistant）。
 */
object OllamaMessages {

    /** core [ChatMessage] → Ollama [OllamaMessage]。 */
    fun toOllama(messages: List<ChatMessage>): List<OllamaMessage> = messages.map { m ->
        val role = when (m.role) {
            ChatRole.SYSTEM -> "system"
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
            ChatRole.TOOL -> "user"
        }
        val content = m.content ?: ""
        val toolPart = m.toolCalls?.let { tcs ->
            tcs.joinToString("\n") { tc ->
                "tool_call ${tc.id} ${tc.function.name}(${tc.function.arguments})"
            }
        }
        val full = buildString {
            if (content.isNotEmpty()) append(content)
            if (toolPart != null) {
                if (isNotBlank()) append("\n")
                append(toolPart)
            }
            if (m.toolCallId != null) {
                if (isNotBlank()) append("\n")
                append("tool_result ${m.toolCallId}${m.name?.let { " $it" } ?: ""} $content")
            }
        }
        OllamaMessage(role = role, content = full)
    }

    /** Ollama 流式 chat 的选项转换（core 侧若传 temperature 等）。 */
    fun optionsOrNull(temperature: Double? = null, numCtx: Int? = null): OllamaOptions? {
        if (temperature == null && numCtx == null) return null
        return OllamaOptions(temperature = temperature, num_ctx = numCtx)
    }
}

/**
 * 供 core 的 gateway factory 读取的 host provider。
 *
 * core 若已定义 OllamaGateway 的 host 注入点，直接实现本接口并在 factory 里注入
 * （例如 `OllamaHostProvider { repo.host }`），即可让 gateway 始终用 app 设置页记住的 host。
 */
fun interface OllamaHostProvider {
    fun host(): String
}

/** 便捷实现：把 OllamaRepository 暴露给 core factory。 */
fun hostProviderFromRepository(repo: com.agentm.localllm.repo.OllamaRepository): OllamaHostProvider =
    OllamaHostProvider { repo.host }
