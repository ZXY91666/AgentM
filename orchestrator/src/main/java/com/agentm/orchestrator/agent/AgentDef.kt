package com.agentm.orchestrator.agent

import com.agentm.core.model.ToolSpec

/**
 * 静态的 agent 定义：描述一个可复用子 agent 的身份、能力与模型偏好。
 * 纯数据类，不持有 LLM 客户端，执行由 [LlmAgentNode] 包装完成。
 */
data class AgentDef(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val tools: List<ToolSpec> = emptyList(),
    val modelOverride: String? = null
) {
    /** 给 MainRouter 做意图匹配用的短描述。 */
    val routerSummary: String get() = "$name: $description"

    /** 供 LLM function-calling 使用：把该 agent 本身包装成一个可被调用的工具。 */
    fun asDelegationTool(): ToolSpec = ToolSpec(
        name = "delegate_${id}",
        description = "Delegate a self-contained subtask to the '${name}' agent. $description",
        parametersSchemaJson =
            """{"type":"object","properties":{"task":{"type":"string","description":"The concrete, self-contained task for this agent."}},"required":["task"]}"""
    )

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "systemPrompt" to systemPrompt,
        "tools" to tools.map { it.name },
        "modelOverride" to modelOverride
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): AgentDef = AgentDef(
            id = m["id"]?.toString() ?: throw IllegalArgumentException("AgentDef missing id"),
            name = m["name"]?.toString() ?: m["id"].toString(),
            description = m["description"]?.toString() ?: "",
            systemPrompt = m["systemPrompt"]?.toString() ?: m["name"]?.toString() ?: "",
            tools = (m["tools"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { ToolSpec(name = it) }
                ?: emptyList(),
            modelOverride = m["modelOverride"]?.toString()
        )
    }
}
