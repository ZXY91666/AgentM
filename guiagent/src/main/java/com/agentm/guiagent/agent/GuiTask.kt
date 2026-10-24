package com.agentm.guiagent.agent

/**
 * 一个 GUI agent 任务：目标 + 成功判定 + 最大步数。
 *
 * @property goal            自然语言目标（给 LLM 当"任务描述"）
 * @property successCriteria 成功判定（LLM 在验证阶段对照它判断是否完成）
 * @property maxSteps        最多执行多少轮"截图→决策→执行"；到顶就停止并输出 [GuiAction.Error]
 * @property context         可选的附加上下文（如"当前在淘宝 App"、"账号密码是 X"）
 * @property screenshotFormat "png" | "jpeg"
 */
data class GuiTask(
    val goal: String,
    val successCriteria: String = "LLM 认为任务目标已达成",
    val maxSteps: Int = 12,
    val context: String? = null,
    val screenshotFormat: String = "png"
) {
    init {
        require(maxSteps in 1..64) { "maxSteps 必须在 1..64 内，收到 $maxSteps" }
    }

    /** 渲染成 LLM 可读的任务描述。 */
    fun promptBlock(): String {
        val sb = StringBuilder()
        sb.append("GOAL: ").append(goal).append('\n')
        sb.append("SUCCESS: ").append(successCriteria).append('\n')
        if (!context.isNullOrBlank()) sb.append("CONTEXT: ").append(context).append('\n')
        sb.append("MAX_STEPS: ").append(maxSteps)
        return sb.toString()
    }
}
