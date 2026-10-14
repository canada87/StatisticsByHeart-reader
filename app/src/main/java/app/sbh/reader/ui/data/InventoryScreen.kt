package app.sbh.reader.ui.data

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.R
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.health.SourceStat
import app.sbh.reader.health.TypeInventory
import app.sbh.reader.ui.MainViewModel
import app.sbh.reader.ui.components.BannerKind
import app.sbh.reader.ui.components.ButtonStyle
import app.sbh.reader.ui.components.HudButton
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.PushedHeader
import app.sbh.reader.ui.components.SbhIcon
import app.sbh.reader.ui.components.StatusBanner
import app.sbh.reader.ui.formatCount
import app.sbh.reader.ui.formatDate
import app.sbh.reader.ui.theme.SbhTheme

/** Friendly names for source apps we know; anything else is shown as its package name. */
private fun sourceLabel(pkg: String): String? = when (pkg) {
    "com.google.android.apps.fitness" -> "Google Fit"
    "com.withings.wiscale2" -> "Withings"
    else -> null
}

@Composable
fun InventoryScreen(
    vm: InventoryViewModel,
    main: MainViewModel,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = SbhTheme.colors
    val scanning = state.state as? InventoryState.Scanning
    val shareTitle = stringResource(R.string.inventory_share)

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") { PushedHeader(stringResource(R.string.inventory_title), onBack) }
        item(key = "intro") {
            Text(stringResource(R.string.inventory_intro), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
        }
        if (state.notGranted.isNotEmpty()) {
            item(key = "ungranted") {
                StatusBanner(
                    BannerKind.INFO,
                    stringResource(R.string.inventory_ungranted_title, state.notGranted.size),
                    stringResource(R.string.inventory_ungranted_body),
                    actionLabel = stringResource(R.string.inventory_grant_all),
                    onAction = { main.requestPermissions(HealthTypes.all) },
                )
            }
        }
        item(key = "action") {
            if (scanning != null) {
                HudPanel {
                    val label = stringResource(HealthTypes.byId.getValue(scanning.currentTypeId).labelRes)
                    Text(
                        stringResource(R.string.inventory_scanning, label, scanning.done + 1, scanning.total),
                        style = MaterialTheme.typography.bodyMedium, color = c.fg,
                    )
                    Text(
                        stringResource(R.string.inventory_read_so_far, formatCount(scanning.readSoFar)),
                        style = MaterialTheme.typography.bodySmall, color = c.fgMuted,
                    )
                    LinearProgressIndicator(
                        progress = { scanning.done.toFloat() / scanning.total },
                        modifier = Modifier.fillMaxWidth(),
                        color = c.accentGlow,
                        trackColor = c.border,
                    )
                    HudButton(stringResource(R.string.action_cancel), vm::cancel, Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY)
                }
            } else {
                HudButton(
                    stringResource(if (state.results.isEmpty()) R.string.inventory_start else R.string.inventory_restart),
                    vm::start,
                    Modifier.fillMaxWidth(),
                    enabled = state.scannable.isNotEmpty(),
                    icon = R.drawable.ic_list_magnifying_glass,
                )
            }
        }
        if (state.results.isNotEmpty() && scanning == null) {
            item(key = "share") {
                HudButton(
                    shareTitle,
                    {
                        val text = vm.report { id -> HealthTypes.byId[id]?.let { context.getString(it.labelRes) } ?: id }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    },
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.SECONDARY,
                    icon = R.drawable.ic_share_network,
                )
            }
        }
        items(state.results.sortedByDescending { it.total }, key = { it.typeId }) { inv ->
            InventoryCard(inv)
        }
    }
}

@Composable
private fun InventoryCard(inv: TypeInventory) {
    val c = SbhTheme.colors
    val spec = HealthTypes.byId.getValue(inv.typeId)
    HudPanel(accent = groupColor(spec.group)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SbhIcon(spec.iconRes, null, tint = groupColor(spec.group))
            Column(Modifier.weight(1f)) {
                Text(stringResource(spec.labelRes), style = MaterialTheme.typography.titleSmall, color = c.fg)
                if (inv.total == 0L) {
                    Text(stringResource(R.string.inventory_no_data), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
                } else {
                    Text(
                        stringResource(R.string.inventory_range, formatDate(inv.first!!), formatDate(inv.last!!)),
                        style = MaterialTheme.typography.bodySmall, color = c.fgMuted,
                    )
                }
            }
            Text(
                formatCount(inv.total) + if (inv.truncated) "+" else "",
                style = MaterialTheme.typography.titleMedium, color = c.fg,
            )
        }
        if (inv.error) {
            Text(stringResource(R.string.inventory_error), style = MaterialTheme.typography.bodySmall, color = c.dangerText)
        }
        inv.sources.forEach { s ->
            HorizontalDivider(color = c.border)
            SourceRow(s)
        }
    }
}

@Composable
private fun SourceRow(s: SourceStat) {
    val c = SbhTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(sourceLabel(s.packageName) ?: s.packageName, style = MaterialTheme.typography.bodySmall, color = c.fg)
            if (sourceLabel(s.packageName) != null) {
                Text(s.packageName, style = MaterialTheme.typography.labelSmall, color = c.fgMuted)
            }
            if (s.first != null && s.last != null) {
                Text(
                    stringResource(R.string.inventory_range, formatDate(s.first), formatDate(s.last)),
                    style = MaterialTheme.typography.labelSmall, color = c.fgMuted,
                )
            }
        }
        Text(formatCount(s.count), style = MaterialTheme.typography.bodySmall, color = c.fg)
    }
}
