package com.agentm.orchestrator.persistence

import com.agentm.orchestrator.graph.AgentGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 图持久化 DSL（参考 LangGraph / AutoGen 的图定义 schema）。
 *
 * - 节点：`id` + `agent`（引用宿主 AgentDef 注册表里的定义）+ 可选 `config`
 * - 边：`from` / `to` / `condition`（条件名；null = 无条件。predicate 是 lambda 不可序列化，
 *   宿主在反序列化后按条件名注入 [com.agentm.orchestrator.graph.AgentGraph.addEdge] 的 predicate）
 * - 序列化：kotlinx-serialization，纯 JSON，可直接写文件 / 存 Room / 走 HTTP
 *
 * 不依赖 Compose。
 */
@Serializable
data class GraphSpec(
    val id: String,
    val entry: String,
    val nodes: List<NodeSpec> = emptyList(),
    val edges: List<EdgeSpec> = emptyList()
) {
    @Serializable
    data class NodeSpec(
        val id: String,
        /** 宿主 AgentDef 注册表里的定义 id；按此查表构造 LlmAgentNode。 */
        val agent: String,
        /** 节点级配置（温度、maxRounds 等），透传 AgentContext.config。 */
        val config: Map<String, String> = emptyMap()
    )

    @Serializable
    data class EdgeSpec(
        val from: String,
        val to: String,
        val condition: String? = null
    )

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "entry" to entry,
        "nodes" to nodes.map { n ->
            mapOf("id" to n.id, "agent" to n.agent, "config" to n.config)
        },
        "edges" to edges.map { e ->
            mapOf("from" to e.from, "to" to e.to, "condition" to e.condition)
        }
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun toJson(spec: GraphSpec): String = json.encodeToString(spec)
        fun fromJson(raw: String): GraphSpec = json.decodeFromString(raw)

        /** 校验 round-trip：spec → JSON → spec，应得到等价对象。 */
        fun roundTripCheck(spec: GraphSpec): Boolean =
            fromJson(toJson(spec)) == spec

        /** 从 AgentGraph 反向生成 spec（存档"已建好的图"；节点 agent 名即节点名）。 */
        fun fromGraph(g: AgentGraph): GraphSpec {
            @Suppress("UNCHECKED_CAST")
            val m = g.toMap() as Map<String, Any?>
            return GraphSpec(
                id = m["id"]?.toString() ?: "graph",
                entry = (m["entry"] as? String) ?: ((m["nodes"] as? List<*>)?.firstOrNull()?.toString() ?: ""),
                nodes = ((m["nodes"] as? List<*>) ?: emptyList()).map { n ->
                    NodeSpec(id = n.toString(), agent = n.toString())
                },
                edges = ((m["edges"] as? List<Map<String, Any?>>) ?: emptyList()).map { e ->
                    EdgeSpec(
                        from = e["from"]?.toString() ?: throw IllegalArgumentException("edge missing from"),
                        to = e["to"]?.toString() ?: throw IllegalArgumentException("edge missing to"),
                        condition = e["condition"]?.toString()
                    )
                }
            )
        }
    }
}
