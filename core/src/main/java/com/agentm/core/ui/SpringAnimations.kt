package com.agentm.core.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue

/**
 * AgentM 通用弹簧动画扩展。
 *
 * 组件侧统一从这里取参数（不允许自造，与 [SpringSpecs] 的约定一致）：
 *  - [amFloat] / [amBool]        —— 通用弹簧状态
 *  - [amFadeInAlpha] / [amFadeOutAlpha] —— 带惯性的淡入/淡出
 *  - [amSlideIn] / [amSlideOut]  —— 滑入/滑出
 *  - [staggerIndex]              —— 列表项交错入场延迟
 *  - [amPressScaleState]         —— 按压回弹（卡片/按钮微交互）
 */

/** 列表项交错入场：每项延迟（ms）。第 [index] 项 = index × 40ms。 */
const val STAGGER_PER_ITEM_MS = 40L

/** 转场时长基线（SpringSpecs.responseDurationMs 缺省用）。 */
const val TRANSITION_MS = 450

/** 流式渲染节奏：每攒 16 个字符触发一次高度弹簧。 */
const val STREAM_BATCH_CHARS = 16

/** 通用：target 每次变化都弹一下（Float）。返回 [State]，直接接
 *  graphicsLayer 的 alpha/scale/translationY。默认 [SpringSpecs.Bouncy]。 */
@Composable
fun amFloat(
    target: Float,
    spec: SpringSpec<Float> = SpringSpecs.Bouncy,
): State<Float> = animateFloatAsState(
    targetValue = target,
    animationSpec = spec,
    label = "am-spring-float",
)

/** Boolean 版：true=1f，false=0f。给 alpha/高度弹簧用。 */
@Composable
fun amBool(
    target: Boolean,
    spec: SpringSpec<Float> = SpringSpecs.Bouncy,
): State<Float> = amFloat(if (target) 1f else 0f, spec)

/**
 * 带弹簧惯性的淡入 alpha。
 */
@Composable
fun amFadeInAlpha(visible: Boolean): State<Float> =
    amBool(visible, spec = SpringSpecs.Bouncy)

/** 淡出：[SpringSpecs.Smooth]（无过冲，最快消失，不拖沓）。 */
@Composable
fun amFadeOutAlpha(visible: Boolean): State<Float> =
    amBool(visible, spec = SpringSpecs.Smooth)

/**
 * 弹簧惯性滑入：从 [fromY] 出发，[SpringSpecs.Bouncy] 驱动，
 * 冲过 0 再弹回。返回的 Float 直接给 graphicsLayer.translationY。
 */
@Composable
fun amSlideIn(active: Boolean, fromY: Float = 48f): State<Float> =
    amFloat(if (active) 0f else fromY, spec = SpringSpecs.Bouncy)

/** 滑出：到 [toY]，[SpringSpecs.Smooth]（无过冲）。 */
@Composable
fun amSlideOut(active: Boolean, toY: Float = -48f): State<Float> =
    amFloat(if (active) toY else 0f, spec = SpringSpecs.Smooth)

/**
 * 列表项交错入场延迟：第 [index] 项 = index × [STAGGER_PER_ITEM_MS]。
 * 配合 [amFloat] 与 LaunchedEffect + delay 使用。
 */
fun staggerIndex(index: Int): Long = index.toLong() * STAGGER_PER_ITEM_MS

/**
 * 按压回弹微交互（卡片/按钮）：按下 scale [pressedScale]（默认 0.94），
 * 松开后按 [SpringSpecs.Bouncy] 弹回 1.0。
 *
 * 实际可用的 Modifier 版在 [com.agentm.core.ui.amPressBounce]
 * （MicroInteractions.kt，手势驱动 + graphicsLayer 已接好）。
 * 本函数仅用于"外部自己管理 pressed 状态"的调用侧：
 * ```
 * val scale by amPressScaleState(pressed = pressed, pressedScale = 0.94f)
 * Card(Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
 * ```
 */
@Composable
fun amPressScaleState(
    pressed: Boolean,
    pressedScale: Float = 0.94f,
    spec: SpringSpec<Float> = SpringSpecs.Bouncy,
): State<Float> = amFloat(if (pressed) pressedScale else 1f, spec)

/** 画布 Fling 吸附参数（供 orchestrator 画布引用） */
object CanvasSnap {
    /** 网格边长 dp */
    const val GRID_DP = 8
    /** Fling 减速摩擦（越接近 1 减速越慢） */
    const val FLING_FRICTION = 0.98f
    /** 吸附容差 dp：小于它直接落格，不用弹 */
    const val SNAP_TOLERANCE_DP = 4
}
