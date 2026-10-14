package app.sbh.reader.ui.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.R
import app.sbh.reader.health.MetricGroup
import app.sbh.reader.ui.MainViewModel
import app.sbh.reader.ui.components.BannerKind
import app.sbh.reader.ui.components.ButtonStyle
import app.sbh.reader.ui.components.HudButton
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.HudSwitchRow
import app.sbh.reader.ui.components.ScreenTitle
import app.sbh.reader.ui.components.SectionLabel
import app.sbh.reader.ui.components.StatusBanner
import app.sbh.reader.ui.formatCount
import app.sbh.reader.ui.formatDate
import app.sbh.reader.ui.theme.SbhTheme

@Composable
fun groupColor(group: MetricGroup): Color {
    val c = SbhTheme.colors
    return when (group) {
        MetricGroup.HEART -> c.heart
        MetricGroup.SLEEP -> c.sleepText
        MetricGroup.ACTIVITY -> c.activity
        MetricGroup.ENERGY -> c.energy
        MetricGroup.VITALS -> c.vitals
    }
}

@Composable
fun DataScreen(
    vm: DataViewModel,
    main: MainViewModel,
    padding: PaddingValues,
    onOpenInventory: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshToday() }

    val missing = state.rows.filter { it.enabled && it.supported && !it.granted }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(R.string.nav_data)) }
        item { TodayPanel(state.today, state.hcAvailable) }
        if (missing.isNotEmpty() && state.hcAvailable) {
            item {
                StatusBanner(
                    BannerKind.WARN,
                    stringResource(R.string.issue_permissions_title),
                    stringResource(R.string.issue_permissions_body, missing.size),
                    actionLabel = stringResource(R.string.action_grant_permissions),
                    onAction = { main.requestPermissions() },
                )
            }
        }
        item {
            HudButton(
                stringResource(R.string.action_open_inventory),
                onOpenInventory,
                Modifier.fillMaxWidth(),
                style = ButtonStyle.SECONDARY,
                icon = R.drawable.ic_list_magnifying_glass,
            )
        }
        item { TypeSection(stringResource(R.string.data_tier1), state.rows.filter { it.spec.tier == 1 }, vm) }
        item { TypeSection(stringResource(R.string.data_tier2), state.rows.filter { it.spec.tier == 2 }, vm) }
    }
}

@Composable
private fun TodayPanel(today: TodaySummary, hcAvailable: Boolean) {
    val c = SbhTheme.colors
    val dash = stringResource(R.string.value_none)
    HudPanel {
        SectionLabel(stringResource(R.string.today_title))
        if (!hcAvailable) {
            Text(stringResource(R.string.today_unavailable), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
            return@HudPanel
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(stringResource(R.string.today_steps), today.steps?.let { formatCount(it) } ?: dash, c.activity, Modifier.weight(1f))
            Metric(
                stringResource(R.string.today_hr),
                today.hrAvg?.let { stringResource(R.string.today_hr_value, it, today.hrMin ?: 0, today.hrMax ?: 0) } ?: dash,
                c.heart,
                Modifier.weight(1.3f),
            )
            Metric(
                stringResource(R.string.today_sleep),
                today.sleepSeconds?.let { stringResource(R.string.today_sleep_value, it / 3600, (it % 3600) / 60) } ?: dash,
                c.sleepText,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = SbhTheme.colors.fgMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun TypeSection(title: String, rows: List<TypeRow>, vm: DataViewModel) {
    HudPanel {
        SectionLabel(title)
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = SbhTheme.colors.border)
            TypeRowItem(row, onToggle = { vm.setEnabled(row.spec.id, it) })
        }
    }
}

@Composable
private fun TypeRowItem(row: TypeRow, onToggle: (Boolean) -> Unit) {
    val permission = when {
        !row.supported -> stringResource(R.string.perm_unsupported)
        row.granted -> stringResource(R.string.perm_granted)
        else -> stringResource(R.string.perm_missing)
    }
    val sent = row.progress?.let { p ->
        if (p.sentCount > 0) {
            stringResource(R.string.type_sent, formatCount(p.sentCount)) +
                (p.lastRecordTime?.let { " · " + stringResource(R.string.type_last, formatDate(it)) } ?: "")
        } else {
            null
        }
    } ?: stringResource(R.string.type_nothing_sent)
    HudSwitchRow(
        label = stringResource(row.spec.labelRes),
        checked = row.enabled,
        onCheckedChange = onToggle,
        supporting = "$permission · $sent",
        icon = row.spec.iconRes,
        iconTint = groupColor(row.spec.group),
        enabled = row.supported,
    )
}
