package com.agentm.core.tool

import com.agentm.core.model.ToolCall
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从模型纯文本输出里解析工具调用（供不支持原生 function-calling 的通道使用，如 Ollama）。
 *
 * 支持两种线格式（与 [com.agentm.core.gateway.OllamaGateway] 注入的提示词协议配套）：
 * 1. `tool_call <name>(<argsJson>)` —— 简洁格式（OllamaMessages 序列化出去的同款）
 * 2. `<tool_call>{"name":"..","arguments":{..}}</tool_call>` —— XML 包裹格式（Qwen 系列惯用）
 */
object FunctionCallParser {

    private val SIMPLE_REGEX =
        Regex("""tool_call\s+([A-Za-z0-9_\-]+)\s*\((\{.*?\})\)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val XML_REGEX =
        Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", setOf(RegexOption.DOT_MATCHES_ALL))

    /** 解析文本中的全部工具调用；无则空列表。id 合成 `local_<序号>`。 */
    fun parse(text: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        XML_REGEX.findAll(text).forEachIndexed { i, m ->
            val body = runCatching {
                com.agentm.core.json.JsonUtil.parseObject(m.groupValues[1])
            }.getOrNull() ?: return@forEachIndexed
            val name = body["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            val args = body["arguments"]?.let { com.agentm.core.json.JsonUtil.elementToString(it) } ?: "{}"
            out += ToolCall(
                id = "local_$i",
                function = ToolCall.FunctionDef(name = name, arguments = args),
            )
        }
        if (out.isEmpty()) {
            SIMPLE_REGEX.findAll(text).forEachIndexed { i, m ->
                out += ToolCall(
                    id = "local_$i",
                    function = ToolCall.FunctionDef(
                        name = m.groupValues[1],
                        arguments = m.groupValues[2],
                    ),
                )
            }
        }
        return out
    }

    /** 从文本中移除工具调用片段，留下纯回答部分（UI 展示用）。 */
    fun strip(text: String): String =
        XML_REGEX.replace(text, "").let { SIMPLE_REGEX.replace(it, "") }.trim()
}
