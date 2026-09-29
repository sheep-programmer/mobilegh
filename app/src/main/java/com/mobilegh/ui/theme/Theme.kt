package com.mobilegh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mobilegh.data.Session

/** GitHub Primer 设计令牌 */
@Immutable
data class GhColors(
    val dark: Boolean,
    val canvas: Color,
    val canvasSubtle: Color,
    val canvasInset: Color,
    val border: Color,
    val borderMuted: Color,
    val fg: Color,
    val fgMuted: Color,
    val accent: Color,
    val accentSubtle: Color,
    val success: Color,
    val successSubtle: Color,
    val danger: Color,
    val dangerSubtle: Color,
    val done: Color,
    val doneSubtle: Color,
    val attention: Color,
    val attentionSubtle: Color,
    val neutralMuted: Color,
    val btnPrimary: Color,
    val btnBg: Color,
    val header: Color,
    val diffAdd: Color,
    val diffAddWord: Color,
    val diffDel: Color,
    val diffDelWord: Color,
    val diffHunk: Color,
    val heat: List<Color>,
)

val LightGh = GhColors(
    dark = false,
    canvas = Color(0xFFFFFFFF),
    canvasSubtle = Color(0xFFF6F8FA),
    canvasInset = Color(0xFFF6F8FA),
    border = Color(0xFFD1D9E0),
    borderMuted = Color(0xFFD8DEE4),
    fg = Color(0xFF1F2328),
    fgMuted = Color(0xFF59636E),
    accent = Color(0xFF0969DA),
    accentSubtle = Color(0xFFDDF4FF),
    success = Color(0xFF1A7F37),
    successSubtle = Color(0xFFDAFBE1),
    danger = Color(0xFFD1242F),
    dangerSubtle = Color(0xFFFFEBE9),
    done = Color(0xFF8250DF),
    doneSubtle = Color(0xFFFBEFFF),
    attention = Color(0xFF9A6700),
    attentionSubtle = Color(0xFFFFF8C5),
    neutralMuted = Color(0x33AFB8C1),
    btnPrimary = Color(0xFF1F883D),
    btnBg = Color(0xFFF6F8FA),
    header = Color(0xFFF6F8FA),
    diffAdd = Color(0xFFDAFBE1),
    diffAddWord = Color(0xFFACEEBB),
    diffDel = Color(0xFFFFEBE9),
    diffDelWord = Color(0xFFFFCECB),
    diffHunk = Color(0xFFDDF4FF),
    heat = listOf(Color(0xFFEFF2F5), Color(0xFFACEEBB), Color(0xFF4AC26B), Color(0xFF2DA44E), Color(0xFF116329)),
)

val DarkGh = GhColors(
    dark = true,
    canvas = Color(0xFF0D1117),
    canvasSubtle = Color(0xFF151B23),
    canvasInset = Color(0xFF010409),
    border = Color(0xFF3D444D),
    borderMuted = Color(0xFF2F3742),
    fg = Color(0xFFF0F6FC),
    fgMuted = Color(0xFF9198A1),
    accent = Color(0xFF4493F8),
    accentSubtle = Color(0x1A388BFD),
    success = Color(0xFF3FB950),
    successSubtle = Color(0x262EA043),
    danger = Color(0xFFF85149),
    dangerSubtle = Color(0x1AF85149),
    done = Color(0xFFAB7DF8),
    doneSubtle = Color(0x26AB7DF8),
    attention = Color(0xFFD29922),
    attentionSubtle = Color(0x26BB8009),
    neutralMuted = Color(0x66656C76),
    btnPrimary = Color(0xFF238636),
    btnBg = Color(0xFF212830),
    header = Color(0xFF010409),
    diffAdd = Color(0x262EA043),
    diffAddWord = Color(0x662EA043),
    diffDel = Color(0x1AF85149),
    diffDelWord = Color(0x66F85149),
    diffHunk = Color(0x1A388BFD),
    heat = listOf(Color(0xFF151B23), Color(0xFF033A16), Color(0xFF196C2E), Color(0xFF2EA043), Color(0xFF56D364)),
)

val LocalGh = staticCompositionLocalOf { LightGh }

object Gh {
    val c: GhColors
        @Composable @ReadOnlyComposable get() = LocalGh.current
}

val Mono = FontFamily.Monospace

@Composable
fun isDarkMode(): Boolean = when (Session.themeMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun MobileGhTheme(content: @Composable () -> Unit) {
    val dark = isDarkMode()
    val g = if (dark) DarkGh else LightGh
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = g.accent,
        onPrimary = Color.White,
        primaryContainer = g.accentSubtle,
        onPrimaryContainer = g.accent,
        secondary = g.fgMuted,
        secondaryContainer = g.neutralMuted,
        onSecondaryContainer = g.fg,
        background = g.canvas,
        onBackground = g.fg,
        surface = g.canvas,
        onSurface = g.fg,
        surfaceVariant = g.canvasSubtle,
        onSurfaceVariant = g.fgMuted,
        surfaceContainer = g.canvasSubtle,
        surfaceContainerLow = g.canvas,
        surfaceContainerLowest = g.canvas,
        surfaceContainerHigh = g.canvasSubtle,
        surfaceContainerHighest = g.canvasSubtle,
        surfaceBright = g.canvasSubtle,
        outline = g.border,
        outlineVariant = g.borderMuted,
        error = g.danger,
    )
    val t = Typography()
    val typo = t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp),
    )
    CompositionLocalProvider(LocalGh provides g) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}

val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 19.sp)
