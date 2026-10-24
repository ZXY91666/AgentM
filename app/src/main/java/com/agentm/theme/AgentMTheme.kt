package com.agentm.app.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * AgentM 主题 token（app 侧入口，core.ui.theme.kt 保留给 library 模块单独引用）。
 *
 * 与 res/values/colors.xml、values/dimens.xml 的 am_* token 对齐。
 * 深色支持：跟随系统（isSystemInDarkTheme 由调用方传入），动态取色 Android 12+。
 */

// ---------- 静态色板（与 res colors 一致） ----------

private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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

/** 形状 token：对应 am_r_* dimens。 */
val AmShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // chip
    small = RoundedCornerShape(12.dp),       // bubble
    medium = RoundedCornerShape(16.dp),      // card
    large = RoundedCornerShape(18.dp),       // input
    extraLarge = RoundedCornerShape(24.dp),  // sheet
)

/** 类型 token：紧凑聊天节奏，正文 15sp 起。 */
val AmTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontSize = 20.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 17.sp),
    titleSmall = Typography().titleSmall.copy(fontSize = 15.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 13.sp),
)

/** 间距 token（dp，对应 am_m_*）。 */
object AmSpacing {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24

    val xs: androidx.compose.ui.unit.Dp = 4.dp
    val sm: androidx.compose.ui.unit.Dp = 8.dp
    val md: androidx.compose.ui.unit.Dp = 12.dp
    val lg: androidx.compose.ui.unit.Dp = 16.dp
    val xl: androidx.compose.ui.unit.Dp = 24.dp
}

/**
 * 应用根主题。
 *
 * @param isDark 是否深色（调用方传 isSystemInDarkTheme()）
 * @param useDynamicColors Android 12+ 动态取色，关闭时回退静态 token
 */
@Composable
fun AgentMTheme(
    isDark: Boolean,
    useDynamicColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AmShapes,
        typography = AmTypography,
        content = content,
    )
}
