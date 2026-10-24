package com.agentm.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.Build

/**
 * AgentM Compose 主题。
 *
 * 双轨制：
 * - [useDynamicColors] 开启（默认，Android 12+）：从壁纸取 Material3 动态色板；
 * - 关闭或 Android 11 及以下：回退 [AM_STATIC_LIGHT] / [AM_STATIC_DARK] 静态 token，
 *   与 app/src/main/res/values(-night)/colors.xml 一一对应。
 *
 * 动态取色只替换基础 8 色（primary/secondary/tertiary 各一组），
 * 品牌专用 token（气泡色、画布色）永远静态，保证深浅主题一致性。
 */

// ---------- 静态 token（与 res 资源同步） ----------

val AM_STATIC_LIGHT: ColorScheme = lightColorScheme(
    primary = Color(0xFF4553E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E4FF),
    onPrimaryContainer = Color(0xFF191A56),
    secondary = Color(0xFF5E5E80),
    secondaryContainer = Color(0xFFEDEAFF),
    tertiary = Color(0xFF12B5A0),
    tertiaryContainer = Color(0xFFA9F2E8),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFFCF9FF),
    background = Color(0xFFFCF9FF),
    surfaceVariant = Color(0xFFF2EEF7),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF45454F),
)

val AM_STATIC_DARK: ColorScheme = darkColorScheme(
    primary = Color(0xFFB3B4FF),
    onPrimary = Color(0xFF24245F),
    primaryContainer = Color(0xFF353579),
    onPrimaryContainer = Color(0xFFE2E4FF),
    secondary = Color(0xFFC6C5E7),
    secondaryContainer = Color(0xFF3E3E5F),
    tertiary = Color(0xFF4FD9C6),
    tertiaryContainer = Color(0xFF065F52),
    error = Color(0xFFE7756C),
    surface = Color(0xFF1D1B20),
    background = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFF252329),
    onSurface = Color(0xFFE7E3EB),
    onSurfaceVariant = Color(0xFFC8C5D1),
)

// ---------- 品牌 token（动态取色不影响，与 dimens.xml 同步） ----------

object AmTokens {
    // 消息气泡
    val bubbleUserLight: Color = Color(0xFF4553E8)
    val bubbleUserDark: Color = Color(0xFF6E72F5)
    val bubbleAssistantLight: Color = Color(0xFFF2EEF7)
    val bubbleAssistantDark: Color = Color(0xFF302E35)
    val bubbleTool: Color = Color(0xFF12B5A0) // 深浅同色，深色底下对比足够

    // 画布
    val canvasBgLight: Color = Color(0xFF141218)
    val canvasBgDark: Color = Color(0xFF0E0C12)
    val canvasGrid: Color = Color(0x22FFFFFF)
    val nodeAgent: Color = Color(0xFF7C6FF0)
    val nodeTool: Color = Color(0xFF12B5A0)
    val nodeIo: Color = Color(0xFF5E5E80)
    val edgeIdle: Color = Color(0xFF55556A)
    val edgeActive: Color = Color(0xFF12B5A0)

    // 圆角 dp（对应 am_r_*）
    val rChipDp: Dp = 8.dp
    val rBubbleDp: Dp = 12.dp
    val rCardDp: Dp = 16.dp
    val rInputDp: Dp = 18.dp
    val rSheetDp: Dp = 24.dp
    // 间距 dp（对应 am_m_*）
    val mXsDp: Dp = 4.dp
    val mSmDp: Dp = 8.dp
    val mMdDp: Dp = 12.dp
    val mLgDp: Dp = 16.dp
    val mXlDp: Dp = 24.dp
    // 阴影层 dp（对应 am_el_*）
    val el0Dp: Dp = 0.dp
    val el1Dp: Dp = 2.dp
    val el2Dp: Dp = 6.dp
    // 组件尺寸
    val avatarDp: Dp = 36.dp
    val avatarMiniDp: Dp = 24.dp
    val nodeWDp: Dp = 172.dp
    val nodeHDp: Dp = 56.dp
}

/**
 * 应用级主题入口。
 *
 * @param dark 是否深色（用 isSystemInDarkTheme 驱动）
 * @param useDynamicColors Android 12+ 壁纸取色开关，App 设置页可持久化覆盖
 */
@Composable
fun AgentMTheme(
    dark: Boolean,
    useDynamicColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> AM_STATIC_DARK
        else -> AM_STATIC_LIGHT
    }

    Surface(modifier = Modifier, color = colorScheme.background, contentColor = colorScheme.onSurface) {
        content()
    }
}

/** 深浅各一套的品牌 token。画布/气泡/节点色不随动态色板变化。 */
@Composable
fun amTokens(dark: Boolean) = AmTokenSet(
    bubbleUser = if (dark) AmTokens.bubbleUserDark else AmTokens.bubbleUserLight,
    bubbleAssistant = if (dark) AmTokens.bubbleAssistantDark else AmTokens.bubbleAssistantLight,
    canvasBg = if (dark) AmTokens.canvasBgDark else AmTokens.canvasBgLight,
)

class AmTokenSet(
    val bubbleUser: Color,
    val bubbleAssistant: Color,
    val canvasBg: Color,
) {
    val bubbleTool: Color get() = AmTokens.bubbleTool
    val canvasGrid: Color get() = AmTokens.canvasGrid
    val nodeAgent: Color get() = AmTokens.nodeAgent
    val nodeTool: Color get() = AmTokens.nodeTool
    val nodeIo: Color get() = AmTokens.nodeIo
    val edgeIdle: Color get() = AmTokens.edgeIdle
    val edgeActive: Color get() = AmTokens.edgeActive
}
