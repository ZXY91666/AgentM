package com.agentm.core.config

import android.content.Context
import android.content.SharedPreferences
import com.agentm.core.model.LlmConfig

/**
 * 双通道配置持久化（SharedPreferences，免 Room 依赖）。
 *
 * 键规划（前缀防冲突）：
 * - `local_*`：Ollama 通道（baseUrl/model；apiKey 恒空）
 * - `cloud_*`：OpenAI 兼容云端（baseUrl/apiKey/model/采样参数）
 * - `active`：当前优先通道（`local` / `cloud` / `auto`，默认 auto = factory 自动选）
 */
class GatewayConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agentm_gateway", Context.MODE_PRIVATE)

    fun loadLocal(): LlmConfig = LlmConfig(
        provider = "ollama",
        baseUrl = prefs.getString("local_baseUrl", "") ?: "",
        model = prefs.getString("local_model", "") ?: "",
        apiKey = "",
        temperature = prefs.getFloatOrNull("local_temperature"),
        maxTokens = prefs.getIntOrNull("local_maxTokens"),
    )

    fun loadCloud(): LlmConfig = LlmConfig(
        provider = "openai",
        baseUrl = prefs.getString("cloud_baseUrl", "") ?: "",
        apiKey = prefs.getString("cloud_apiKey", "") ?: "",
        model = prefs.getString("cloud_model", "") ?: "",
        temperature = prefs.getFloatOrNull("cloud_temperature"),
        maxTokens = prefs.getIntOrNull("cloud_maxTokens"),
        topP = prefs.getFloatOrNull("cloud_topP"),
    )

    fun saveLocal(cfg: LlmConfig) {
        prefs.edit()
            .putString("local_baseUrl", cfg.baseUrl)
            .putString("local_model", cfg.model)
            .putFloatOrNull("local_temperature", cfg.temperature)
            .putIntOrNull("local_maxTokens", cfg.maxTokens)
            .apply()
    }

    fun saveCloud(cfg: LlmConfig) {
        prefs.edit()
            .putString("cloud_baseUrl", cfg.baseUrl)
            .putString("cloud_apiKey", cfg.apiKey)
            .putString("cloud_model", cfg.model)
            .putFloatOrNull("cloud_temperature", cfg.temperature)
            .putIntOrNull("cloud_maxTokens", cfg.maxTokens)
            .putFloatOrNull("cloud_topP", cfg.topP)
            .apply()
    }

    var activeChannel: String
        get() = prefs.getString("active", "auto") ?: "auto"
        set(v) { prefs.edit().putString("active", v).apply() }

    private fun SharedPreferences.getFloatOrNull(k: String): Float? =
        if (contains(k)) getFloat(k, 0f) else null
    private fun SharedPreferences.getIntOrNull(k: String): Int? =
        if (contains(k)) getInt(k, 0) else null
    private fun SharedPreferences.Editor.putFloatOrNull(k: String, v: Float?) =
        if (v == null) remove(k) else putFloat(k, v)
    private fun SharedPreferences.Editor.putIntOrNull(k: String, v: Int?) =
        if (v == null) remove(k) else putInt(k, v)
}
