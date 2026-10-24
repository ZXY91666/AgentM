package com.agentm.core.gateway

import com.agentm.core.LlmException
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.core.model.LlmConfig
import com.agentm.core.model.ModelInfo
import com.agentm.core.model.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 统一 LLM 网关接口——chat / orchestrator / guiagent 的公共契约。
 *
 * 两条调用路径：
 * 1. [chat]：挂 `onEvent` 回调，由 gateway 内部把每一帧（Delta/ToolCall/ToolResult/Done/Error）
 *    派发给调用方。返回的 [Job] 在**非流式**请求时于调用返回前就已完成；
 *    流式请求下，[Job] 在流读完、终态事件派发后完成。
 * 2. [stream]：更轻量的 delta 回调形式，供只需要增量文本的场景（如纯聊天 UI）。
 *    它内部复用 [chat] 的流式管线，只是把 [AgentEvent.Delta] 转发给 [emitDelta]。
 *
 * 线程模型：所有方法都是**挂起/非挂起**分离——`chat` 本身立即返回，
 * 真正的工作在 [scope] 里异步执行，调用方通过 [onEvent] 观察结果。
 * 这避免了"接口必须挂起"与"必须返回 Job"的冲突。
 */
interface LlmGateway {

    /** 通道名（日志/展示用）：`ollama` / `openai` / 自定义。 */
    val channelName: String

    /** 通道是否就绪（配置完整）。未就绪时调用 chat 会抛 [LlmException.AUTH] 或 BAD_REQUEST。 */
    fun isUsable(): Boolean = true

    /**
     * 发起一次（可能含工具循环的）LLM 调用。
     *
     * @param messages 对话历史（调用方负责维护；工具结果消息也在这里追加）。
     * @param tools 工具声明；null/空 = 本次不让模型调用工具。
     * @param onEvent 事件回调，按发生顺序调用；终态事件（Done/Error）为最后一条。
     * @param onDone 可选的终态便捷回调（等价于在 onEvent 里过滤 Done/Error）。
     * @param scope 运行该调用的协程作用域；取消它即中止流。
     * @return 代表本次运行的 [Job]；`join()` 等待完成。
     */
    fun chat(
        scope: CoroutineScope,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        onEvent: (AgentEvent) -> Unit,
        onDone: (AgentEvent) -> Unit = {},
    ): Job

    /**
     * 流式调用的轻量形式：只要增量文本与终态。
     * 等价于调用 [chat] 并把 [AgentEvent.Delta] 转发给 [emitDelta]。
     */
    fun stream(
        scope: CoroutineScope,
        messages: List<ChatMessage>,
        emitDelta: (text: String, isFinal: Boolean) -> Unit,
        onDone: (finalText: String) -> Unit = {},
        onError: (cause: Throwable) -> Unit = {},
        tools: List<ToolSpec>? = null,
    ): Job

    // ---------- 供子类/实现共用的辅助 ----------

    /** 从任意 gateway 构建 [ModelInfo]（云端可查 /api/models，本地可查 /api/tags）。 */
    suspend fun listModels(): List<ModelInfo> = emptyList()

    /** 把 [LlmConfig] 里与本次调用相关的参数（temperature 等）合并到请求体上。 */
    fun resolveConfig(): LlmConfig = config

    /** 通道配置（由实现构造时注入）。 */
    val config: LlmConfig
}
