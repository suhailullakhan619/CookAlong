package com.cookalong.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cookalong.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * CookAlong palette. Contrast ratios (text on background) were checked with the WCAG formula:
 *  dark:  text #F5EDE3 on #14110F ≈ 16:1, muted #CDBFAF ≈ 10.4:1, amber #F2B04F ≈ 9.9:1
 *  light: text #1E1712 on #FBF6EF ≈ 16:1, muted #4F4339 ≈ 8.9:1, brown #7A4100 ≈ 7.6:1
 *  Every text/background pair used in the UI is ≥ 7:1 except light-theme alert text (6.1:1).
 */
@Immutable
data class CookColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val focusRing: Color,
    val chip: Color,
    val onChip: Color,
    val alert: Color,
    val onAlert: Color,
    val scrim: Color,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val isDark: Boolean,
)

val DarkCook = CookColors(
    background = Color(0xFF14110F),
    surface = Color(0xFF221C18),
    surfaceRaised = Color(0xFF2E2620),
    text = Color(0xFFF5EDE3),
    textMuted = Color(0xFFCDBFAF),
    accent = Color(0xFFF2B04F),
    onAccent = Color(0xFF1E1408),
    focusRing = Color(0xFFFFE2A8),
    chip = Color(0xFF3A3029),
    onChip = Color(0xFFF5EDE3),
    alert = Color(0xFFFF9B71),
    onAlert = Color(0xFF240B02),
    scrim = Color(0xF0100D0B),
    shimmerBase = Color(0xFF2A231E),
    shimmerHighlight = Color(0xFF3B322B),
    isDark = true,
)

val LightCook = CookColors(
    background = Color(0xFFFBF6EF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF1E8DC),
    text = Color(0xFF1E1712),
    textMuted = Color(0xFF4F4339),
    accent = Color(0xFF7A4100),
    onAccent = Color(0xFFFFFFFF),
    focusRing = Color(0xFF1E1712),
    chip = Color(0xFFEDE2D3),
    onChip = Color(0xFF1E1712),
    alert = Color(0xFFB3360B),
    onAlert = Color(0xFFFFFFFF),
    scrim = Color(0xF2FBF6EF),
    shimmerBase = Color(0xFFEDE4D8),
    shimmerHighlight = Color(0xFFF8F2EA),
    isDark = false,
)

val LocalCookColors = staticCompositionLocalOf { DarkCook }

/**
 * 10-foot type scale. Fire TV renders 1080p as 960×540 dp (density 2.0), so 1 sp = 2 physical pixels:
 * step text 44 sp ≈ 88 px, headings 36 sp ≈ 72 px, body 22 sp ≈ 44 px. Medium/bold weights only.
 */
/** DM Serif Display gives headings a food-magazine feel; Poppins keeps steps and labels crisp from the sofa. Both OFL. */
val Serif = FontFamily(Font(R.font.dm_serif_display, FontWeight.Normal))
val Sans = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

object CookType {
    val step = TextStyle(fontFamily = Sans, fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold)
    val hero = TextStyle(fontFamily = Serif, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Normal)
    val display = TextStyle(fontFamily = Serif, fontSize = 42.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal)
    val headline = TextStyle(fontFamily = Serif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
    val title = TextStyle(fontFamily = Sans, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = Sans, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
}

/** 5% overscan-safe margins of the 960×540 dp TV canvas. */
object Safe {
    val horizontal = 48.dp
    val vertical = 27.dp
}

@Composable
fun CookAlongTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkCook else LightCook
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.textMuted,
            border = c.focusRing,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.textMuted,
            border = c.focusRing,
        )
    }
    val typography = Typography(
        displaySmall = CookType.display,
        headlineMedium = CookType.headline,
        titleLarge = CookType.title,
        bodyLarge = CookType.body,
        bodyMedium = CookType.body,
        labelLarge = CookType.label,
        labelMedium = CookType.label,
        bodySmall = CookType.body,
        titleMedium = CookType.title,
        headlineSmall = CookType.headline,
    )
    CompositionLocalProvider(LocalCookColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Screen backdrop: the flat background with a soft warm glow from the top-left corner, so screens feel lit, not flat. */
@Composable
fun Modifier.cookBackdrop(): Modifier {
    val c = LocalCookColors.current
    val glow = if (c.isDark) Color(0x33F2B04F) else Color(0x33E8A23C)
    return this
        .background(c.background)
        .background(
            Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = Offset(0f, 0f),
                radius = 1400f,
            ),
        )
}
