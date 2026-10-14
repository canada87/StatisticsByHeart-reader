package app.sbh.reader.ui.config

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sbh.reader.BuildConfig
import app.sbh.reader.R
import app.sbh.reader.data.Settings
import app.sbh.reader.ui.MainViewModel
import app.sbh.reader.ui.components.BannerKind
import app.sbh.reader.ui.components.ButtonStyle
import app.sbh.reader.ui.components.HudButton
import app.sbh.reader.ui.components.HudPanel
import app.sbh.reader.ui.components.HudSwitchRow
import app.sbh.reader.ui.components.HudTextField
import app.sbh.reader.ui.components.SbhIcon
import app.sbh.reader.ui.components.ScreenTitle
import app.sbh.reader.ui.components.SectionLabel
import app.sbh.reader.ui.components.StatusBanner
import app.sbh.reader.ui.theme.SbhTheme

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ConfigScreen(
    vm: ConfigViewModel,
    main: MainViewModel,
    padding: PaddingValues,
    onOpenGuide: () -> Unit,
    onOpenRationale: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = SbhTheme.colors
    var showToken by rememberSaveable { mutableStateOf(false) }
    var confirmBackfill by rememberSaveable { mutableStateOf(false) }
    var confirmClearToken by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(R.string.nav_config)) }

        // ---- server ----
        item {
            HudPanel {
                SectionLabel(stringResource(R.string.config_server))
                HudTextField(
                    value = state.urlInput,
                    onValueChange = vm::onUrlChange,
                    label = stringResource(R.string.config_url),
                    supporting = stringResource(if (state.urlInvalid) R.string.config_url_invalid else R.string.config_url_hint),
                    isError = state.urlInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (state.cleartextWarning) {
                    Text(stringResource(R.string.config_cleartext), style = MaterialTheme.typography.bodySmall, color = c.warn)
                }
                HudTextField(
                    value = state.tokenInput,
                    onValueChange = vm::onTokenChange,
                    label = stringResource(R.string.config_token),
                    supporting = stringResource(if (state.tokenSaved) R.string.config_token_saved else R.string.config_token_hint),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailing = {
                        IconButton(onClick = { showToken = !showToken }) {
                            SbhIcon(
                                if (showToken) R.drawable.ic_eye_slash else R.drawable.ic_eye,
                                stringResource(if (showToken) R.string.config_token_hide else R.string.config_token_show),
                            )
                        }
                    },
                )
                ConnectionResult(state.test)
                HudButton(
                    stringResource(R.string.config_test),
                    vm::testConnection,
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.SECONDARY,
                    icon = R.drawable.ic_plugs_connected,
                    enabled = state.test !is ConnectionTest.Running && !state.urlInvalid && state.urlInput.isNotBlank(),
                )
                HudButton(
                    stringResource(if (state.savedJustNow) R.string.config_saved else R.string.config_save),
                    vm::save,
                    Modifier.fillMaxWidth(),
                    icon = R.drawable.ic_check_circle,
                    enabled = state.dirty && !state.urlInvalid,
                )
                if (state.tokenSaved) {
                    HudButton(
                        stringResource(R.string.config_token_clear),
                        { confirmClearToken = true },
                        Modifier.fillMaxWidth(),
                        style = ButtonStyle.DANGER,
                        icon = R.drawable.ic_trash,
                    )
                }
            }
        }

        // ---- sync ----
        item {
            HudPanel {
                SectionLabel(stringResource(R.string.config_sync))
                Text(stringResource(R.string.config_interval), style = MaterialTheme.typography.bodyMedium, color = c.fg)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.INTERVAL_OPTIONS_HOURS.forEach { h ->
                        FilterChip(
                            selected = state.settings.intervalHours == h,
                            onClick = { vm.setInterval(h) },
                            label = { Text(pluralStringResource(R.plurals.link_interval, h, h)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.accent,
                                selectedLabelColor = c.onAccent,
                                labelColor = c.fg,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = state.settings.intervalHours == h,
                                borderColor = c.borderControl,
                                selectedBorderColor = c.accent,
                            ),
                        )
                    }
                }
                HudSwitchRow(
                    label = stringResource(R.string.config_wifi_only),
                    checked = state.settings.wifiOnly,
                    onCheckedChange = vm::setWifiOnly,
                    supporting = stringResource(R.string.config_wifi_only_hint),
                )
                HorizontalDivider(color = c.border)
                HudButton(
                    stringResource(R.string.config_power_guide),
                    onOpenGuide,
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.SECONDARY,
                    icon = R.drawable.ic_battery_charging,
                )
            }
        }

        // ---- appearance ----
        item {
            HudPanel {
                SectionLabel(stringResource(R.string.config_accessibility))
                HudSwitchRow(
                    label = stringResource(R.string.config_reduce_motion),
                    checked = state.settings.reduceMotion,
                    onCheckedChange = vm::setReduceMotion,
                    supporting = stringResource(R.string.config_reduce_motion_hint),
                )
            }
        }

        // ---- health connect ----
        item {
            HudPanel {
                SectionLabel(stringResource(R.string.config_hc))
                Text(stringResource(R.string.config_hc_body), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
                HudButton(
                    stringResource(R.string.action_grant_permissions),
                    { main.requestPermissions() },
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.SECONDARY,
                    icon = R.drawable.ic_shield_check,
                )
                HudButton(
                    stringResource(R.string.config_hc_open_settings),
                    main::openHealthConnectSettings,
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.SECONDARY,
                    icon = R.drawable.ic_gear_six,
                )
                HudButton(
                    stringResource(R.string.config_privacy),
                    onOpenRationale,
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.SECONDARY,
                    icon = R.drawable.ic_lock_key,
                )
            }
        }

        // ---- history ----
        item {
            HudPanel(accent = c.danger) {
                SectionLabel(stringResource(R.string.config_history), color = c.dangerText)
                Text(stringResource(R.string.config_backfill_body), style = MaterialTheme.typography.bodySmall, color = c.fgMuted)
                HudButton(
                    stringResource(R.string.config_backfill_action),
                    { confirmBackfill = true },
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.DANGER,
                    icon = R.drawable.ic_download_simple,
                )
            }
        }

        item {
            Text(
                stringResource(R.string.config_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = c.fgMuted,
            )
        }
    }

    if (confirmBackfill) {
        ConfirmDialog(
            title = stringResource(R.string.config_backfill_confirm_title),
            body = stringResource(R.string.config_backfill_confirm_body),
            confirm = stringResource(R.string.config_backfill_action),
            onConfirm = { confirmBackfill = false; vm.resetBackfill() },
            onDismiss = { confirmBackfill = false },
        )
    }
    if (confirmClearToken) {
        ConfirmDialog(
            title = stringResource(R.string.config_token_clear_title),
            body = stringResource(R.string.config_token_clear_body),
            confirm = stringResource(R.string.config_token_clear),
            onConfirm = { confirmClearToken = false; vm.clearToken() },
            onDismiss = { confirmClearToken = false },
        )
    }
}

@Composable
private fun ConnectionResult(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Running -> Text(
            stringResource(R.string.test_running),
            style = MaterialTheme.typography.bodySmall, color = SbhTheme.colors.fgMuted,
        )
        is ConnectionTest.Ok -> StatusBanner(
            BannerKind.OK,
            stringResource(R.string.test_ok_title),
            stringResource(R.string.test_ok_body, test.tokenName ?: "-", test.apiVersion) +
                (test.clockSkewSeconds?.let { "\n" + stringResource(R.string.test_clock_skew, it) } ?: ""),
        )
        is ConnectionTest.Failed -> {
            val (title, body) = when (test.reason) {
                ConnectionTest.Failed.Reason.BAD_URL -> R.string.test_bad_url_title to R.string.test_bad_url_body
                ConnectionTest.Failed.Reason.CLEARTEXT -> R.string.test_cleartext_title to R.string.test_cleartext_body
                ConnectionTest.Failed.Reason.UNREACHABLE -> R.string.test_unreachable_title to R.string.test_unreachable_body
                ConnectionTest.Failed.Reason.TIMEOUT -> R.string.test_timeout_title to R.string.test_timeout_body
                ConnectionTest.Failed.Reason.TLS -> R.string.test_tls_title to R.string.test_tls_body
                ConnectionTest.Failed.Reason.INVALID_TOKEN -> R.string.test_invalid_token_title to R.string.test_invalid_token_body
                ConnectionTest.Failed.Reason.REVOKED_TOKEN -> R.string.test_revoked_token_title to R.string.test_revoked_token_body
                ConnectionTest.Failed.Reason.NO_TOKEN -> R.string.test_no_token_title to R.string.test_no_token_body
                ConnectionTest.Failed.Reason.SERVER -> R.string.test_server_title to R.string.test_server_body
            }
            StatusBanner(BannerKind.ERROR, stringResource(title), stringResource(body))
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = SbhTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceMuted,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = c.fg) },
        text = { Text(body, style = MaterialTheme.typography.bodySmall, color = c.fgMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm.uppercase(), color = c.dangerText, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel).uppercase(), color = c.fg, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
