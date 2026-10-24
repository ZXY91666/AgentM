package com.agentm.core.gateway

import com.agentm.core.LlmException
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.core.model.LlmConfig
import com.agentm.core.model.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 双通道网关工厂：本地 Ollama 主 + OpenAI 兼容云端备，按健康状态自动 fallback。
 *
 * 策略（见 docs/integration-notes.md）：
 * - 主通道失败 [LlmException.Kind.UNREACHABLE] / SERVER / AUTH → 切备通道
 * - BAD_REQUEST 不切（参数问题换通道无效）
 * - 坏通道进入 60s 冷却，期间直接用另一通道
 */
class LlmGatewayFactory(
    private val localConfig: LlmConfig,
    private val cloudConfig: LlmConfig,
    private val http: OkHttpClient = defaultClient(),
) {
    private val localGateway: LlmGateway? =
        if (localConfig.isUsable) OllamaGateway(localConfig) else null
    private val cloudGateway: LlmGateway? =
        if (cloudConfig.isUsable) OpenAICompatibleGateway(http, cloudConfig) else null

    @Volatile private var localBadUntil: Long = 0
    @Volatile private var cloudBadUntil: Long = 0

    /** 当前首选 gateway（主通道在冷却则让位给备通道）。 */
    fun preferred(): LlmGateway? {
        val now = System.currentTimeMillis()
        val localOk = localGateway != null && now >= localBadUntil
        val cloudOk = cloudGateway != null && now >= cloudBadUntil
        return when {
            localOk -> localGateway
            cloudOk -> cloudGateway
            else -> localGateway ?: cloudGateway // 全冷却也返回一个，让错误自然冒出
        }
    }

    /** 带 fallback 的 chat：主通道失败且错误可切换 → 自动换备通道重跑一次。 */
    fun chat(
        scope: CoroutineScope,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        onEvent: (AgentEvent) -> Unit,
        onDone: (AgentEvent) -> Unit = {},
    ): Job {
        val primary = preferred() ?: run {
            return scope.launch {
                val ev = AgentEvent.Error("无可用 LLM 通道", LlmException.auth("no usable channel"))
                onEvent(ev); onDone(ev)
            }
        }
        val fallback = if (primary === localGateway) cloudGateway else localGateway
        return scope.launch {
            var usedFallback = false
            primary.chat(
                scope = scope, messages = messages, tools = tools,
                onEvent = onEvent,
                onDone = { ev ->
                    if (ev is AgentEvent.Error && fallback != null && !usedFallback &&
                        shouldFallback(ev.cause)
                    ) {
                        usedFallback = true
                        markBad(primary)
                        onEvent(AgentEvent.Delta("\n[切换通道: ${fallback.channelName}]\n"))
                        fallback.chat(scope, messages, tools, onEvent, onDone)
                    } else {
                        onDone(ev)
                    }
                },
            ).join()
        }
    }

    private fun shouldFallback(cause: Throwable?): Boolean = when (cause) {
        is LlmException -> when (cause.kind) {
            LlmException.Kind.UNREACHABLE,
            LlmException.Kind.SERVER,
            LlmException.Kind.AUTH -> true
            else -> false
        }
        else -> false
    }

    private fun markBad(g: LlmGateway) {
        val until = System.currentTimeMillis() + COOLDOWN_MS
        if (g === localGateway) localBadUntil = until else cloudBadUntil = until
    }

    companion object {
        const val COOLDOWN_MS = 60_000L
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
