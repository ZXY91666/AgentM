package com.agentm.orchestrator.orchestrator

import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.orchestrator.agent.AgentDef
import com.agentm.orchestrator.agent.AgentNode
import com.agentm.orchestrator.graph.AgentGraph
import com.agentm.orchestrator.graph.GraphExecutor
import com.agentm.orchestrator.graph.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 多智能体编排门面：chat 侧唯一的入口。不依赖 Compose / 任何 UI。
 *
 * 职责：
 * - 注册 [AgentNode] / 图 [AgentGraph] 与工具 [ToolRegistry]
 * - 构建或加载图（[loadGraphSpec] 走 persistence 的 GraphSpec JSON）
 * - [dispatch]：按 [MainRouter] 挑一张图执行，把 [GraphExecutor] 的事件流
 *   作为 [Flow]<[AgentEvent]> 透给调用方——chat ViewModel 直接 collect
 */
class Orchestrator(
    val scope: CoroutineScope,
    private val defaultRegistry: ToolRegistry = ToolRegistry.create(),
    private val executor: GraphExecutor = GraphExecutor(),
    private val router: MainRouter = MainRouter()
) {
    private val graphs = LinkedHashMap<String, AgentGraph>()

    fun executor(): GraphExecutor = executor
    fun router(): MainRouter = router
    fun graph(id: String): AgentGraph? = graphs[id]
    fun allGraphs(): Map<String, AgentGraph> = graphs

    /** 注册一张图（同名覆盖）。节点/边需已建好；从 JSON 加载用 [loadGraphSpec]。 */
    fun registerGraph(graph: AgentGraph): Orchestrator {
        graphs[graph.id] = graph
        if (graphs.size == 1) {
            // 第一张图成为路由兜底（默认图）
            router.setDefaultGraphId(graph.id)
        }
        return this
    }

    /** 从 persistence 的 GraphSpec JSON 加载骨架并注册；节点由调用方 addNode 后重新 registerGraph。 */
    fun loadGraphSpec(json: String): AgentGraph {
        val spec = com.agentm.orchestrator.persistence.GraphSpec.fromJson(json)
        val g = AgentGraph.fromMap(spec.toMap())
        registerGraph(g)
        return g
    }

    /**
     * 派发一次请求，返回整张图的事件流。
     *
     * @param input 用户原始请求
     * @param tools 本次执行可用的工具（null = 用默认注册表）
     * @param maxRounds 覆盖默认轮数上限（null = GraphExecutor 默认值）
     *
     * chat 侧典型用法：
     * ```
     * orchestrator.dispatch(msg.text, toolRegistry)
     *     .collect { ev -> when (ev) { is AgentEvent.Delta -> ui.append(ev.text) ... } }
     * ```
     */
    fun dispatch(
        input: String,
        tools: ToolRegistry? = null,
        maxRounds: Int? = null
    ): Flow<AgentEvent> {
        val graph = routedGraph(input) ?: return emptyFlow()
        val reg = tools ?: defaultRegistry
        val toolMap = reg.specs().associate { s ->
            s.name to (reg.executor(s.name) ?: { "no impl for ${s.name}" })
        }
        return executor.executeAsFlow(graph, input, scope, toolMap, maxRounds)
    }

    /** 同步取最终文本（runBlocking，仅限测试 / CLI，勿在 UI 线程调用）。 */
    fun dispatchText(input: String, tools: ToolRegistry? = null, maxRounds: Int? = null): String {
        var result = ""
        runBlocking {
            dispatch(input, tools, maxRounds).collect { ev ->
                if (ev is AgentEvent.Done) result = ev.finalMessage.content ?: result
            }
        }
        return result
    }

    /** 路由命中哪张图（纯查询，不执行）。 */
    fun routedGraph(input: String): AgentGraph? =
        graphs[router.route(input, graphs.keys.toList())]

    companion object {
        /** 把 [AgentDef] 快速包成"单节点图"并注册（最小组合，适合快速接入）。 */
        fun singleNode(
            orchestrator: Orchestrator,
            graphId: String,
            def: AgentDef,
            node: AgentNode
        ): Orchestrator = orchestrator.registerGraph(
            AgentGraph(graphId).addNode(graphId, node)
        )
    }
}
