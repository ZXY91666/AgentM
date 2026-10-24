package com.agentm.orchestrator.agent

import com.agentm.core.gateway.LlmGateway
import com.agentm.core.model.ToolSpec

/**
 * 内置的领域子 agent。每个都是纯 [AgentDef]：
 * - 作为节点：[toNode] 用 LlmGateway 包装成 [LlmAgentNode] 注册进图；
 * - 作为 handler：主 agent 侧经 [AgentDef.asDelegationTool] 以 function-calling 形态委派。
 *
 * 工具表（[tools] 里给的都是 [ToolSpec] 声明；实际执行逻辑由宿主在
 * AgentContext.tools 里注入 `name → suspend (argsJson) → String`）。
 */
object SubAgents {

    private fun specs(names: List<String>): List<ToolSpec> =
        names.map { ToolSpec(name = it, description = it) }

    val uiDef = AgentDef(
        id = "ui",
        name = "UI Agent",
        description = "Inspects and operates the device UI: screenshots, taps, text entry, navigation.",
        systemPrompt = "You are a UI agent. Verify screen state with observation tools before acting; " +
            "report what you see after each action. Never guess UI state.",
        tools = specs(listOf("screenshot", "tap", "input_text", "swipe", "open_app"))
    )

    val codeDef = AgentDef(
        id = "code",
        name = "Code Agent",
        description = "Reads, edits and runs code: file tools, shell commands, tests.",
        systemPrompt = "You are a coding agent. Read before you write, make minimal edits, " +
            "and verify with the project's own build/test commands.",
        tools = specs(listOf("read_file", "write_file", "run_shell", "grep"))
    )

    val searchDef = AgentDef(
        id = "search",
        name = "Search Agent",
        description = "Finds current information on the web and summarizes sources.",
        systemPrompt = "You are a research agent. Prefer fresh sources, cite URLs, " +
            "and clearly distinguish fact from speculation.",
        tools = specs(listOf("web_search", "web_fetch"))
    )

    val toolDef = AgentDef(
        id = "tool",
        name = "Tool Agent",
        description = "General-purpose tool executor: runs any registered tool for the user.",
        systemPrompt = "You are a tool orchestrator. Pick the right registered tool, " +
            "pass well-formed arguments, and report results honestly.",
        tools = specs(listOf("run_tool"))
    )

    fun defs(): List<AgentDef> = listOf(uiDef, codeDef, searchDef, toolDef)

    /** 按 id 取定义；找不到返回 null。 */
    fun def(id: String): AgentDef? = defs().firstOrNull { it.id == id }

    /** 把定义包装成可执行节点。 */
    fun toNode(def: AgentDef, gateway: LlmGateway): LlmAgentNode =
        LlmAgentNode(def, gateway)
}
