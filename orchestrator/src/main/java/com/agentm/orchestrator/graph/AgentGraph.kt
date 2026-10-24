package com.agentm.orchestrator.graph

import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.orchestrator.agent.AgentNode
import kotlinx.coroutines.CoroutineScope

/**
 * 工具执行器契约（core 里占位，宿主实现）。
 * 工具名 → 挂起执行：入参是 LLM 给的 JSON 字符串，返回文本结果。
 */
typealias ToolExecutor = suspend (argsJson: String) -> String

/**
 * 工具注册表：[AgentContext] 携带，LlmAgentNode 在 ToolCall 事件上按名查找执行。
 * core 侧暂无对应物（其 ToolSpec 文档注释里提到 [ToolRegistry] 但未落类），
 * 此处在 orchestrator 内定义最小契约，宿主（guiagent/app）注入实现。
 */
interface ToolRegistry {
    /** 注册一个工具。[spec] 供 LLM function-calling 声明用；[execute] 是实际执行逻辑。 */
    fun register(name: String, description: String, spec: com.agentm.core.model.ToolSpec?,
                  execute: ToolExecutor): ToolRegistry

    /** 按名查执行器；无则 null。 */
    fun executor(name: String): ToolExecutor?

    /** 全部工具声明（喂给 LlmGateway.chat 的 tools 参数）。 */
    fun specs(): List<com.agentm.core.model.ToolSpec>

    /** 全部工具名。 */
    fun names(): List<String>

    companion object {
        /** 内存实现。 */
        class InMemory(private val execs: MutableMap<String, ToolExecutor> =
                LinkedHashMap(), private val specs: MutableList<com.agentm.core.model.ToolSpec> =
                mutableListOf()) : ToolRegistry {
            override fun register(name: String, description: String, spec: com.agentm.core.model.ToolSpec?,
                                   execute: ToolExecutor): ToolRegistry {
                execs[name] = execute
                specs += spec ?: com.agentm.core.model.ToolSpec(name = name, description = description)
                return this
            }

            override fun executor(name: String): ToolExecutor? = execs[name]
            override fun specs(): List<com.agentm.core.model.ToolSpec> = specs.toList()
            override fun names(): List<String> = execs.keys.toList()
        }
    }
}

/**
 * 共享执行上下文：贯穿整张图的一次执行。
 * - [messages]：跨节点共享的消息历史
 * - [tools]：工具表（name → suspend (argsJson) → String）
 * - [config]：自由配置（温度、节点级参数，节点可读）
 * - [scope]：整次执行的协程作用域（LlmGateway.chat 用它跑）
 * - [onEvent]：事件 sink，节点把 [AgentEvent] 推到这里，最终汇入 Orchestrator 的 Flow
 */
data class AgentContext(
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val tools: Map<String, ToolExecutor> = emptyMap(),
    val config: MutableMap<String, Any?> = mutableMapOf(),
    val scope: CoroutineScope,
    val onEvent: (AgentEvent) -> Unit
) {
    /** 便捷：向 sink 推事件。 */
    fun emit(ev: AgentEvent) = onEvent(ev)

    /** 从 [ToolRegistry] 构建 tools 表。 */
    companion object {
        fun from(
            scope: CoroutineScope,
            registry: ToolRegistry?,
            onEvent: (AgentEvent) -> Unit = {},
            config: MutableMap<String, Any?> = mutableMapOf()
        ): AgentContext = AgentContext(
            scope = scope,
            tools = registry?.specs()?.associate { s -> s.name to (registry.executor(s.name) ?: { "no impl" }) }
                ?: emptyMap(),
            config = config,
            onEvent = onEvent
        )
    }
}

/** 条件边：[from] → [to]；[condition] 为 null 表无条件。 */
data class Edge(
    val from: String,
    val to: String,
    val condition: String? = null,
    val predicate: ((ctx: AgentContext, lastOutput: String) -> Boolean)? = null
)

/**
 * 有向图（节点 + 边）。参考 LangGraph：环合法，由 [GraphExecutor] 在运行时驱动
 * （回到已访问节点即一轮循环），拓扑序只用于初始化校验。
 */
class AgentGraph(val id: String) {
    private val _nodes = LinkedHashMap<String, AgentNode>()
    private val _edges = mutableListOf<Edge>()
    private var entry: String? = null

    val nodes: Map<String, AgentNode> get() = _nodes
    val edges: List<Edge> get() = _edges
    fun entry(): String? = entry
    fun node(name: String): AgentNode? = _nodes[name]

    /** 注册节点。第一个节点自动成为 entry（可被 [setEntry] 覆盖）。 */
    fun addNode(name: String, node: AgentNode): AgentGraph {
        check(name !in _nodes) { "node already registered: $name" }
        if (entry == null) entry = name
        _nodes[name] = node
        return this
    }

    /**
     * 加边。[condition] 是条件名（持久化进 GraphSpec，供宿主查条件注册表注入 [predicate]）；
     * 直接传 [predicate] 则立即可用。无 [condition] 无 [predicate] = 无条件边。
     */
    fun addEdge(from: String, to: String, condition: String? = null,
                predicate: ((AgentContext, String) -> Boolean)? = null): AgentGraph {
        require(from in _nodes && to in _nodes) { "edge $from->$to references unknown node" }
        _edges += Edge(from, to, condition, predicate)
        return this
    }

    fun setEntry(name: String): AgentGraph {
        require(name in _nodes) { "unknown entry $name" }
        entry = name
        return this
    }

    fun outEdges(node: String): List<Edge> = _edges.filter { it.from == node }
    fun inEdges(node: String): List<Edge> = _edges.filter { it.to == node }

    /**
     * 某节点在给定上下文下的激活出边。
     * 无条件边恒激活；条件边看 predicate（无 predicate 的条件边默认激活，
     * 宿主注入条件注册表后生效——"未注入 = 放行"是安全的宽默认）。
     */
    fun activeEdges(from: String, ctx: AgentContext, lastOutput: String): List<Edge> =
        outEdges(from).filter { edge ->
            edge.predicate?.invoke(ctx, lastOutput) ?: true
        }

    /** Kahn 拓扑序（环上节点排在尾部，不影响执行器驱动）。 */
    fun topologicalOrder(): List<String> {
        val degree = nodes.keys.associateWith { n -> inEdges(n).count() }.toMutableMap()
        val ready = degree.filterValues { it == 0 }.keys.toMutableList()
        val out = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val n = ready.removeAt(0)
            out += n
            for (e in outEdges(n)) {
                val d = (degree[e.to] ?: 0) - 1
                degree[e.to] = d
                if (d == 0) ready += e.to
            }
        }
        out += nodes.keys - out.toSet() // 环上剩余节点补在尾部
        return out
    }

    /** 校验，返回问题列表（空 = 合法）。 */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (_nodes.isEmpty()) problems += "graph is empty"
        if (_nodes.isNotEmpty() && entry == null) problems += "no entry node"
        for (e in _edges) {
            if (e.from !in _nodes) problems += "edge ${e.from}->${e.to}: unknown from"
            if (e.to !in _nodes) problems += "edge ${e.from}->${e.to}: unknown to"
        }
        return problems
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "entry" to entry,
        "nodes" to _nodes.keys.toList(),
        "edges" to _edges.map { m -> mapOf(
            "from" to m.from, "to" to m.to, "condition" to m.condition
        ) }
    )

    companion object {
        /** 从持久化 Map 还原骨架（边/entry）。节点与 predicate 由调用方 addNode/addEdge 注入。 */
        fun fromMap(m: Map<String, Any?>): AgentGraph {
            val graph = AgentGraph(m["id"]?.toString() ?: "graph")
            @Suppress("UNCHECKED_CAST")
            (m["edges"] as? List<Map<String, Any?>> ?: emptyList()).forEach { e ->
                graph._edges += Edge(
                    from = e["from"]?.toString() ?: throw IllegalArgumentException("edge missing from"),
                    to = e["to"]?.toString() ?: throw IllegalArgumentException("edge missing to"),
                    condition = e["condition"]?.toString()
                )
            }
            (m["entry"] as? String)?.let { graph.entry = it }
            return graph
        }
    }
}
