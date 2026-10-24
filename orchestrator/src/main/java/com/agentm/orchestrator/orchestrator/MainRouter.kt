package com.agentm.orchestrator.orchestrator

import com.agentm.core.gateway.LlmGateway
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 输入路由：决定一次请求进哪张图 / 哪个子 agent。
 *
 * 两级策略：
 * 1. **关键词规则**：[rules] 表（图 id → 触发词列表，小写子串匹配），零成本离线可用
 * 2. **LLM 分类**（可选）：规则全 miss 且注入了 [gateway] + [addDescription] 时，
 *    让 LLM 从候选图里选一个（只回图 id）；失败/超时回退 [defaultGraphId]
 *
 * 不依赖 Compose；纯数据 + 可选协程分类。
 */
class MainRouter(
    defaultGraphId: String = "default",
    private val gateway: LlmGateway? = null
) {
    private var defaultGraphId: String = defaultGraphId
    /** 图 id → 触发词列表（小写）。 */
    private val rules = LinkedHashMap<String, MutableList<String>>()
    /** 图 id → 一句话描述（给 LLM 分类用）。 */
    private val descriptions = LinkedHashMap<String, String>()

    fun setDefaultGraphId(id: String): MainRouter {
        defaultGraphId = id
        return this
    }

    fun addRule(graphId: String, vararg keywords: String): MainRouter {
        (rules.getOrPut(graphId) { mutableListOf() }) += keywords.map { it.lowercase() }
        return this
    }

    fun addDescription(graphId: String, desc: String): MainRouter {
        descriptions[graphId] = desc
        return this
    }

    /** 纯规则路由；全 miss 返回 null。 */
    fun routeByRule(input: String, available: List<String>): String? {
        val lower = input.lowercase()
        for ((graphId, kws) in rules) {
            if (graphId !in available) continue
            if (kws.any { lower.contains(it) }) return graphId
        }
        return null
    }

    /**
     * 路由入口：先规则后 LLM，兜底默认图。
     * @param available 已注册的图 id 列表
     */
    fun route(input: String, available: List<String>): String {
        if (available.isEmpty()) return defaultGraphId
        routeByRule(input, available)?.let { return it }
        val llmPick = classifyByLlm(input, available)
        if (llmPick != null) return llmPick
        return if (defaultGraphId in available) defaultGraphId else available.first()
    }

    /** LLM 分类（可选）：让模型在候选里选一个图 id。未注入 gateway / 无候选描述 / 失败 → null。 */
    private fun classifyByLlm(input: String, available: List<String>): String? {
        val gw = gateway ?: return null
        val candidates = available.filter { descriptions.containsKey(it) }
        if (candidates.isEmpty()) return null
        val prompt = "You are a router. Pick the graph id that best handles the user request. " +
            "Candidates:\n" +
            candidates.joinToString("\n") { "${it}: ${descriptions[it]}" } +
            "\nReply with ONLY the id, nothing else."

        val done = CompletableDeferred<String>()
        val scope = CoroutineScope(Dispatchers.IO)
        val realJob = gw.chat(
            scope = scope,
            messages = listOf(ChatMessage.user(prompt + "\nUser request: " + input)),
            tools = null,
            onEvent = { ev ->
                if (ev is AgentEvent.Done) done.complete(ev.finalMessage.content ?: "")
            },
        )
        val raw = try {
            runBlocking { withTimeoutOrNull(5_000) { done.await() } } ?: ""
        } finally {
            realJob.cancel()
        }
        return raw.trim().split(Regex("\\s")).firstOrNull { it in candidates }
            ?: raw.trim().takeIf { it in candidates }
    }
}
