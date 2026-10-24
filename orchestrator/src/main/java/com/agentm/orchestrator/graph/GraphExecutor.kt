package com.agentm.orchestrator.graph

import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 图执行器：驱动 [AgentGraph] 的一次完整运行（参考 LangGraph 的超步执行模型）。
 *
 * - **循环**：同一节点可被多次路由到（环合法），[defaultMaxRounds] 是全局轮数上限
 *   （一次 dispatch 里总共跑多少个节点实例），超过即终止并标记失败
 * - **条件边**：[Edge.predicate] 决定激活；无条件边恒激活
 * - **失败回退**（三级，按序尝试）：
 *   1. 当前节点存在 condition == "fallback" 的出边 → 跳到那条边的 to
 *   2. 否则回 entry 节点重试一次（仅一次）
 *   3. 再失败 → 结果 [ExecResult.failed]，Flow 侧补发 [AgentEvent.Error]
 * - **可观测**：节点事件（Delta/ToolCall/ToolResult…）在执行时**实时**汇入
 *   [executeAsFlow] 的 Flow，chat 侧直接 collect
 *
 * 纯 Kotlin 协程，不依赖 Compose / 任何 UI。
 */
class GraphExecutor(private val defaultMaxRounds: Int = 12) {

    /** 一次执行的最终结论。 */
    data class ExecResult(
        val graphId: String,
        val output: String,
        val visited: List<String>,
        val failed: Boolean = false,
        val error: String? = null,
        val stoppedByRoundLimit: Boolean = false
    )

    /**
     * 把执行过程暴露为 [Flow<AgentEvent>]——chat ViewModel 直接 collect。
     * 首事件 [AgentEvent.Started]；节点内部事件实时透出；
     * 成功后 [AgentEvent.Done]，失败后 [AgentEvent.Error]。
     */
    fun executeAsFlow(
        graph: AgentGraph,
        input: String,
        scope: CoroutineScope,
        tools: Map<String, suspend (String) -> String> = emptyMap(),
        maxRounds: Int? = null
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started(runId = "graph:${graph.id}"))
        val result = execute(graph, input, scope, tools, maxRounds, liveSink = { ev -> emit(ev) })
        if (result.failed) {
            emit(AgentEvent.Error(result.error ?: "graph ${graph.id} failed"))
        } else {
            emit(AgentEvent.Done(ChatMessage.assistant(result.output)))
        }
    }

    /**
     * 裸执行（不进 Flow），直接拿结果。
     * @param liveSink 实时事件 sink；执行中每个节点事件都会推给它（null 等价于丢弃）。
     */
    suspend fun execute(
        graph: AgentGraph,
        input: String,
        scope: CoroutineScope,
        tools: Map<String, suspend (String) -> String> = emptyMap(),
        maxRounds: Int? = null,
        liveSink: ((AgentEvent) -> Unit)? = null
    ): ExecResult {
        val problems = graph.validate()
        if (problems.isNotEmpty()) {
            return ExecResult(graph.id, "", emptyList(),
                failed = true, error = "invalid graph: ${problems.joinToString("; ")}")
        }
        val entry = graph.entry()!!
        val cap = maxRounds ?: defaultMaxRounds

        var lastOutput = input
        var current = entry
        val visited = mutableListOf<String>()
        var retriedEntry = false

        while (true) {
            if (visited.size >= cap) {
                return ExecResult(graph.id, lastOutput, visited,
                    failed = true, error = "round limit $cap reached", stoppedByRoundLimit = true)
            }
            visited += current
            val node = graph.node(current)!!
            val nodeInput = if (current == entry) input else lastOutput

            // 每个节点独立建 ctx（节点间通过 input/output 解耦，消息历史由节点自持）
            val ctx = AgentContext(
                tools = tools,
                config = mutableMapOf("node" to current),
                scope = scope,
                onEvent = { ev -> liveSink?.invoke(ev) }
            )
            try {
                val out = node.run(ctx, nodeInput, { ev -> liveSink?.invoke(ev) })
                lastOutput = out.output
                if (out.stop) return ExecResult(graph.id, lastOutput, visited)

                val next = nextNode(graph, ctx, current, lastOutput)
                if (next == null) return ExecResult(graph.id, lastOutput, visited)
                current = next
            } catch (t: Throwable) {
                // 失败回退 1：fallback 边
                val fb = graph.outEdges(current).firstOrNull { it.condition == "fallback" }?.to
                if (fb != null) {
                    current = fb
                    retriedEntry = (fb == entry)
                    continue
                }
                // 失败回退 2：回 entry 重试一次
                if (!retriedEntry && current != entry) {
                    current = entry
                    retriedEntry = true
                    continue
                }
                return ExecResult(graph.id, lastOutput, visited,
                    failed = true, error = "node $current: ${t.message}")
            }
        }
    }

    /** 选下一节点：取第一条激活出边的 to；无激活出边 = 终止。 */
    private fun nextNode(graph: AgentGraph, ctx: AgentContext, from: String, lastOutput: String): String? =
        graph.activeEdges(from, ctx, lastOutput).firstOrNull()?.to
}
