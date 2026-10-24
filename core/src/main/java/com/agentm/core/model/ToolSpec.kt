package com.agentm.core.model

import kotlinx.serialization.Serializable

/**
 * 工具能力声明——注册进 gateway 请求体让模型知道"我能调用什么"。
 *
 * 对应 OpenAI `tools[]` 里的 function 定义：
 * ```
 * { "type":"function", "function": { "name", "description", "parameters": <JSON Schema> } }
 * ```
 *
 * [parametersSchemaJson] 是 JSON Schema 的**原始字符串**而非嵌套对象：
 * - 调用方从任意来源（本地文件、远程下发、代码生成）拼好后直接塞入即可，零转换成本；
 * - gateway 序列化时原样嵌入，避免逐层嵌套 schema 类。
 * 需要程序化构造时可用 [JsonUtil].defaultParametersSchema() 生成 `{type:object, properties:{...}}`。
 */
@Serializable
data class ToolSpec(
    /** 工具名，必须与 [ToolRegistry] 注册的 name、以及 ToolCall.function.name 一致。 */
    val name: String,
    /** 给模型看的自然语言说明，决定模型何时调用它；写清楚输入输出与副作用。 */
    val description: String = "",
    /**
     * 参数 JSON Schema（字符串形式）。
     * 缺省为无参对象 `{ "type":"object", "properties": {} }`。
     */
    val parametersSchemaJson: String = DEFAULT_PARAMETERS,
) {
    companion object {
        const val DEFAULT_PARAMETERS = """{"type":"object","properties":{},"additionalProperties":false}"""

        /** 生成一个带 [properties] 的 object schema；property 的值本身是 schema 的 JSON 字符串片段。 */
        fun objectSchema(vararg props: Pair<String, String>): String =
            com.agentm.core.json.JsonUtil.buildObjectSchema(props.map { it.first to it.second })
    }
}
