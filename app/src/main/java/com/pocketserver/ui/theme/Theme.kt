package com.pocketserver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val PhoneLightScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EEFF),
    onPrimaryContainer = Color(0xFF0B2A5B),
    secondary = Color(0xFF0F7B6C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCF5F0),
    onSecondaryContainer = Color(0xFF04372F),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF1B1F24),
    surface = Color.White,
    onSurface = Color(0xFF1B1F24),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF5B6472),
    error = Color(0xFFC0392B),
    onError = Color.White,
    outline = Color(0xFFD5DBE4),
    outlineVariant = Color(0xFFE3E8EF)
)

/**
 * TV 深色配色（FR-10.2 十尺 UI）。
 *
 * 为什么电视必须深色：客厅观看距离 2~3 米，浅色大面积在暗环境下会产生眩光，
 * 而且电视面板的峰值亮度远高于手机，"白底"会让人睁不开眼。
 * 同时深色背景让二维码（黑模块白底）与配对码数字的对比度最高，直接服务于 FR-10.4。
 */
private val TvDarkScheme = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF06203F),
    primaryContainer = Color(0xFF14305C),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF4FD1B5),
    onSecondary = Color(0xFF00382E),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF171C24),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF222A36),
    onSurfaceVariant = Color(0xFFA9B4C4),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF3A0906),
    outline = Color(0xFF3A4453),
    outlineVariant = Color(0xFF2A3340)
)

private val PhoneTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

private val PhoneShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

/**
 * 电视字号全部放大（正文 18sp 起，标题 30sp）。
 * 这不是审美选择，是硬指标：在 3 米外，15sp 的字在 55 吋电视上只有约 0.4° 视角，
 * 低于人眼舒适阅读下限（约 0.5°）。
 */
private val TvTypography = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun PocketServerTheme(
    isTv: Boolean,
    content: @Composable () -> Unit
) {
    // 电视固定深色；手机跟随系统主题（系统为深色时也用深色，避免夜间刺眼）
    val scheme = if (isTv) TvDarkScheme else {
        if (isSystemInDarkTheme()) TvDarkScheme else PhoneLightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = if (isTv) TvTypography else PhoneTypography,
        shapes = if (isTv) Shapes() else PhoneShapes,
        content = content
    )
}

/** 电视端遥控器焦点的强调色。抽出来是为了保证所有界面焦点态完全一致。 */
object TvTokens {
    val FocusBorder = Color(0xFF6FA8FF)
    val IdleBorder = Color(0xFF2A3340)
    const val FOCUS_BORDER_DP = 3
}
