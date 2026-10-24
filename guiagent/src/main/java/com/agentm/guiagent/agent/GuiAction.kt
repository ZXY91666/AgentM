package com.agentm.guiagent.agent

/**
 * GUI agent 的动作空间。参考 Mobile-Agent / AppAgent 的设计：
 * 视觉模型看到"截图 + 可交互元素列表 + 任务目标"，输出一个动作 JSON，
 * 框架解析为 [GuiAction] 并执行。
 *
 * 动作类型：
 * - [Tap]    点按屏幕坐标（元素 index 中心或屏幕绝对坐标）
 * - [Swipe]  滑动（滚列表、下拉刷新、返回手势）
 * - [Input]  在焦点编辑框输入文本
 * - [Back]   系统返回
 * - [Launch] 拉起应用
 * - [Done]   任务完成（成功或放弃）
 * - [Error]  模型返回了无法解析 / 执行失败的动作
 */
sealed class GuiAction {

    abstract val reasoning: String   // 模型为什么做这个动作

    data class Tap(
        val x: Int,
        val y: Int,
        val elementIndex: Int? = null,   // 可选：对应的可交互元素 index
        override val reasoning: String
    ) : GuiAction()

    data class Swipe(
        val x1: Int, val y1: Int,
        val x2: Int, val y2: Int,
        val durationMs: Int = 300,
        override val reasoning: String
    ) : GuiAction()

    data class Input(
        val text: String,
        override val reasoning: String
    ) : GuiAction()

    data class Back(
        override val reasoning: String
    ) : GuiAction()

    data class Launch(
        val packageName: String,
        val className: String? = null,
        override val reasoning: String
    ) : GuiAction()

    data class Done(
        val success: Boolean,
        val summary: String,
        override val reasoning: String
    ) : GuiAction()

    data class Error(
        val message: String,
        override val reasoning: String = ""
    ) : GuiAction()

    companion object {
        const val TYPE_TAP = "tap"
        const val TYPE_SWIPE = "swipe"
        const val TYPE_INPUT = "input"
        const val TYPE_BACK = "back"
        const val TYPE_LAUNCH = "launch"
        const val TYPE_DONE = "done"

        /**
         * 把模型输出的 JSON 字符串解析成 [GuiAction]。
         * 接受的 JSON 形状（最外层是动作 JSON 或带 "action" 字段的对象）：
         * ```json
         * {"type":"tap","x":540,"y":1200,"elementIndex":3,"reasoning":"点登录按钮"}
         * {"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":600,"durationMs":250,"reasoning":"上滚"}
         * {"type":"input","text":"hello","reasoning":"输入用户名"}
         * {"type":"back","reasoning":"返回上一页"}
         * {"type":"launch","packageName":"com.android.chrome","reasoning":"打开浏览器"}
         * {"type":"done","success":true,"summary":"已完成","reasoning":"达到目标"}
         * ```
         * 解析失败 / 类型未知时返回 [Error]。
         */
        @JvmStatic
        fun fromJson(json: String): GuiAction {
            val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
                ?: return Error("无法解析 JSON：$json")
            return when (val type = obj.optString("type", "").lowercase()) {
                TYPE_TAP -> {
                    val x = obj.optInt("x", 0)
                    val y = obj.optInt("y", 0)
                    val el = obj.optInt("elementIndex", Int.MIN_VALUE)
                    Tap(
                        x = x, y = y,
                        elementIndex = if (el == Int.MIN_VALUE) null else el,
                        reasoning = obj.optString("reasoning", "")
                    )
                }
                TYPE_SWIPE -> {
                    val duration = obj.optInt("durationMs", 300)
                    Swipe(
                        x1 = obj.optInt("x1", 0), y1 = obj.optInt("y1", 0),
                        x2 = obj.optInt("x2", 0), y2 = obj.optInt("y2", 0),
                        durationMs = if (duration in 50..5000) duration else 300,
                        reasoning = obj.optString("reasoning", "")
                    )
                }
                TYPE_INPUT -> Input(
                    text = obj.optString("text", ""),
                    reasoning = obj.optString("reasoning", "")
                )
                TYPE_BACK -> Back(reasoning = obj.optString("reasoning", ""))
                TYPE_LAUNCH -> Launch(
                    packageName = obj.optString("packageName", ""),
                    className = obj.optString("className").takeIf { it.isNotEmpty() },
                    reasoning = obj.optString("reasoning", "")
                )
                TYPE_DONE -> Done(
                    success = obj.optBoolean("success", false),
                    summary = obj.optString("summary", ""),
                    reasoning = obj.optString("reasoning", "")
                )
                else -> Error("未知动作类型 '$type'：$json")
            }
        }
    }
}
