package com.agentm.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * AgentM 弹簧参数三组规范。
 *
 * Compose 的 spring 物理模型：刚度(stiffness)决定"弹力大小"，
 * 阻尼比(dampingRatio)决定回弹过冲量。0.6-0.9 区间是"像弹簧"的甜区：
 *   - 1.0  = 临界阻尼，无过冲（不算弹簧，是"丝滑"）
 *   - 0.6  = 过冲大，果冻感强
 *   - 0.9  = 过冲小，稳而不闷
 *
 * 三组常量统一定义在此，任何组件只允许引用这里，不允许自造参数。
 * 注：Float 的默认 visibilityThreshold 已是 0.01f，无需显式传。
 */
object SpringSpecs {

    /** 弹性档：有回弹、有惯性。转场、列表交错入场、按压回弹、画布节点落格。
     *  stiffness Low(100) + dampingRatio 0.75 → 过一次目标约 8%，再弹回。 */
    val Bouncy = spring<Float>(
        stiffness = Spring.StiffnessLow,          // 100
        dampingRatio = 0.75f,
    )

    /** 丝滑档：高刚度 + 轻阻尼。常规状态切换（卡片展开、工具气泡消失、开关）。
     *  阻尼 0.9（仍在 0.6-0.9 甜区）——几乎无过冲但保留一丝回弹，观感"顺滑带惯性"。
     *  stiffness High(300)：三者中最快到位。 */
    val Smooth = spring<Float>(
        stiffness = Spring.StiffnessHigh,         // 300
        dampingRatio = 0.9f,                       // 甜区内最接近无过冲
    )

    /** 柔和档：慢速弱弹。氛围类变化（背景渐变色、阴影层、画布网格淡入）。
     *  stiffness MediumLow(80) + dampingRatio 0.9 → 慢慢滑到位，轻微回弹。 */
    val Gentle = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,    // 80
        dampingRatio = 0.9f,
    )

    /** 画布吸附专用档：Medium(150) + 0.85。手指 Fling 甩出去减速，
     *  节点吸附到网格时落格有"咔哒"感但不生硬。 */
    val Snap = spring<Float>(
        stiffness = Spring.StiffnessMedium,       // 150
        dampingRatio = 0.85f,
    )
}
