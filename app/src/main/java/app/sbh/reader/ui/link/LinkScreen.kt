package app.sbh.reader.ui.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.R
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.sync.SyncPhase
import app.sbh.reader.sync.SyncProgress
import app.sbh.reader.ui.MainViewModel
import app.sbh.reader.ui.agoText
import app.sbh.reader.ui.components.GaugeState
import app.sbh.reader.ui.components.HudButton
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.ScreenTitle
import app.sbh.reader.ui.components.SectionLabel
import app.sbh.reader.ui.components.StatusBanner
import app.sbh.reader.ui.components.StatusGauge
import app.sbh.reader.ui.formatDateTime
import app.sbh.reader.ui.rememberNow
import app.sbh.reader.ui.theme.SbhTheme

@Composable
fun LinkScreen(
    vm: LinkViewModel,
    main: MainViewModel,
    padding: PaddingValues,
    onOpenConfig: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val c = SbhTheme.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val lastText = state.lastSuccess?.let { agoText(it, now) } ?: stringResource(R.string.link_never)
    val label = when (state.gauge) {
        GaugeState.IDLE -> stringResource(R.string.gauge_idle)
        GaugeState.SYNCING -> stringResource(R.string.gauge_syncing)
        GaugeState.ERROR -> stringResource(R.string.gauge_error)
        GaugeState.OFFLINE -> stringResource(R.string.gauge_offline)
        GaugeState.SETUP -> stringResource(R.string.gauge_setup)
    }
    val sublabel = when (state.gauge) {
        GaugeState.SYNCING -> progressText(state.running)
        GaugeState.IDLE -> lastText
        else -> null
    }
    val description = stringResource(R.string.gauge_description, label, sublabel ?: lastText, state.outbox)

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(R.string.nav_link)) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusGauge(state.gauge, label, sublabel, description)
            }
        }
        item {
            HudPanel {
                SectionLabel(stringResource(R.string.link_status_title))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(stringResource(R.string.link_last_success), style = MaterialTheme.typography.labelSmall, color = c.fgMuted)
                        Text(lastText, style = MaterialTheme.typography.titleMedium, color = c.fg)
                        state.lastSuccess?.let {
                            Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.link_queue), style = MaterialTheme.typography.labelSmall, color = c.fgMuted)
                        Text(state.outbox.toString(), style = MaterialTheme.typography.titleMedium, color = c.fg)
                        Text(
                            pluralStringResource(R.plurals.link_interval, state.intervalHours, state.intervalHours),
                            style = MaterialTheme.typography.bodySmall, color = c.fgMuted,
                        )
                    }
                }
            }
        }
        item {
            HudButton(
                text = stringResource(R.string.action_sync_now),
                onClick = main::syncNow,
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_arrows_clockwise,
                enabled = state.canSync,
            )
        }
        items(state.issues, key = { it.kind }) { issue ->
            IssueBanner(
                issue = issue,
                missingTypes = state.missingTypes,
                hours = state.intervalHours,
                main = main,
                onOpenConfig = onOpenConfig,
                onOpenGuide = onOpenGuide,
            )
        }
    }
}

@Composable
private fun IssueBanner(
    issue: Issue,
    missingTypes: Int,
    hours: Int,
    main: MainViewModel,
    onOpenConfig: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val (title, body, action, onAction) = when (issue.kind) {
        IssueKind.HC_MISSING -> IssueTexts(
            stringResource(R.string.issue_hc_missing_title), stringResource(R.string.issue_hc_missing_body),
            stringResource(R.string.issue_hc_missing_action), main::openHealthConnectStore,
        )
        IssueKind.HC_UPDATE -> IssueTexts(
            stringResource(R.string.issue_hc_update_title), stringResource(R.string.issue_hc_update_body),
            stringResource(R.string.issue_hc_update_action), main::openHealthConnectStore,
        )
        IssueKind.PERMISSIONS -> IssueTexts(
            stringResource(R.string.issue_permissions_title), stringResource(R.string.issue_permissions_body, missingTypes),
            stringResource(R.string.action_grant_permissions), { main.requestPermissions() },
        )
        IssueKind.HISTORY -> IssueTexts(
            stringResource(R.string.issue_history_title), stringResource(R.string.issue_history_body),
            stringResource(R.string.action_grant_permissions), { main.requestPermissions() },
        )
        IssueKind.BACKGROUND -> IssueTexts(
            stringResource(R.string.issue_background_title), stringResource(R.string.issue_background_body),
            stringResource(R.string.action_grant_permissions), { main.requestPermissions() },
        )
        IssueKind.NOT_CONFIGURED -> IssueTexts(
            stringResource(R.string.issue_not_configured_title), stringResource(R.string.issue_not_configured_body),
            stringResource(R.string.action_open_config), onOpenConfig,
        )
        IssueKind.AUTH -> IssueTexts(
            stringResource(R.string.issue_auth_title), stringResource(R.string.issue_auth_body),
            stringResource(R.string.action_open_config), onOpenConfig,
        )
        IssueKind.CONFIG_INVALID -> IssueTexts(
            stringResource(R.string.issue_config_invalid_title), stringResource(R.string.issue_config_invalid_body),
            stringResource(R.string.action_open_config), onOpenConfig,
        )
        IssueKind.UNREACHABLE -> IssueTexts(
            stringResource(R.string.issue_unreachable_title), stringResource(R.string.issue_unreachable_body),
            stringResource(R.string.action_retry), main::syncNow,
        )
        IssueKind.OFFLINE -> IssueTexts(
            stringResource(R.string.issue_offline_title), stringResource(R.string.issue_offline_body), null, null,
        )
        IssueKind.BATTERY -> IssueTexts(
            stringResource(R.string.issue_battery_title), stringResource(R.string.issue_battery_body),
            stringResource(R.string.action_open_guide), onOpenGuide,
        )
        IssueKind.STALE -> IssueTexts(
            stringResource(R.string.issue_stale_title), stringResource(R.string.issue_stale_body, hours * 3),
            stringResource(R.string.action_open_guide), onOpenGuide,
        )
    }
    StatusBanner(issue.severity, title, body, actionLabel = action, onAction = onAction)
}

private data class IssueTexts(val title: String, val body: String, val action: String?, val onAction: (() -> Unit)?)

@Composable
private fun progressText(p: SyncProgress?): String? = when (p?.phase) {
    SyncPhase.BACKFILL -> p.typeId?.let { HealthTypes.byId[it] }?.let { stringResource(R.string.progress_backfill, stringResource(it.labelRes), p.recordsDelivered) }
    SyncPhase.CHANGES -> stringResource(R.string.progress_changes)
    SyncPhase.AGGREGATES -> stringResource(R.string.progress_aggregates)
    SyncPhase.UPLOADING -> stringResource(R.string.progress_uploading, p.recordsDelivered)
    SyncPhase.PREPARING -> stringResource(R.string.progress_preparing)
    null -> stringResource(R.string.progress_preparing)
}
