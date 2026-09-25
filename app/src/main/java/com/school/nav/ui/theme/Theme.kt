package com.school.nav.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 与 Demo 保持一致的配色，避免设计稿和实现两套颜色。 */
object NavColors {
    val Brand = Color(0xFF2F6BFF)
    val BrandDark = Color(0xFF2059E0)
    val Green = Color(0xFF16B364)
    val PageBackground = Color(0xFFEEF1F6)
    val Card = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1D24)
    val TextSecondary = Color(0xFF8A90A0)
    val Divider = Color(0xFFE2E7F0)
    val FieldBackground = Color(0xFFFAFBFD)
    val ChipBackground = Color(0xFFEEF1F7)
    val ChipText = Color(0xFF4A5164)
    val HighlightBackground = Color(0xFFE8EFFF)

    /** 底部导航栏底色：参照设计稿的浅暖灰胶囊。 */
    val NavBarBackground = Color(0xFFF2F2F7)

    /** 选中项底下的胶囊底色（图里“首页”那一块，比导航栏底更亮更白）。 */
    val NavBarSelectedBackground = Color(0xFFFFFFFF)

    /** 选中项的文字与图标颜色（设计稿里接近纯黑）。 */
    val NavBarSelectedContent = Color(0xFF1A1D24)

    /** 未选中项的文字颜色。 */
    val NavBarContent = Color(0xFF6B7280)
}

private val LightColors = lightColorScheme(
    primary = NavColors.Brand,
    onPrimary = Color.White,
    primaryContainer = NavColors.HighlightBackground,
    onPrimaryContainer = NavColors.BrandDark,
    secondary = NavColors.Green,
    onSecondary = Color.White,
    background = NavColors.PageBackground,
    onBackground = NavColors.TextPrimary,
    surface = NavColors.Card,
    onSurface = NavColors.TextPrimary,
    surfaceVariant = NavColors.ChipBackground,
    onSurfaceVariant = NavColors.ChipText,
    outline = NavColors.Divider,
)

private val NavTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
)

private val NavShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FindClassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = NavTypography,
        shapes = NavShapes,
        content = content,
    )
}
