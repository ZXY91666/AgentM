package com.agentm.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/**
 * AgentM 微交互：按压回弹 + 工具气泡进出。
 *
 * 参数约定（组件侧不允许自造）：
 *  - 按压回弹：[SpringSpecs.Bouncy]。按到 0.94，松手弹回 1.0（过冲 ~8%，"像弹簧"）。
 *  - 工具气泡出现：[SpringSpecs.Bouncy]，alpha 0→1 与高度 0→1 同步弹（观感 ~420ms）。
 *  - 工具气泡消失：[SpringSpecs.Smooth]，无过冲、最快收（观感 ~240ms，不拖沓）。
 */

// ---------- 按压回弹 ----------

/**
 * 给任意可点击组件加"按压缩小 + 弹簧回弹"。
 * [onTap] 在松手时触发（tap 语义；滑出按下的手势会被 pointerInput 自然丢弃）。
 *
 * 用法：
 * ```
 * Button(
 *   onClick = { ... },
 *   modifier = Modifier.amPressBounce(onTap = { ... }),
 * )
 * ```
 * scale 由内部状态驱动，调用侧无需再接 graphicsLayer。
 */
fun Modifier.amPressBounce(
    pressedScale: Float = 0.94f,
    spec: SpringSpec<Float> = SpringSpecs.Bouncy,
    onTap: () -> Unit,
): Modifier = composed {
    var scale by remember { mutableFloatStateOf(1f) }
    var pressed by remember { mutableStateOf(false) }

    // 弹簧驱动：pressed 变化时把 scale 弹到新目标值
    LaunchedEffect(pressed) {
        val target = if (pressed) pressedScale else 1f
        val anim = Animatable(scale)
        anim.animateTo(target, spec) {
            scale = value
        }
    }

    this
        .pointerInput(spec) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.type == PointerType.Mouse || down.type == PointerType.Stylus) {
                    return@awaitEachGesture // 桌面设备不驱动按压回弹
                }
                pressed = true
                // 手指跟随：持续读取直到抬起，并记录最后抬起点
                var stillDown = true
                var upPosition: Offset? = null
                while (stillDown) {
                    val event = awaitPointerEvent()
                    stillDown = event.changes.any { it.pressed }
                    if (!stillDown) upPosition = event.changes.firstOrNull()?.position
                }
                pressed = false
                // 抬起点与按下点基本重合才算 tap（容差 8dp 内视为未滑动）
                if (upPosition != null &&
                    kotlin.math.abs(upPosition.x - down.position.x) < 8f &&
                    kotlin.math.abs(upPosition.y - down.position.y) < 8f
                ) onTap()
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

// ---------- 工具气泡进出 ----------

/** 工具气泡出现：alpha 与高度同步弹入（[SpringSpecs.Bouncy]，~420ms 观感）。 */
@Composable
fun toolBubbleAppear(visible: Boolean): ToolBubbleState {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = SpringSpecs.Bouncy,
        label = "am-tool-bubble-in",
    )
    val height by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = SpringSpecs.Bouncy,
        label = "am-tool-bubble-height-in",
    )
    return ToolBubbleState(visible, alpha, height)
}

/** 工具气泡消失：alpha 与高度同步收回（[SpringSpecs.Smooth]，不拖沓）。 */
@Composable
fun toolBubbleDisappear(visible: Boolean): ToolBubbleState {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = SpringSpecs.Smooth,
        label = "am-tool-bubble-out",
    )
    val height by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = SpringSpecs.Smooth,
        label = "am-tool-bubble-height-out",
    )
    return ToolBubbleState(visible, alpha, height)
}

/** 工具气泡动画状态：alpha 接 graphicsLayer，heightFraction 接高度约束。 */
data class ToolBubbleState(
    val visible: Boolean,
    val alpha: Float,
    val heightFraction: Float,
)

/** 工具气泡进/出时长观感基线（与 SpringSpecs 配套，供审计）。 */
object ToolBubbleMotion {
    const val IN_MS = 420
    const val OUT_MS = 240
}
