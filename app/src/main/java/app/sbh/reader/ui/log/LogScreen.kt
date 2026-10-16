package app.sbh.reader.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.R
import app.sbh.reader.sync.SyncLogEntry
import app.sbh.reader.sync.SyncMode
import app.sbh.reader.sync.SyncStatus
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.ScreenTitle
import app.sbh.reader.ui.components.StatusTag
import app.sbh.reader.ui.config.LogViewModel
import app.sbh.reader.ui.formatDateTime
import app.sbh.reader.ui.formatDuration
import app.sbh.reader.ui.theme.SbhTheme
import java.time.Duration

@Composable
fun LogScreen(vm: LogViewModel, padding: PaddingValues) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val c = SbhTheme.colors
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") { ScreenTitle(stringResource(R.string.nav_log)) }
        item(key = "intro") {
            Text(stringResource(R.string.log_intro), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
        }
        val list = logs
        when {
            list == null -> item(key = "loading") { CircularProgressIndicator(color = c.accentGlow) }
            list.isEmpty() -> item(key = "empty") {
                HudPanel {
                    Text(stringResource(R.string.log_empty_title), style = MaterialTheme.typography.titleSmall, color = c.fg)
                    Text(stringResource(R.string.log_empty_body), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
                }
            }
            else -> items(list, key = { it.id }) { LogRow(it) }
        }
    }
}

@Composable
private fun LogRow(e: SyncLogEntry) {
    val c = SbhTheme.colors
    val (icon, tint, text) = when (e.status) {
        SyncStatus.COMPLETED -> Triple(R.drawable.ic_check_circle, c.ok, stringResource(R.string.log_status_ok))
        SyncStatus.PARTIAL -> Triple(R.drawable.ic_warning, c.warn, stringResource(R.string.log_status_partial))
        SyncStatus.FAILED -> Triple(R.drawable.ic_x_circle, c.dangerText, stringResource(R.string.log_status_failed))
    }
    HudPanel(accent = tint) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusTag(icon, text, tint)
            Text(
                stringResource(
                    when (e.mode) {
                        SyncMode.BACKFILL -> R.string.log_mode_backfill
                        SyncMode.INCREMENTAL -> R.string.log_mode_incremental
                        SyncMode.ROUTES -> R.string.log_mode_routes
                    },
                ).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = c.fgMuted,
            )
        }
        Text(formatDateTime(e.startedAt), style = MaterialTheme.typography.bodyMedium, color = c.fg)
        Text(
            if (e.mode == SyncMode.ROUTES) {
                stringResource(
                    R.string.log_counts_routes,
                    e.counts["routes_queued"] ?: 0, e.counts["routes_sent"] ?: 0,
                    formatDuration(Duration.between(e.startedAt, e.finishedAt)),
                )
            } else {
                stringResource(
                    R.string.log_counts,
                    e.counts["records_sent"] ?: 0, e.counts["deletions_sent"] ?: 0, e.counts["aggregates_sent"] ?: 0,
                    formatDuration(Duration.between(e.startedAt, e.finishedAt)),
                )
            },
            style = MaterialTheme.typography.bodySmall, color = c.fgMuted,
        )
        val rejected = e.counts["records_rejected"] ?: 0
        if (rejected > 0) {
            Text(stringResource(R.string.log_rejected, rejected), style = MaterialTheme.typography.bodySmall, color = c.warn)
        }
        e.errors.forEach { code ->
            Column {
                Text(errorText(code), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
            }
        }
    }
}

/** Machine codes from the engine ("failure:auth", "type_read_failed:Steps=1") to readable Italian. */
@Composable
private fun errorText(code: String): String {
    val key = code.substringBefore('=').substringBefore(':')
    val rest = code.substringAfter(':', "").substringBefore('=')
    return when (key) {
        "failure" -> when (rest) {
            "not_configured" -> stringResource(R.string.err_not_configured)
            "config_invalid" -> stringResource(R.string.err_config_invalid)
            "hc_unavailable" -> stringResource(R.string.err_hc_unavailable)
            "permissions_missing" -> stringResource(R.string.err_permissions_missing)
            "auth" -> stringResource(R.string.err_auth)
            "network" -> stringResource(R.string.err_network)
            "server" -> stringResource(R.string.err_server)
            else -> stringResource(R.string.err_hc_error)
        }
        "routes_not_granted" -> stringResource(R.string.err_routes_not_granted)
        "token_expired" -> stringResource(R.string.err_token_expired)
        "type_read_failed" -> stringResource(R.string.err_type_read_failed, rest)
        "aggregate_failed" -> stringResource(R.string.err_aggregate_failed, rest)
        "record_rejected" -> stringResource(R.string.err_record_rejected, rest)
        else -> code
    }
}
