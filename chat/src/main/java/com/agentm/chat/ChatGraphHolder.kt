package com.agentm.chat

import com.agentm.localllm.repo.OllamaRepository
import com.agentm.orchestrator.orchestrator.Orchestrator

/**
 * chat 模块的全局依赖注入点。
 *
 * chat 不依赖 :app（依赖方向必须是 app → chat），因此 app 在 Application.onCreate
 * 里把 [Orchestrator] / [OllamaRepository] 注入进来；chat 各屏从这里取。
 *
 * 用法（app 侧）：
 * ```
 * ChatGraphHolder.bind(orchestrator, ollamaRepo)
 * ```
 */
object ChatGraphHolder {
    @Volatile
    private var orchestratorRef: Orchestrator? = null

    @Volatile
    private var repoRef: OllamaRepository? = null

    /** app 启动时绑定（可重复调用，后者覆盖）。 */
    fun bind(orchestrator: Orchestrator, ollamaRepo: OllamaRepository) {
        orchestratorRef = orchestrator
        repoRef = ollamaRepo
    }

    /** 取当前绑定的 Orchestrator；未绑定时抛 IllegalStateException。 */
    val orchestrator: Orchestrator
        get() = orchestratorRef ?: throw IllegalStateException(
            "ChatGraphHolder not bound yet. Call ChatGraphHolder.bind(...) in Application.onCreate()."
        )

    /** 取当前绑定的 OllamaRepository；未绑定时抛 IllegalStateException。 */
    val ollamaRepo: OllamaRepository
        get() = repoRef ?: throw IllegalStateException(
            "ChatGraphHolder not bound yet. Call ChatGraphHolder.bind(...) in Application.onCreate()."
        )

    /** 是否已绑定。 */
    val isBound: Boolean get() = orchestratorRef != null && repoRef != null
}
