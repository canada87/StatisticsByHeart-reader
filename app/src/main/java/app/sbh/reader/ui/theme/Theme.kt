package app.sbh.reader.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sbh.reader.R

/** Colours beyond Material's scheme, from the §8.1 tokens. */
@Immutable
class SbhColors(
    val bg: Color = Color(Tokens.BG),
    val surface: Color = Color(Tokens.SURFACE),
    val surfaceMuted: Color = Color(Tokens.SURFACE_MUTED),
    val border: Color = Color(Tokens.BORDER),
    val borderControl: Color = Color(Tokens.BORDER_CONTROL),
    val fg: Color = Color(Tokens.FG),
    val fgMuted: Color = Color(Tokens.FG_MUTED),
    val accent: Color = Color(Tokens.ACCENT),
    val accentGlow: Color = Color(Tokens.ACCENT_GLOW),
    val onAccent: Color = Color(Tokens.ON_ACCENT),
    val danger: Color = Color(Tokens.DANGER),
    val dangerText: Color = Color(Tokens.DANGER_TEXT),
    val ok: Color = Color(Tokens.M_VITALS),
    val warn: Color = Color(Tokens.M_ENERGY),
    val heart: Color = Color(Tokens.M_HEART),
    val sleep: Color = Color(Tokens.M_SLEEP),
    val sleepText: Color = Color(Tokens.M_SLEEP_TEXT),
    val activity: Color = Color(Tokens.M_ACTIVITY),
    val energy: Color = Color(Tokens.M_ENERGY),
    val vitals: Color = Color(Tokens.M_VITALS),
)

val LocalSbhColors = compositionLocalOf { SbhColors() }

/** True when animations must be skipped: the in-app "Riduci animazioni" toggle or the system animation scale 0. */
val LocalReduceMotion = compositionLocalOf { false }

object SbhTheme {
    val colors: SbhColors
        @Composable @ReadOnlyComposable get() = LocalSbhColors.current
}

/** Single set of motion tokens (§8.4). Exits are ~65% of the matching entrance. */
object Motion {
    const val MICRO_MS = 120
    const val STATE_MS = 250
    const val ENTER_MS = 380
    const val EXIT_MS = 250
}

private val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun firaWeight(w: Int) = Font(
    R.font.fira_code,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

private val FiraCode = FontFamily(firaWeight(400), firaWeight(500), firaWeight(600), firaWeight(700))

private fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
        fontFeatureSettings = "tnum",
    )

/** Scale 12/14/16/18/24/32/48 (§8.2). Display/label = Share Tech Mono, body/data = Fira Code. */
private val SbhTypography = Typography(
    displayLarge = style(ShareTechMono, 48, 56),
    displayMedium = style(ShareTechMono, 32, 40),
    displaySmall = style(ShareTechMono, 24, 32),
    headlineLarge = style(ShareTechMono, 32, 40),
    headlineMedium = style(ShareTechMono, 24, 32),
    headlineSmall = style(ShareTechMono, 18, 26),
    titleLarge = style(ShareTechMono, 24, 32),
    titleMedium = style(ShareTechMono, 18, 26, tracking = 0.4),
    titleSmall = style(ShareTechMono, 16, 24, tracking = 0.4),
    bodyLarge = style(FiraCode, 16, 24),
    bodyMedium = style(FiraCode, 14, 22),
    bodySmall = style(FiraCode, 12, 18),
    labelLarge = style(ShareTechMono, 16, 24, tracking = 0.8),
    labelMedium = style(ShareTechMono, 14, 20, tracking = 1.0),
    labelSmall = style(ShareTechMono, 12, 16, tracking = 1.2),
)

private val SbhShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun SbhTheme(reduceMotionSetting: Boolean = false, content: @Composable () -> Unit) {
    val c = remember { SbhColors() }
    val context = LocalContext.current
    val reduce = reduceMotionSetting || systemAnimationsOff(context)
    val scheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.surfaceMuted,
        onPrimaryContainer = c.fg,
        secondary = c.accentGlow,
        onSecondary = c.onAccent,
        secondaryContainer = c.surfaceMuted,
        onSecondaryContainer = c.fg,
        tertiary = c.sleep,
        onTertiary = c.onAccent,
        background = c.bg,
        onBackground = c.fg,
        surface = c.surface,
        onSurface = c.fg,
        surfaceVariant = c.surfaceMuted,
        onSurfaceVariant = c.fgMuted,
        surfaceTint = c.accent,
        surfaceBright = c.surfaceMuted,
        surfaceDim = c.bg,
        surfaceContainerLowest = c.bg,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceMuted,
        surfaceContainerHighest = c.surfaceMuted,
        error = c.danger,
        onError = Color(Tokens.ON_DANGER),
        errorContainer = c.surfaceMuted,
        onErrorContainer = c.dangerText,
        outline = c.borderControl,
        outlineVariant = c.border,
        scrim = Color.Black,
    )
    CompositionLocalProvider(LocalSbhColors provides c, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, typography = SbhTypography, shapes = SbhShapes, content = content)
    }
}

private fun systemAnimationsOff(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) {
    false
}
