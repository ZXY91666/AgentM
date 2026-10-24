package com.agentm.guiagent.service

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.ArrayDeque

/**
 * 对 [GuiAgentService] 的高层 API，orchestrator 把它当 tool 调用。
 *
 * 设计：
 * - 不直接持有 service 实例，而是每次调用前重新解析 [GuiAgentService.instance]，
 *   以便用户随时开关无障碍服务时无需重建 controller。
 * - 提供 [requireService] 守卫：服务未启用时抛 [ServiceNotEnabledException]。
 * - 所有动作都是同步的，但 [screenShot] 内部会阻塞最多 1.5s 等系统回调。
 *
 * 用法（orchestrator 侧伪代码）：
 * ```kotlin
 * val gui = GuiAgentController(context)
 * val bmp = gui.screenShot()
 * gui.tap(540, 1200)
 * gui.swipe(540, 1800, 540, 600, durationMs = 250)
 * gui.type("你好")
 * gui.pressBack()
 * gui.launchApp("com.android.chrome")
 * ```
 */
class GuiAgentController {

    /** 服务未启用时抛出。orchestrator 可 catch 后提示用户开启。 */
    class ServiceNotEnabledException : RuntimeException(
        "GuiAgentService is not enabled. Please enable it in " +
            "Settings → Accessibility → AgentM GUI Agent."
    )

    /** 截图的可选回退：当 service.takeScreenshot 失败时，可用 shell `screencap` 兜底。 */
    fun interface ScreenshotFallback {
        fun capture(): Bitmap?
    }

    private val screenshotFallback: ScreenshotFallback?

    constructor(screenshotFallback: ScreenshotFallback? = null) {
        this.screenshotFallback = screenshotFallback
    }

    // region 状态

    /** 服务是否已连接。 */
    fun isEnabled(): Boolean = GuiAgentService.isEnabled()

    /** 确保服务已启用，否则抛 [ServiceNotEnabledException]。 */
    fun requireService(): GuiAgentService =
        GuiAgentService.instance ?: throw ServiceNotEnabledException()

    // endregion

    // region 动作 API

    /** 截取整屏；失败时走 fallback，仍失败返回 null。 */
    fun screenShot(): Bitmap? {
        return try {
            val bmp = requireService().screenShot()
            if (bmp != null) bmp else screenshotFallback?.capture()
        } catch (e: ServiceNotEnabledException) {
            // 服务未启用：走 fallback
            screenshotFallback?.capture()
        }
    }

    /** 点按。未启用时返回 false。 */
    fun tap(x: Int, y: Int): Boolean =
        runCatching { requireService().tap(x, y) }.getOrDefault(false)

    /** 滑动。未启用时返回 false。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean =
        runCatching { requireService().swipe(x1, y1, x2, y2, durationMs) }.getOrDefault(false)

    /** 输入文本（找当前焦点 editable 节点）。 */
    fun type(text: String): Boolean =
        runCatching { requireService().type(text) }.getOrDefault(false)

    /** 系统返回。 */
    fun pressBack(): Boolean =
        runCatching { requireService().pressBack() }.getOrDefault(false)

    /** 系统 home。 */
    fun pressHome(): Boolean =
        runCatching { requireService().pressHome() }.getOrDefault(false)

    /** 系统最近任务。 */
    fun pressRecents(): Boolean =
        runCatching { requireService().pressRecents() }.getOrDefault(false)

    /** 打开通知栏。 */
    fun openNotificationShade(): Boolean =
        runCatching { requireService().openNotificationShade() }.getOrDefault(false)

    /** 用 package（或 package+class）拉起应用。 */
    fun launchApp(packageName: String, className: String? = null, action: String? = null): Boolean =
        runCatching { requireService().launchApp(packageName, className, action) }.getOrDefault(false)

    /** 用 [Intent] 拉起（更灵活：可传任意 action + data）。 */
    fun startActivity(action: String, packageName: String, className: String?, data: String?) {
        runCatching {
            requireService().launchAppWithAction(action, packageName, className, data)
        }
    }

    /** 判断应用是否处于前台。 */
    fun isAppInForeground(packageName: String): Boolean =
        runCatching { requireService().isAppInForeground(packageName) }.getOrDefault(false)

    // endregion

    // region 无障碍树

    /**
     * 解析当前窗口的无障碍树，返回扁平化的 [UiElement] 列表。
     * 给 agent 视觉理解的 LLM 当元素参考（带 index、bounds、clickable 等）。
     * 服务未启用时返回空列表。
     */
    fun parseNodeTree(): List<UiElement> {
        val root = runCatching { requireService().currentRoot() }.getOrNull() ?: return emptyList()
        return parseNodeTree(root)
    }

    /** 解析任意 root（单测 / 离线场景）。 */
    fun parseNodeTree(root: android.view.accessibility.AccessibilityNodeInfo): List<UiElement> {
        val out = ArrayList<UiElement>(256)
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var index = 0
        while (queue.isNotEmpty()) {
            val n = queue.poll()
            // 过滤掉无意义节点：空 bounds、隐藏、纯容器
            val androidBounds = android.graphics.Rect()
            n.getBoundsInScreen(androidBounds)
            val hasText = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
            val isInteractive = n.isClickable || n.isFocusable || n.isEditable ||
                n.isScrollable || n.isCheckable || n.isLongClickable
            if (hasText || isInteractive) {
                out.add(
                    UiElement(
                        index = index++,
                        text = n.text?.toString(),
                        contentDescription = n.contentDescription?.toString(),
                        bounds = Rect(androidBounds.left, androidBounds.top, androidBounds.right, androidBounds.bottom),
                        className = n.className?.toString(),
                        `package` = n.packageName?.toString(),
                        resourceEntry = n.viewIdResourceName,
                        clickable = n.isClickable,
                        focusable = n.isFocusable,
                        scrollable = n.isScrollable,
                        editable = n.isEditable,
                        enabled = n.isEnabled,
                        checked = n.isChecked,
                        longClickable = n.isLongClickable
                    )
                )
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i)
                if (c != null) queue.add(c)
            }
        }
        return out
    }

    /** 把 [UiElement] 列表格式化成 LLM 友好的字符串（带 index、describe）。 */
    fun formatElementsForLlm(elements: List<UiElement>): String {
        if (elements.isEmpty()) return "(no interactive elements found)"
        return elements
            .map { "[${it.index}] ${it.describe()}" }
            .joinToString("\n")
    }

    // endregion

    // region 辅助

    /** 截取并返回可被 base64 编码的 PNG（给 LlmGateway 视觉理解用）。 */
    fun screenShotAsBase64Png(): String? {
        val bmp = screenShot() ?: return null
        val copied = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val bytes = java.io.ByteArrayOutputStream()
        copied.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bytes)
        return android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // endregion
}

/**
 * 扩展方法：让 [GuiAgentService] 支持带任意 action + data 的 startActivity。
 * 声明为 extension 是为了不污染 service 自身；controller.startActivity 调用它。
 */
fun GuiAgentService.launchAppWithAction(
    action: String,
    packageName: String,
    className: String?,
    data: String?
): Boolean {
    return try {
        val intent = android.content.Intent(action).apply {
            setPackage(packageName)
            if (className != null) component = android.content.ComponentName(packageName, className)
            if (data != null) this.data = android.net.Uri.parse(data)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        true
    } catch (e: Exception) {
        android.util.Log.w("GuiAgentService", "launchAppWithAction failed", e)
        false
    }
}
