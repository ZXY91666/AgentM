package com.agentm.guiagent.agent

import com.agentm.guiagent.service.GuiAgentController
import com.agentm.guiagent.service.UiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * GUI agent 循环。参考 Mobile-Agent / AppAgent：
 *
 * ```
 * ┌───────────┐   截图+元素列表    ┌────────────┐  动作JSON  ┌──────────┐  执行  ┌─────┐
 * │ 任务目标  │ ─────────────────► │ 视觉理解   │ ─────────► │  解析    │ ─────► │执行 │──►
 * │ (goal)   │                     │ (LLM)      │            │ (Action) │        └──┬──┘
 * └───────────┘                     └────────────┘            └──────────┘           │
 *      ▲                                                                            │
 *      │                    ┌────────────┐  验证  ┌──────────┐                      │
 *      └────────────────── │  验证(下一轮)│ ◄───── │  观察    │ ◄────────────────────┘
 *                          │  Done? 成功?│         │  (截图)  │
 *                          └────────────┘         └──────────┘
 * ```
 *
 * 动作空间：[GuiAction] Tap / Swipe / Input / Back / Launch / Done / Error。
 * 最大步数由 [GuiTask.maxSteps] 控制；到顶自动 [GuiAction.Error]。
 *
 * 视觉理解后端：通过 [VisionBackend] 注入，支持 OpenAI 兼容 vision API
 * 或本地 Ollama 视觉模型。
 */
class GuiAgentLoop(
    private val controller: GuiAgentController,
    private val visionBackend: VisionBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** 一轮循环的观察 + 决策结果。 */
    data class StepResult(
        val stepIndex: Int,
        val action: GuiAction,
        val screenshotBase64: String?,
        val elements: List<UiElement>,
        val executed: Boolean,
        val observation: String? = null   // 执行后模型对结果的评价
    )

    /** 整个任务的最终结果。 */
    data class TaskResult(
        val task: GuiTask,
        val steps: List<StepResult>,
        val finalAction: GuiAction,
        val success: Boolean
    )

    /**
     * 视觉理解后端抽象。实现方负责把（截图 + 元素 + 任务）转成 LLM 调用，
     * 并把 LLM 输出解析为 [GuiAction]。
     *
     * 可用实现：
     * - [OpenAiCompatibleVisionBackend]：OpenAI 兼容 /v1/chat/completions
     * - [LocalOllamaVisionBackend]：本地 Ollama qwen2-vl / llava 等
     */
    fun interface VisionBackend {
        /**
         * @return 模型决策的 [GuiAction]；无法决策时返回 [GuiAction.Error]。
         */
        fun decide(
            task: GuiTask,
            stepIndex: Int,
            screenshotBase64: String?,
            screenshotFormat: String,
            elements: List<UiElement>,
            history: List<GuiAction>
        ): GuiAction
    }

    /**
     * 同步执行一个任务（阻塞当前线程直到完成 / 到顶）。
     * 在 orchestrator 里调用时包在 withContext(Dispatchers.IO) 里即可。
     */
    fun run(task: GuiTask): TaskResult {
        val steps = ArrayList<StepResult>(task.maxSteps)
        val history = ArrayList<GuiAction>()
        var finalAction: GuiAction = GuiAction.Error("任务未启动")
        var success = false

        for (step in 0 until task.maxSteps) {
            // 1. 截图 + 元素解析
            val bmp = controller.screenShot()
            val base64 = bmp?.let { encodePng(it) }
            val elements = controller.parseNodeTree()
                .take(MAX_ELEMENTS_FOR_LLM)   // 防止 prompt 过长

            // 2. 视觉理解 → 决策
            val decision = visionBackend.decide(
                task = task,
                stepIndex = step,
                screenshotBase64 = base64,
                screenshotFormat = task.screenshotFormat,
                elements = elements,
                history = history
            )
            history.add(decision)

            // 3. 终止条件：模型主动 Done / 到顶
            if (decision is GuiAction.Done) {
                finalAction = decision
                success = decision.success
                steps.add(
                    StepResult(step, decision, base64, elements, executed = false,
                        observation = "模型主动 Done(success=${decision.success})：${decision.summary}")
                )
                break
            }
            if (step == task.maxSteps - 1) {
                finalAction = GuiAction.Error("达到最大步数 ${task.maxSteps}，任务未完成")
                steps.add(
                    StepResult(step, decision, base64, elements, executed = false,
                        observation = "到顶强制结束")
                )
                break
            }

            // 4. 执行动作
            val executed = executeAction(decision)
            steps.add(
                StepResult(step, decision, base64, elements, executed = executed,
                    observation = if (executed) null else "执行失败（未启用 / 坐标越界 / 无焦点节点）")
            )

            // 5. 执行后短延时，让 UI 状态稳定再下一轮（run 为同步阻塞路径，用线程 sleep）
            Thread.sleep(SETTLE_MS)
        }

        return TaskResult(task, steps, finalAction, success)
    }

    /** 协程版：在 [scope] 里跑，不阻塞调用者。 */
    fun runAsync(task: GuiTask, onResult: (TaskResult) -> Unit) {
        scope.launch {
            val result = run(task)
            onResult(result)
        }
    }

    // region 动作执行

    private fun executeAction(action: GuiAction): Boolean = when (action) {
        is GuiAction.Tap -> {
            val (x, y) = action.elementIndex?.let { idx ->
                val el = controller.parseNodeTree().getOrNull(idx)
                if (el != null) Pair(el.center.first, el.center.second) else null
            } ?: Pair(action.x, action.y)
            controller.tap(x, y)
        }
        is GuiAction.Swipe ->
            controller.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
        is GuiAction.Input ->
            controller.type(action.text)
        is GuiAction.Back ->
            controller.pressBack()
        is GuiAction.Launch ->
            controller.launchApp(action.packageName, action.className)
        is GuiAction.Done, is GuiAction.Error ->
            false   // 不执行，仅作为终止 / 标记
    }

    // endregion

    // region 编码

    private fun encodePng(bitmap: android.graphics.Bitmap): String? {
        val out = ByteArrayOutputStream()
        return runCatching {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    // endregion

    companion object {
        private const val MAX_ELEMENTS_FOR_LLM = 60
        private const val SETTLE_MS = 400L
    }
}
