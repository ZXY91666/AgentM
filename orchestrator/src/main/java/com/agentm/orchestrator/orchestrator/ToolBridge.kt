package com.agentm.orchestrator.orchestrator

import com.agentm.core.model.ToolSpec
import com.agentm.orchestrator.agent.AgentDef
import com.agentm.orchestrator.graph.ToolExecutor
import com.agentm.orchestrator.graph.ToolRegistry

/**
 * 把 orchestrator 的工具 / agent 暴露给 LLM function-calling。
 *
 * 1. **工具注册**：向 [ToolRegistry] 批量挂工具（name + 描述 + 挂起执行器），
 *    供 LlmAgentNode 在 ToolCall 事件上执行。
 * 2. **agent-as-tool**：把 [AgentDef] 包成 `delegate_<id>` 工具挂进注册表——
 *    主 agent 可用 function-calling 把整段任务委派给子 agent（宿主传入执行闭包）。
 * 3. **spec 导出**：[toolSpecs] 生成喂给 LlmGateway.chat 的 tools 参数列表。
 *
 * 不依赖 Compose / UI。
 */
class ToolBridge(private val registry: ToolRegistry) {

    /** 注册一个工具。[execute] 入参是 LLM 给的参数 JSON 字符串。 */
    fun expose(name: String, description: String, execute: ToolExecutor): ToolBridge {
        registry.register(name, description, ToolSpec(name = name, description = description), execute)
        return this
    }

    /** 把 [AgentDef] 作为 delegate 工具挂进注册表。宿主在 execute 闭包里真正跑子 agent。 */
    fun exposeAgent(def: AgentDef, execute: suspend (argsJson: String) -> String): ToolBridge {
        registry.register(def.asDelegationTool().name, def.asDelegationTool().description, def.asDelegationTool(), execute)
        return this
    }

    /** 全部工具声明（喂给 LlmGateway.chat 的 tools 参数）。 */
    fun toolSpecs(): List<ToolSpec> = registry.specs()

    /** 全部工具名。 */
    fun names(): List<String> = registry.names()
}
