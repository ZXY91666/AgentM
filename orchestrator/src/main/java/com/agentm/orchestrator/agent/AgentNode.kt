package com.agentm.orchestrator.agent

import com.agentm.core.gateway.LlmGateway
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.orchestrator.graph.AgentContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

/**
 * 图节点的执行结果。[stop] 为 true 时 GraphExecutor 立即终止整张图。
 */
data class AgentOutput(
    val agentId: String,
    val output: String,
    val stop: Boolean = false
)

/**
 * 图节点接口（参考 LangGraph 的节点-边思想）。
 * 每个节点接收共享 [AgentContext] 与本次输入，通过 [emit] 把 [AgentEvent] 推给调用方
 * （chat 侧收集成 Flow<AgentEvent>），并返回节点产出。
 *
 * @param ctx 共享执行上下文（消息历史 / 工具 / 配置 / 协程作用域）
 * @param input entry 节点拿到的是用户请求，其余节点拿到的是上游节点输出
 * @param emit 事件 sink
 */
fun interface AgentNode {
    suspend fun run(
        ctx: AgentContext,
        input: String,
        emit: (AgentEvent) -> Unit
    ): AgentOutput
}

/**
 * 把 [AgentDef] + core 的 [LlmGateway] 包装成可执行图节点。
 *
 * 依赖契约（core）：
 * - [LlmGateway.chat]：一次调用含完整工具循环（Delta/ToolCall/ToolResult/Done/Error 按序派发），
 *   返回 [kotlinx.coroutines.Job]，本节点挂起等待其完成；
 * - 工具执行：节点收到 [AgentEvent.ToolCall] 时从 [AgentContext.tools] 查
 *   `name → suspend (argsJson) → String`，把结果以 [ChatMessage.tool] 回灌 messages，
 *   供 gateway 下一轮继续；
 * - [AgentEvent.Error] 直接向上抛，由 GraphExecutor 做失败回退。
 */
class LlmAgentNode(
    private val def: AgentDef,
    private val gateway: LlmGateway
) : AgentNode {

    override suspend fun run(ctx: AgentContext, input: String, emit: (AgentEvent) -> Unit): AgentOutput {
        ctx.emit(AgentEvent.Started("node:${def.id}:${ctx.messages.size}"))
        ctx.messages += ChatMessage.user(input)
        if (def.systemPrompt.isNotBlank()) ctx.messages += ChatMessage.system(def.systemPrompt)

        var finalText = ""
        val pending = CompletableDeferred<Unit>()
        val job = gateway.chat(
            scope = ctx.scope,
            messages = ctx.messages.toList(),
            tools = def.tools.ifEmpty { null },
            onEvent = { ev ->
                when (ev) {
                    is AgentEvent.Delta -> finalText += ev.text
                    is AgentEvent.ToolCall -> {
                        val tool = ev.toolCall
                        val name = tool.function.name
                        // 工具执行排进 ctx.scope（挂起版）；onEvent 回调本身不是挂起上下文，
                        // 所以用 launch 跑。完成后把 assistant(tool_calls) 与 tool(result)
                        // 两条消息回灌 ctx.messages，并补发 ToolResult 事件。
                        ctx.scope.launch {
                            val runner = ctx.tools[name]
                            val result = if (runner == null) {
                                "unknown tool: $name"
                            } else {
                                try {
                                    runner(tool.function.arguments)
                                } catch (t: Throwable) {
                                    "tool $name failed: ${t.message}"
                                }
                            }
                            ctx.messages += ChatMessage.assistant(toolCalls = listOf(tool))
                            ctx.messages += ChatMessage.tool(toolCallId = tool.id, name = name, content = result)
                            emit(AgentEvent.ToolResult(toolCallId = tool.id, content = result))
                        }
                    }
                    is AgentEvent.Done -> finalText = ev.finalMessage.content ?: finalText
                    is AgentEvent.Error -> pending.completeExceptionally(
                        IllegalStateException("agent ${def.id} failed: ${ev.message}", ev.cause)
                    )
                    else -> Unit
                }
                emit(ev)
            },
            onDone = { pending.complete(Unit) }
        )
        try {
            pending.await()
        } finally {
            // 等工具回灌的协程跑完，避免下一轮图执行时消息还不全
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            job.cancel()
        }
        ctx.emit(AgentEvent.Done(ChatMessage.assistant(finalText)))
        return AgentOutput(agentId = def.id, output = finalText)
    }

    companion object {
        fun of(def: AgentDef, gateway: LlmGateway): LlmAgentNode = LlmAgentNode(def, gateway)
    }
}

/** 把任意挂起函数包装为图节点（纯逻辑节点，不调 LLM）。 */
fun logicNode(agentId: String, f: suspend (AgentContext, String) -> AgentOutput): AgentNode =
    AgentNode { ctx, input, _ ->
        val out = f(ctx, input)
        AgentOutput(agentId = agentId, output = out.output, stop = out.stop)
    }
