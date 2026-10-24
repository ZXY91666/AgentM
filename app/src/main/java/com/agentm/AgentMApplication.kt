package com.agentm.app

import android.app.Application
import com.agentm.localllm.repo.OllamaRepository
import com.agentm.orchestrator.agent.AgentDef
import com.agentm.orchestrator.agent.AgentNode
import com.agentm.orchestrator.agent.AgentOutput
import com.agentm.orchestrator.agent.logicNode
import com.agentm.orchestrator.graph.AgentGraph
import com.agentm.orchestrator.orchestrator.Orchestrator
import com.agentm.core.model.AgentEvent

/**
 * Application 级依赖容器。
 *
 * 组装顺序（[onCreate]）：
 * 1. [OllamaRepository]：端侧 Ollama 模型管理（host 发现/持久化由内部 OllamaHostDiscovery 承担）
 * 2. [orchestrator]：多图注册 + 路由，chat 侧唯一入口
 *
 * 说明：
 * - core 的 LlmGateway / GatewayConfigStore 契约仍在 core-domain 并行建设中
 *   （core/gateway、core/config 目录）。UI 侧先按 "Orchestrator.dispatch(input):
 *   Flow<AgentEvent>" 这一稳定契约消费；gateway 注入点待 core 出接口后由 task-9 统一对齐。
 * - 默认图：router（入口）→ 内置子 agent（ui/code/search/tool）→ 回 router。
 *   节点为 [logicNode] 透传占位（无 LLM 时原样输出），保证"入口可启动、
 *   画布可编辑、保存 JSON 可往返"；真实 LLM 行为待 core 契约落地后替换为 LlmAgentNode。
 */
class AgentMApplication : Application() {

    lateinit var ollamaRepo: OllamaRepository
        private set
    lateinit var orchestrator: Orchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Ollama 端侧模型管理（host 发现 / 列表 / 拉取 / chat）
        ollamaRepo = OllamaRepository(this)

        // 2. 编排器：注册默认主图
        orchestrator = Orchestrator(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
            )
        )
        orchestrator.registerGraph(defaultMainGraph())
    }

    /**
     * 默认主图：router 入口 + 四个子 agent，双向边。
     * 节点是 [logicNode] 透传占位：无 LLM 时发出 Delta 事件并原样输出，
     * 画布运行与事件流可视化可用。
     */
    private fun defaultMainGraph(): AgentGraph {
        val main = AgentGraph("main")
        main.addNode("router", passThrough("router"))
        subAgentDefs().forEach { def ->
            main.addNode(def.id, passThrough(def.id))
            main.addEdge("router", def.id)
            main.addEdge(def.id, "router")
        }
        main.setEntry("router")
        return main
    }

    /** 透传占位节点：发 NodeEnter/Delta/NodeExit 风格事件，输出 = 输入。 */
    private fun passThrough(agentId: String): AgentNode =
        logicNode(agentId) { ctx, input ->
            ctx.emit(AgentEvent.Delta("[passthrough:$agentId] ", false))
            ctx.emit(AgentEvent.Delta(input, true))
            AgentOutput(agentId = agentId, output = input)
        }

    /** 内置子 agent 静态定义（与 SubAgents.allDefs 同构，避免依赖未就绪的 LlmGateway）。 */
    private fun subAgentDefs(): List<AgentDef> = listOf(
        AgentDef("ui", "UI Agent", "Operates the device UI (taps, input, screenshots).", "You are a UI agent."),
        AgentDef("code", "Code Agent", "Writes and runs code.", "You are a coding agent."),
        AgentDef("search", "Search Agent", "Searches the web for current information.", "You are a research agent."),
        AgentDef("tool", "Tool Agent", "Executes any registered tool.", "You are a tool orchestrator.")
    )

    companion object {
        @Volatile
        private var instance: AgentMApplication? = null

        val app: AgentMApplication
            get() = instance ?: throw IllegalStateException("AgentMApplication not initialized")
    }
}
