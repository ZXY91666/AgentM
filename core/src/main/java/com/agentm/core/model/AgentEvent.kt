package com.agentm.core.model

/**
 * 一次 agent 运行（可能包含多轮 LLM 调用 + 工具循环）对外发出的事件流。
 *
 * 设计思想（参考 OpenAI Agents SDK 的 streaming 事件模型）：
 * - 每次 [LlmGateway.chat] 调用（非流式）或每轮工具循环（流式）都由调用方通过
 *   `onEvent` 回调消费这些事件，UI / 日志 / orchestrator 都基于同一套类型；
 * - 事件是 **不可变数据**，可安全跨线程派发（纯内存流转，不做序列化——
 *   [Error.cause] 为 Throwable 无序列化器）；
 * - 以 [Started] 开始、以 [Done] 或 [Error] 结束（终态二选一）。
 *
 * 一次"完整对话"的事件序列示例：
 * ```
 * Started → Delta × N → ToolCall × M → ToolResult × M → Delta × N → Done(finalMessage)
 * 或任意时刻 Error(cause)
 * ```
 */
sealed class AgentEvent {

    /** 一次 agent run 开始。[runId] 由调用方生成，用于关联流式片段。 */
    data class Started(val runId: String) : AgentEvent()

    /**
     * 增量文本（流式）。
     * @property text 本片段增量内容（非全量）。
     * @property isFinal false 表示后面还有 delta；true 表示这是最后一个 delta。
     */
    data class Delta(val text: String, val isFinal: Boolean = false) : AgentEvent()

    /**
     * 模型发起一次工具调用。
     * @property toolCall 完整的调用信息（含 id，用于结果回指）。
     * @property argumentsJson 已解析成 JSON 对象的参数字符串（原样保留，便于日志）。
     */
    data class ToolCall(
        val toolCall: com.agentm.core.model.ToolCall,
        val argumentsJson: String,
    ) : AgentEvent()

    /**
     * 工具执行结果，指回 [ToolCall.toolCall.id]。
     * @property toolCallId 与发起事件一致。
     * @property content 工具输出的文本形式（序列化后的 JSON / 纯文本）。
     * @property isError 执行失败。
     */
    data class ToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean = false,
    ) : AgentEvent()

    /** 本轮 LLM 调用结束（非流式一次性事件，或流式的收尾）。携带最终 assistant 消息。 */
    data class Done(val finalMessage: ChatMessage, val usage: Usage? = null) : AgentEvent()

    /** 整个 run 出错（网络失败、模型返回错误、工具异常等）。 */
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent()

    /** 一次工具循环迭代（LLM 决定调用 → 执行 → 结果回灌）完成。供 orchestrator 观测中间状态。 */
    data class ToolRound(
        val round: Int,
        val toolCalls: List<com.agentm.core.model.ToolCall>,
    ) : AgentEvent()

    /** token 用量统计（可选携带于 [Done]）。 */
    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
    ) : AgentEvent()

    companion object {
        /** 是否终态事件：[Done] 或 [Error]。调用方消费流时可据此停止收集。 */
        fun isTerminal(event: AgentEvent): Boolean =
            event is Done || event is Error
    }
}
