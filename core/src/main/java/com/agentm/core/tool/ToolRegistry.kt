package com.agentm.core.tool

import com.agentm.core.LlmException
import com.agentm.core.model.ToolCall
import com.agentm.core.model.ToolSpec

/**
 * 工具注册表（core 公共契约）。
 *
 * 与 OpenAI function-calling 对齐：[specs] 直接喂给 `LlmGateway.chat(tools=)`；
 * [execute] 由 agent 循环在收到 `AgentEvent.ToolCall` 后调用，结果回灌 messages。
 *
 * orchestrator 里目前有一份同名占位契约（`orchestrator.graph.ToolRegistry`），
 * 接口形状一致，后续集成统一换到本类即可。
 */
interface ToolRegistry {
    fun register(spec: ToolSpec, executor: ToolExecutor): ToolRegistry
    fun executor(name: String): ToolExecutor?
    fun spec(name: String): ToolSpec?
    fun specs(): List<ToolSpec>
    fun names(): List<String>

    companion object {
        fun inMemory(): ToolRegistry = InMemory()
    }
}

/** 挂起执行器：入参是 LLM 给的 JSON 字符串，返回文本结果。 */
typealias ToolExecutor = suspend (argsJson: String) -> String

internal class InMemory(
    private val execs: MutableMap<String, ToolExecutor> = LinkedHashMap(),
    private val specs: MutableMap<String, ToolSpec> = LinkedHashMap(),
) : ToolRegistry {
    override fun register(spec: ToolSpec, executor: ToolExecutor): ToolRegistry {
        execs[spec.name] = executor
        specs[spec.name] = spec
        return this
    }
    override fun executor(name: String): ToolExecutor? = execs[name]
    override fun spec(name: String): ToolSpec? = specs[name]
    override fun specs(): List<ToolSpec> = specs.values.toList()
    override fun names(): List<String> = execs.keys.toList()
}

/** 便捷执行：跑一次工具调用并把异常收敛为 [LlmException.Kind.TOOL_EXECUTION]。 */
suspend fun ToolRegistry.runTool(call: ToolCall): String {
    val exec = executor(call.function.name)
        ?: throw LlmException.tool("unknown tool: ${call.function.name}")
    return try {
        exec(call.function.arguments)
    } catch (e: LlmException) {
        throw e
    } catch (t: Throwable) {
        throw LlmException.tool("tool ${call.function.name} failed: ${t.message}", t)
    }
}
