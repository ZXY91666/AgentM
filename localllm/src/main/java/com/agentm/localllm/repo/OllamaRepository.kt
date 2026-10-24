package com.agentm.localllm.repo

import android.content.Context
import com.agentm.localllm.client.OllamaClient
import com.agentm.localllm.client.PullResult
import com.agentm.localllm.discovery.OllamaHostDiscovery
import com.agentm.localllm.model.OllamaChatResponse
import com.agentm.localllm.model.OllamaChunk
import com.agentm.localllm.model.OllamaMessage
import com.agentm.localllm.model.OllamaModel
import com.agentm.localllm.model.OllamaOptions
import com.agentm.localllm.model.OllamaShowResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ollama 端侧模型管理入口（app 设置页直接使用本类）。
 *
 * 组合 [OllamaClient]（按 host 惰性创建）与 [OllamaHostDiscovery]（host 持久化），
 * 暴露给 UI 的扁平方法：
 *
 * - [currentHost] / [testConnection] / [setHost] —— host 管理
 * - [listAvailable] —— 可用模型列表（含量化/参数信息）
 * - [showModel] —— 模型详情
 * - [pull] / [createModel] —— 拉取/创建模型
 * - [chat] / [streamChat] —— 对话
 */
/** 设置页需要观察的状态快照。 */
data class OllamaUiState(
    val host: String,
    val connected: Boolean? = null,
    val models: List<OllamaModel> = emptyList(),
    val pullingModel: String? = null,
)

class OllamaRepository(
    context: Context,
) {
    private val discovery = OllamaHostDiscovery(context)
    private var baseUrl: String = discovery.lastHost()

    private val client: OllamaClient
        get() = OllamaClient(baseUrl)

    /** 当前 host（`http://host:port`）。 */
    val host: String
        get() = baseUrl

    // ---- UI 状态（设置页 collect）----

    private val _state = MutableStateFlow(OllamaUiState(host = baseUrl))
    /** 设置页唯一需要 collect 的流：host / 连接状态 / 模型列表 / 拉取中模型。 */
    val state: StateFlow<OllamaUiState> = _state.asStateFlow()

    // ---- host 管理 ----

    /** 测试当前/指定 host 是否可达。 */
    suspend fun testConnection(host: String = baseUrl): Boolean {
        val ok = discovery.testHost(host)
        _state.update { it.copy(host = host, connected = ok) }
        return ok
    }

    /** 切换 host 并记住（设置页保存后调用；测试失败则不切换、仅更新展示 host）。 */
    suspend fun setHost(host: String): Boolean {
        val ok = discovery.testHost(host)
        if (ok) {
            baseUrl = host
            discovery.saveLastHost(host)
        }
        _state.update { it.copy(host = host, connected = ok) }
        return ok
    }

    /** 发现局域网可用 host（可达的在前，同时记住第一个可达的）。 */
    suspend fun discoverHosts(): List<String> = discovery.discover()

    /** 重新拉取记忆的 host（不重扫）。 */
    fun refreshLastHost() {
        baseUrl = discovery.lastHost()
        _state.update { it.copy(host = baseUrl, connected = null, models = emptyList()) }
    }

    // ---- 模型 ----

    /** 远端可用模型列表（并写入 [state]）。 */
    suspend fun listAvailable(): List<OllamaModel> {
        val list = runCatching { client.listModels() }.getOrElse { emptyList() }
        _state.update { it.copy(models = list) }
        return list
    }

    /** 模型详情。 */
    suspend fun showModel(id: String): OllamaShowResponse = client.showModel(id)

    /** 拉取模型（阻塞直到完成），期间 state.pullingModel 非空。 */
    suspend fun pull(id: String): PullResult {
        _state.update { it.copy(pullingModel = id) }
        return try {
            val result = client.pullModel(id)
            _state.update { it.copy(pullingModel = null) }
            result
        } catch (e: Exception) {
            _state.update { it.copy(pullingModel = null) }
            throw e
        }
    }

    /** 创建模型。 */
    suspend fun createModel(id: String, from: String? = null, modelfile: String? = null): PullResult =
        client.createModel(id, from, modelfile)

    // ---- 对话 ----

    /** 非流式对话。 */
    suspend fun chat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions? = null,
    ): OllamaChatResponse = client.chat(model, messages, options)

    /** 流式对话，逐 chunk 产出。 */
    fun streamChat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions? = null,
    ): Flow<OllamaChunk> = client.streamChat(model, messages, options)
}
