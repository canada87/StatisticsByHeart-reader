package app.sbh.reader.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.R
import app.sbh.reader.system.PowerGuide
import app.sbh.reader.ui.components.BannerKind
import app.sbh.reader.ui.components.ButtonStyle
import app.sbh.reader.ui.components.HudButton
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.HudSwitchRow
import app.sbh.reader.ui.components.PushedHeader
import app.sbh.reader.ui.components.SectionLabel
import app.sbh.reader.ui.components.StatusBanner
import app.sbh.reader.ui.theme.SbhTheme

/** Guided screen for HyperOS/MIUI background restrictions (PROJECT_GUIDE §3.1). */
@Composable
fun PowerGuideScreen(vm: GuideViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val done by vm.done.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = SbhTheme.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val batteryOk = PowerGuide.isIgnoringBatteryOptimizations(context)

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PushedHeader(stringResource(R.string.guide_title), onBack) }
        item {
            Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
        }
        if (!PowerGuide.isXiaomi()) {
            item {
                StatusBanner(BannerKind.INFO, stringResource(R.string.guide_not_xiaomi_title), stringResource(R.string.guide_not_xiaomi_body))
            }
        }
        items(PowerGuide.steps, key = { it }) { step ->
            val (title, body, action, open) = when (step) {
                PowerGuide.STEP_BATTERY -> GuideStep(
                    stringResource(R.string.guide_battery_title), stringResource(R.string.guide_battery_body),
                    stringResource(R.string.guide_battery_action),
                ) { PowerGuide.requestIgnoreBatteryOptimizations(context) }
                PowerGuide.STEP_AUTOSTART -> GuideStep(
                    stringResource(R.string.guide_autostart_title), stringResource(R.string.guide_autostart_body),
                    stringResource(R.string.guide_autostart_action),
                ) { PowerGuide.openAutostart(context) }
                PowerGuide.STEP_NO_RESTRICTION -> GuideStep(
                    stringResource(R.string.guide_norestriction_title), stringResource(R.string.guide_norestriction_body),
                    stringResource(R.string.guide_norestriction_action),
                ) { PowerGuide.openAppBatterySaver(context) }
                else -> GuideStep(
                    stringResource(R.string.guide_lock_title), stringResource(R.string.guide_lock_body), null, null,
                )
            }
            val checked = if (step == PowerGuide.STEP_BATTERY) batteryOk else step in done
            HudPanel(accent = if (checked) c.ok else c.warn) {
                SectionLabel(title, color = if (checked) c.ok else c.warn)
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
                if (action != null && open != null) {
                    HudButton(action, { open() }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY, icon = R.drawable.ic_gear_six)
                }
                if (step == PowerGuide.STEP_BATTERY) {
                    Text(
                        stringResource(if (batteryOk) R.string.guide_battery_ok else R.string.guide_battery_todo),
                        style = MaterialTheme.typography.labelMedium, color = c.fg,
                    )
                } else {
                    HudSwitchRow(
                        label = stringResource(R.string.guide_mark_done),
                        checked = checked,
                        onCheckedChange = { vm.setDone(step, it) },
                    )
                }
            }
        }
        item {
            HudButton(stringResource(R.string.guide_app_details), { PowerGuide.openAppDetails(context) }, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
        }
    }
}

private data class GuideStep(val title: String, val body: String, val action: String?, val open: (() -> Unit)?)
