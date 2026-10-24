package com.agentm.localllm.discovery

import android.content.Context
import android.content.SharedPreferences
import com.agentm.localllm.client.OllamaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Ollama host 发现：localhost:11434 优先，再扫局域网常见网段。
 *
 * 判定「可达」= `GET /api/version` 成功；记忆的 lastHost 通过
 * SharedPreferences（key `last_ollama_host`）持久化。
 */
class OllamaHostDiscovery(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 扫描候选 host（localhost + 记忆的 lastHost + 局域网常见 IP）。
     * 返回 baseUrls（`http://host:port`）：可达的在前，其余保留供手动选择；
     * 第一个可达的会写入 SharedPreferences。
     */
    suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        val candidates = candidateHosts()
        val results = coroutineScope {
            candidates.map { host ->
                async {
                    val url = "http://$host:$port"
                    val ok = runCatching { OllamaClient(url, CONNECT_TIMEOUT_MS).healthCheck() }
                        .getOrDefault(false)
                    url to ok
                }
            }.awaitAll()
        }
        val firstOk = results.firstOrNull { it.second }?.first
        if (firstOk != null) saveLastHost(firstOk)
        return@withContext results.sortedBy { !it.second }.map { it.first }
    }

    /** 候选列表：localhost、记忆 host、RFC1918 网段常见尾号。 */
    private fun candidateHosts(): List<String> {
        val rememberedHost = rememberedBaseUrl()
            ?.substringAfter("://")
            ?.substringBefore(":")
            .orEmpty()
        val lan = listOf(
            "192.168.0.1", "192.168.0.2", "192.168.0.3", "192.168.0.4", "192.168.0.5",
            "192.168.0.10", "192.168.1.1", "192.168.1.2", "192.168.1.3", "192.168.1.4",
            "192.168.1.5", "192.168.1.10", "192.168.1.100", "10.0.0.1", "172.16.0.1",
        )
        return listOf("localhost", rememberedHost) + lan
    }

    /** 仅测试单个 host 是否可达（设置页「测试连接」用）。 */
    suspend fun testHost(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { OllamaClient(baseUrl, TEST_TIMEOUT_MS).healthCheck() }.getOrDefault(false)
    }

    /** 记忆的 host（baseUrl 形式）；无记忆时返回 `http://localhost:11434`。 */
    fun lastHost(): String =
        rememberedBaseUrl() ?: "http://localhost:$port"

    private fun rememberedBaseUrl(): String? =
        prefs.getString(KEY_LAST_HOST, null)?.let {
            if (it.startsWith("http")) it else "http://$it:$port"
        }

    /** 记住最后一次使用的 host（`http://host:port` 形式）。 */
    fun saveLastHost(baseUrl: String) {
        prefs.edit().putString(KEY_LAST_HOST, baseUrl).apply()
    }

    /** 清除记忆（重新扫描时用）。 */
    fun clearLastHost() {
        prefs.edit().remove(KEY_LAST_HOST).apply()
    }

    companion object {
        const val DEFAULT_PORT = 11434
        const val PREFS_NAME = "localllm_hosts"
        const val KEY_LAST_HOST = "last_ollama_host"
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val TEST_TIMEOUT_MS = 5_000L
    }
}
