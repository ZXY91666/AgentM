package com.agentm.guiagent.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机操作 agent 的无障碍服务。
 *
 * 职责：
 * - 作为 [AccessibilityService] 被系统启用（用户需在系统无障碍设置里打开）。
 * - 封装高层动作：截图、点击、滑动、输入、返回、拉起应用等。
 * - 提供无障碍树根节点 [currentRoot] 供 agent 循环解析。
 *
 * manifest 注册（由 app 模块负责，见 [GuiAgentController] 类注释）：
 * ```xml
 * <service
 *     android:name="com.agentm.guiagent.service.GuiAgentService"
 *     android:exported="false"
 *     android:label="AgentM GUI Agent"
 *     android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.accessibilityservice.AccessibilityService" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.accessibilityservice"
 *         android:resource="@xml/accessibility_config" />
 * </service>
 * ```
 */
class GuiAgentService : AccessibilityService() {

    companion object {
        private const val TAG = "GuiAgentService"

        @Volatile
        var instance: GuiAgentService? = null
            private set

        /** 服务是否已连接并被系统激活。 */
        @JvmStatic
        fun isEnabled(): Boolean = instance != null
    }

    private val connectionListeners = CopyOnWriteArrayList<() -> Unit>()
    private val lastScreenshot = AtomicReference<Bitmap?>(null)

    /** 主线程 handler：无障碍回调 / dispatchGesture 都在主线程执行。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // region 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectionListeners.forEach { it() }
        Log.i(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 当前无需处理事件流；保留方法以维持回调可用性。
    }

    override fun onInterrupt() {
        // 系统在重启无障碍服务时回调。保持 instance。
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        connectionListeners.forEach { it() }
        Log.i(TAG, "onUnbind")
        return super.onUnbind(intent)
    }

    // endregion

    // region 连接监听

    fun addConnectionListener(listener: () -> Unit) {
        connectionListeners += listener
        if (isEnabled()) listener()
    }

    // endregion

    // region 高层动作 API

    /** 当前活跃窗口根节点；可能为 null。 */
    @SuppressLint("MissingPermission")
    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * 截取整个屏幕。
     *
     * 使用 AccessibilityService.takeScreenshot()（Android 13+，无需额外权限）。
     * 低版本或调用失败时返回最近一次截图，或 null。
     * 调用方可用 shell `screencap` 兜底。
     */
    @SuppressLint("MissingPermission")
    fun screenShot(): Bitmap? {
        if (Build.VERSION.SDK_INT >= 33) {
            val latch = CountDownLatch(1)
            val box = AtomicReference<Bitmap>()
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    AccessibilityService.RequestScreenshotFlags.DEFAULT,
                    Bundle()
                ) { res ->
                    if (res != null) {
                        val b = res.bitmap
                        box.set(b)
                        lastScreenshot.set(b)
                    }
                    latch.countDown()
                }
            }
            runCatching { latch.await(1_500, TimeUnit.MILLISECONDS) }
            val bmp = box.get()
            if (bmp != null) return bmp
        }
        return lastScreenshot.get()
    }

    /** 最后一次成功截图（供回溯与验证）。 */
    fun lastScreenshot(): Bitmap? = lastScreenshot.get()

    /**
     * 点按 (x, y)。坐标为屏幕绝对坐标（px）。
     * 通过 GestureDescription + dispatchGesture 实现。
     */
    @SuppressLint("MissingPermission")
    fun tap(x: Int, y: Int): Boolean {
        val root = currentRoot() ?: return false
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        val gesture = root.createGesture().Builder()
            .addStroke(path, 0L, 50L)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 滑动 (x1,y1) -> (x2,y2)，durationMs 控制时长。 */
    @SuppressLint("MissingPermission")
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val root = currentRoot() ?: return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = root.createGesture().Builder()
            .addStroke(path, 0L, durationMs.toLong())
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 在指定节点输入文本。
     * 先定位焦点 editable 节点，找不到则回退到 rootInActiveWindow 下第一个 editable。
     * 找不到 editable 节点时返回 false。
     */
    @SuppressLint("MissingPermission")
    fun type(text: String): Boolean {
        val root = currentRoot() ?: return false
        val editableNode = findEditableNode(root) ?: return false
        return editableNode.performGlobalAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()
            if (n.isEditable) return n
            if (n.className?.endsWith("EditText") == true) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i)
                if (c != null) queue.add(c)
            }
        }
        return null
    }

    /** 触发系统返回。 */
    fun pressBack(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    /** 触发系统 home。 */
    fun pressHome(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /** 触发最近任务。 */
    fun pressRecents(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /** 打开通知栏。 */
    fun openNotificationShade(): Boolean =
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    /** 用 package（或 package+class）拉起应用。 */
    @SuppressLint("MissingPermission")
    fun launchApp(packageName: String, className: String? = null, action: String? = null): Boolean {
        return try {
            val intent = Intent().apply {
                setPackage(packageName)
                if (action != null) this.action = action
                if (className != null) {
                    component = ComponentName(packageName, className)
                } else {
                    this.action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "launchApp($packageName, $className) failed", e)
            false
        }
    }

    /** 应用当前是否处于前台（粗判断：看 rootInActiveWindow 的 pkg 是否为目标）。 */
    fun isAppInForeground(packageName: String): Boolean {
        val root = currentRoot() ?: return false
        val currentPkg = root.packageName?.toString() ?: return false
        return currentPkg == packageName
    }

    // endregion

    // region 屏幕信息

    /** 屏幕分辨率（px）。 */
    fun screenSize(): Point {
        val dm = resources.displayMetrics
        return Point(dm.widthPixels, dm.heightPixels)
    }

    // endregion

    /** 主动断开连接（通常在 Application 退出时调用）。 */
    fun disconnect() {
        instance = null
        connectionListeners.forEach { it() }
    }
}
