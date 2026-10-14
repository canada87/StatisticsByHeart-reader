package app.sbh.reader.ui.link

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sbh.reader.data.Settings
import app.sbh.reader.data.SettingsRepository
import app.sbh.reader.health.HealthAccess
import app.sbh.reader.health.HealthAccessState
import app.sbh.reader.health.SdkStatus
import app.sbh.reader.net.UrlNormalizer
import app.sbh.reader.security.TokenStore
import app.sbh.reader.sync.SyncLogEntry
import app.sbh.reader.sync.SyncProgress
import app.sbh.reader.sync.SyncStatus
import app.sbh.reader.sync.SyncStore
import app.sbh.reader.system.NetworkMonitor
import app.sbh.reader.system.PowerGuide
import app.sbh.reader.ui.components.BannerKind
import app.sbh.reader.ui.components.GaugeState
import app.sbh.reader.work.SyncScheduler
import app.sbh.reader.work.SyncStatusHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant

enum class IssueKind {
    HC_MISSING, HC_UPDATE, PERMISSIONS, HISTORY, BACKGROUND,
    NOT_CONFIGURED, AUTH, CONFIG_INVALID, UNREACHABLE, OFFLINE, BATTERY, STALE,
}

data class Issue(val kind: IssueKind, val severity: BannerKind)

data class LinkUiState(
    val gauge: GaugeState = GaugeState.SETUP,
    val running: SyncProgress? = null,
    val lastSuccess: Instant? = null,
    val outbox: Int = 0,
    val issues: List<Issue> = emptyList(),
    val canSync: Boolean = false,
    val missingTypes: Int = 0,
    val intervalHours: Int = 1,
)

class LinkViewModel(
    private val app: Application,
    settings: SettingsRepository,
    private val tokens: TokenStore,
    store: SyncStore,
    access: HealthAccess,
    holder: SyncStatusHolder,
    scheduler: SyncScheduler,
    network: NetworkMonitor,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private val tick = MutableStateFlow(0)

    /** Re-evaluates things the system can change behind our back (battery exemption, token). Call on resume. */
    fun refresh() {
        tick.value++
    }

    private data class Inputs(
        val settings: Settings,
        val outbox: Int,
        val lastSuccess: Instant?,
        val lastRun: SyncLogEntry?,
        val running: SyncProgress?,
    )

    private val inputs = combine(
        settings.settings, store.outboxCount(), store.lastSuccess(), store.lastRun(), holder.running,
    ) { s, o, ls, lr, r -> Inputs(s, o, ls, lr, r) }

    val state: StateFlow<LinkUiState> = combine(
        inputs, scheduler.manualPending, network.online, access.state, tick,
    ) { i, manual, online, hc, _ -> compute(i, manual, online, hc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkUiState())

    private fun compute(i: Inputs, manualPending: Boolean, online: Boolean, hc: HealthAccessState): LinkUiState {
        val issues = mutableListOf<Issue>()
        var blocking = false

        if (hc.loaded) {
            when (hc.sdk) {
                SdkStatus.NOT_INSTALLED -> { issues += Issue(IssueKind.HC_MISSING, BannerKind.ERROR); blocking = true }
                SdkStatus.UPDATE_REQUIRED -> { issues += Issue(IssueKind.HC_UPDATE, BannerKind.ERROR); blocking = true }
                SdkStatus.AVAILABLE -> {
                    if (hc.missingFor(i.settings.enabledTypeIds).isNotEmpty()) {
                        issues += Issue(IssueKind.PERMISSIONS, BannerKind.WARN)
                        blocking = true
                    }
                    if (hc.historyAvailable && !hc.historyGranted) issues += Issue(IssueKind.HISTORY, BannerKind.INFO)
                    if (hc.backgroundAvailable && !hc.backgroundGranted) issues += Issue(IssueKind.BACKGROUND, BannerKind.WARN)
                }
            }
        }

        val configured = UrlNormalizer.normalize(i.settings.serverUrl) != null && tokens.hasToken()
        if (!configured) {
            issues += Issue(IssueKind.NOT_CONFIGURED, BannerKind.WARN)
            blocking = true
        }

        val lastFailure = i.lastRun?.takeIf { it.status == SyncStatus.FAILED }?.errors.orEmpty()
        val authFailed = "failure:auth" in lastFailure
        val configInvalid = "failure:config_invalid" in lastFailure
        val networkFailed = "failure:network" in lastFailure || "failure:server" in lastFailure
        if (configured && authFailed) issues += Issue(IssueKind.AUTH, BannerKind.ERROR)
        if (configured && configInvalid) issues += Issue(IssueKind.CONFIG_INVALID, BannerKind.ERROR)
        if (!online) issues += Issue(IssueKind.OFFLINE, BannerKind.WARN)
        else if (configured && networkFailed) issues += Issue(IssueKind.UNREACHABLE, BannerKind.WARN)

        if (!PowerGuide.isIgnoringBatteryOptimizations(app)) issues += Issue(IssueKind.BATTERY, BannerKind.WARN)

        val now = clock()
        val staleAfter = Duration.ofHours(maxOf(3L, i.settings.intervalHours * 3L))
        val lastSuccess = i.lastSuccess
        if (configured && !blocking && lastSuccess != null && Duration.between(lastSuccess, now) > staleAfter) {
            issues += Issue(IssueKind.STALE, BannerKind.WARN)
        }

        val syncing = i.running != null || manualPending
        val gauge = when {
            syncing -> GaugeState.SYNCING
            blocking -> GaugeState.SETUP
            authFailed || configInvalid -> GaugeState.ERROR
            !online || networkFailed -> GaugeState.OFFLINE
            else -> GaugeState.IDLE
        }
        return LinkUiState(
            gauge = gauge,
            running = i.running,
            lastSuccess = lastSuccess,
            outbox = i.outbox,
            issues = issues.sortedBy { it.severity.ordinal },
            canSync = !blocking && !syncing,
            missingTypes = if (hc.loaded) hc.missingFor(i.settings.enabledTypeIds).size else 0,
            intervalHours = i.settings.intervalHours,
        )
    }
}
