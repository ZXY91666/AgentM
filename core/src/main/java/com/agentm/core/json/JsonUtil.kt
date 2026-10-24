package com.agentm.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * core 模块的 JSON 工具（无 UI 依赖，可被任意模块复用）。
 *
 * 统一一个 [Json] 实例（[DEFAULT]），避免各模块各自 new 配置不一致；
 * 所有 gateway 请求体/响应体、工具参数的序列化/反序列化都经过它。
 */
object JsonUtil {

    /** 全局共享的 [Json] 实例：忽略未知字段（模型/网关返回常有额外字段），允许空 body。 */
    val DEFAULT: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    // ---------- 基础 ----------

    fun parse(text: String): JsonElement = DEFAULT.parseToJsonElement(text)

    fun parseObject(text: String): JsonObject = parse(text).jsonObject

    fun elementToString(el: JsonElement?): String =
        el?.let { DEFAULT.encodeToString(JsonElement.serializer(), it) } ?: ""

    /** 取 JsonElement 的"文本值"：字符串/数字原样，对象/数组序列化为紧凑 JSON。 */
    fun textOf(el: JsonElement?): String = when {
        el == null -> ""
        el is JsonPrimitive -> el.content
        else -> elementToString(el)
    }

    // ---------- 构造 ----------

    /** 从 (key → schemaJsonString) 对生成 object JSON Schema 字符串，供 [com.agentm.core.model.ToolSpec] 使用。 */
    fun buildObjectSchema(properties: List<Pair<String, String>>): String {
        val props = buildJsonObject {
            properties.forEach { (name, schemaJson) ->
                put(name, DEFAULT.parseToJsonElement(schemaJson))
            }
        }
        return DEFAULT.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", props)
                put("additionalProperties", JsonPrimitive(false))
            },
        )
    }

    /** 把 JsonElement 转成紧凑 JSON 字符串（工具参数/结果回灌模型用）。 */
    fun toJsonString(value: JsonElement): String =
        DEFAULT.encodeToString(JsonElement.serializer(), value)

    /** 解析任意 JSON 文本并重新输出为紧凑格式。 */
    fun compact(text: String): String = toJsonString(parse(text))
}
