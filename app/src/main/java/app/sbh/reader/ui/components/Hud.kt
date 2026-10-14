package app.sbh.reader.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import app.sbh.reader.R
import app.sbh.reader.ui.theme.LocalReduceMotion
import app.sbh.reader.ui.theme.SbhTheme

/** Min touch target (§8.6). */
val MinTouch = 48.dp

@Composable
fun SbhIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint)
}

/** Screen title: heading semantics, uppercase mono (§8.3). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** Header of a pushed (non top-level) screen: back button >= 48dp + title. */
@Composable
fun PushedHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouch)) {
            SbhIcon(R.drawable.ic_caret_left, stringResource(R.string.action_back))
        }
        ScreenTitle(title)
    }
}

/** Small technical caption, e.g. `HR · BPM` (§8.3). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = SbhTheme.colors.accentGlow) {
    Text(
        text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/**
 * HUD panel: 1dp border, corner brackets, semi-transparent surface, no blur (§8.3).
 */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = SbhTheme.colors.accentGlow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = SbhTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface.copy(alpha = 0.92f))
            .border(1.dp, c.border)
            .drawBehind {
                val len = 14.dp.toPx()
                val w = 1.5.dp.toPx()
                val s = Stroke(width = w, cap = StrokeCap.Square)
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(accent, Offset(x, y), Offset(x + dx * len, y), strokeWidth = s.width, cap = s.cap)
                    drawLine(accent, Offset(x, y), Offset(x, y + dy * len), strokeWidth = s.width, cap = s.cap)
                }
                corner(0f, 0f, 1f, 1f)
                corner(size.width, 0f, -1f, 1f)
                corner(0f, size.height, 1f, -1f)
                corner(size.width, size.height, -1f, -1f)
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val c = SbhTheme.colors
    val shape = RoundedCornerShape(4.dp)
    val content: @Composable () -> Unit = {
        if (icon != null) {
            SbhIcon(icon, null, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val min = Modifier.heightIn(min = MinTouch)
    when (style) {
        ButtonStyle.PRIMARY -> Button(
            onClick = onClick,
            modifier = modifier.then(min),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.accent,
                contentColor = c.onAccent,
                disabledContainerColor = c.surfaceMuted,
                disabledContentColor = c.fgMuted,
            ),
            content = { content() },
        )
        ButtonStyle.SECONDARY -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(min),
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, if (enabled) c.borderControl else c.border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.fg, disabledContentColor = c.fgMuted),
            content = { content() },
        )
        ButtonStyle.DANGER -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(min),
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, c.danger),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.dangerText, disabledContentColor = c.fgMuted),
            content = { content() },
        )
    }
}

/** Whole-row switch: the row is the single toggle target (>= 56dp), announced as a Switch by TalkBack. */
@Composable
fun HudSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    iconTint: Color = SbhTheme.colors.accentGlow,
    enabled: Boolean = true,
) {
    val c = SbhTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) SbhIcon(icon, null, tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.fg)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accentGlow,
                checkedBorderColor = c.accentGlow,
                uncheckedThumbColor = c.fgMuted,
                uncheckedTrackColor = c.surfaceMuted,
                uncheckedBorderColor = c.borderControl,
                disabledCheckedTrackColor = c.border,
                disabledUncheckedTrackColor = c.surfaceMuted,
            ),
        )
    }
}

enum class BannerKind { ERROR, WARN, INFO, OK }

/**
 * Cause + how to fix + recovery button (§8.6). Meaning is carried by icon and text, never by colour alone.
 */
@Composable
fun StatusBanner(
    kind: BannerKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = SbhTheme.colors
    val (tint, icon) = when (kind) {
        BannerKind.ERROR -> c.dangerText to R.drawable.ic_x_circle
        BannerKind.WARN -> c.warn to R.drawable.ic_warning
        BannerKind.INFO -> c.accentGlow to R.drawable.ic_info
        BannerKind.OK -> c.ok to R.drawable.ic_check_circle
    }
    HudPanel(modifier, accent = tint) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            SbhIcon(icon, null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.fg)
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
            }
        }
        if (actionLabel != null && onAction != null) {
            HudButton(actionLabel, onAction, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
        }
    }
}

@Composable
fun HudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    val c = SbhTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouch),
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        keyboardOptions = keyboardOptions,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.fg,
            unfocusedTextColor = c.fg,
            focusedBorderColor = c.accentGlow,
            unfocusedBorderColor = c.borderControl,
            errorBorderColor = c.danger,
            focusedLabelColor = c.accentGlow,
            unfocusedLabelColor = c.fgMuted,
            errorLabelColor = c.dangerText,
            cursorColor = c.accentGlow,
            focusedSupportingTextColor = c.fgMuted,
            unfocusedSupportingTextColor = c.fgMuted,
            errorSupportingTextColor = c.dangerText,
        ),
    )
}

enum class GaugeState { IDLE, SYNCING, ERROR, OFFLINE, SETUP }

/**
 * Radial "link status" gauge (§8.3/§8.6). Each state differs in ring style AND icon AND label, not only colour.
 * The scanning arc only moves when animations are allowed.
 */
@Composable
fun StatusGauge(
    state: GaugeState,
    label: String,
    sublabel: String?,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 224.dp,
) {
    val c = SbhTheme.colors
    val reduce = LocalReduceMotion.current
    val color = when (state) {
        GaugeState.IDLE -> c.ok
        GaugeState.SYNCING -> c.accentGlow
        GaugeState.ERROR -> c.danger
        GaugeState.OFFLINE -> c.fgMuted
        GaugeState.SETUP -> c.warn
    }
    val icon = when (state) {
        GaugeState.IDLE -> R.drawable.ic_check_circle
        GaugeState.SYNCING -> R.drawable.ic_arrows_clockwise
        GaugeState.ERROR -> R.drawable.ic_x_circle
        GaugeState.OFFLINE -> R.drawable.ic_wifi_slash
        GaugeState.SETUP -> R.drawable.ic_warning
    }
    val rotation by if (state == GaugeState.SYNCING && !reduce) {
        rememberInfiniteTransition(label = "scan").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing), RepeatMode.Restart),
            label = "scanRotation",
        )
    } else {
        androidx.compose.runtime.rememberUpdatedState(0f)
    }
    val track = c.border
    Box(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            // fine tick ring (decorative, low contrast)
            val tickR = this.size.width / 2 - 2.dp.toPx()
            for (i in 0 until 60) {
                rotate(i * 6f) {
                    val len = if (i % 5 == 0) 6.dp.toPx() else 3.dp.toPx()
                    drawLine(track, Offset(center.x, center.y - tickR), Offset(center.x, center.y - tickR + len), 1.dp.toPx())
                }
            }
            val inner = Size(arcSize.width - 20.dp.toPx(), arcSize.height - 20.dp.toPx())
            val innerTl = Offset(inset + 10.dp.toPx(), inset + 10.dp.toPx())
            drawArc(track, 0f, 360f, false, innerTl, inner, style = Stroke(stroke))
            when (state) {
                GaugeState.IDLE -> drawArc(color, -90f, 360f, false, innerTl, inner, style = Stroke(stroke, cap = StrokeCap.Round))
                GaugeState.SYNCING -> drawArc(color, -90f + rotation, 110f, false, innerTl, inner, style = Stroke(stroke, cap = StrokeCap.Round))
                GaugeState.ERROR -> for (i in 0 until 3) {
                    drawArc(color, -90f + i * 120f + 8f, 90f, false, innerTl, inner, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                GaugeState.OFFLINE -> drawArc(
                    color, -90f, 360f, false, innerTl, inner,
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 10.dp.toPx()))),
                )
                GaugeState.SETUP -> drawArc(color, -90f, 270f, false, innerTl, inner, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SbhIcon(icon, null, tint = color, size = 32.dp)
            Text(label.uppercase(), style = MaterialTheme.typography.titleSmall, color = c.fg)
            if (sublabel != null) Text(sublabel, style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
        }
    }
}

/** Colour-independent small status tag: icon + text. */
@Composable
fun StatusTag(@DrawableRes icon: Int, text: String, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SbhIcon(icon, null, tint = tint, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelSmall, color = SbhTheme.colors.fg)
    }
}
