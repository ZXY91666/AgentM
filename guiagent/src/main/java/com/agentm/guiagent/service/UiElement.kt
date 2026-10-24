package com.agentm.guiagent.service

/**
 * 一个可交互 / 可读的 UI 元素，由无障碍树解析而来。
 * 供 GUI agent 的视觉理解（LLM）与动作规划使用。
 */
data class UiElement(
    val index: Int,               // 扁平化后的序号（LLM 以 index 引用元素）
    val text: String?,            // 可见文本
    val contentDescription: String?,
    val bounds: Rect,            // 屏幕绝对坐标（px）
    val className: String?,
    val package: String?,
    val resourceEntry: String?,
    val clickable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    val longClickable: Boolean,
) {
    /** LLM 友好的紧凑描述。 */
    fun describe(): String {
        val parts = StringBuilder()
        if (!text.isNullOrBlank()) parts.append("text='").append(text).append("' ")
        if (!contentDescription.isNullOrBlank()) parts.append("desc='").append(contentDescription).append("' ")
        parts.append(className ?: "View").append("@").append(bounds)
        if (clickable) parts.append(" [clickable]")
        if (editable) parts.append(" [editable]")
        if (scrollable) parts.append(" [scrollable]")
        if (!enabled) parts.append(" [disabled]")
        if (checked == true) parts.append(" [checked]")
        if (checked == false) parts.append(" [unchecked]")
        return parts.toString()
    }

    /** 中心点，供 Tap 动作使用。 */
    val center: Pair<Int, Int>
        get() = Pair((left + right) / 2, (top + bottom) / 2)

    val left: Int get() = bounds.left
    val top: Int get() = bounds.top
    val right: Int get() = bounds.right
    val bottom: Int get() = bounds.bottom
}

/** 简单矩形（屏幕绝对坐标，px）。 */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    override fun toString(): String = "Rect(l=$left,t=$top,r=$right,b=$bottom)"
}
