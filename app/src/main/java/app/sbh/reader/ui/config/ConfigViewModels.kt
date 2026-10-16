package app.sbh.reader.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sbh.reader.data.Settings
import app.sbh.reader.data.SettingsRepository
import app.sbh.reader.net.ApiResult
import app.sbh.reader.net.NetworkErrorKind
import app.sbh.reader.net.SbhApi
import app.sbh.reader.net.ServerConfig
import app.sbh.reader.net.UrlNormalizer
import app.sbh.reader.security.TokenStore
import app.sbh.reader.sync.RouteRefreshState
import app.sbh.reader.sync.StateUpdate
import app.sbh.reader.sync.SyncLogEntry
import app.sbh.reader.sync.SyncStore
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.sync.SyncPhase
import app.sbh.reader.work.SyncRunner
import app.sbh.reader.work.SyncScheduler
import app.sbh.reader.work.SyncStatusHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val tokenName: String?, val apiVersion: String, val clockSkewSeconds: Long?) : ConnectionTest
    data class Failed(val reason: Reason) : ConnectionTest {
        enum class Reason { BAD_URL, CLEARTEXT, UNREACHABLE, TIMEOUT, TLS, INVALID_TOKEN, REVOKED_TOKEN, NO_TOKEN, SERVER }
    }
}

data class ConfigUiState(
    val settings: Settings = Settings(),
    val urlInput: String = "",
    val tokenInput: String = "",
    val tokenSaved: Boolean = false,
    val urlInvalid: Boolean = false,
    val cleartextWarning: Boolean = false,
    val test: ConnectionTest = ConnectionTest.Idle,
    val dirty: Boolean = false,
    val savedJustNow: Boolean = false,
    /** Null = the GPS routes refresh was never started. */
    val routeRefresh: RouteRefreshState? = null,
    val routesPending: Boolean = false,
)

class ConfigViewModel(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val store: SyncStore,
    private val scheduler: SyncScheduler,
    /** Builds an API client for an explicit config, so the connection can be tested before saving. */
    private val apiFor: (ServerConfig) -> SbhApi,
    private val runner: SyncRunner,
    holder: SyncStatusHolder,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private data class Form(
        val urlInput: String? = null,
        val tokenInput: String = "",
        val test: ConnectionTest = ConnectionTest.Idle,
        val saved: Boolean = false,
        val tokenSaved: Boolean = false,
    )

    private val form = MutableStateFlow(Form(tokenSaved = tokens.hasToken()))

    private var routesJob: Job? = null
    private val routesRunning = MutableStateFlow(false)

    /** Workouts with a route delivered so far by the running GPS job. */
    val routesSent: StateFlow<Int> = holder.running
        .map { if (it?.phase == SyncPhase.ROUTES) it.recordsDelivered else 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val state: StateFlow<ConfigUiState> = combine(
        settings.settings, form, store.routeRefreshState(), routesRunning,
    ) { s, f, routes, routesPending ->
        val url = f.urlInput ?: s.serverUrl
        ConfigUiState(
            settings = s,
            urlInput = url,
            tokenInput = f.tokenInput,
            tokenSaved = f.tokenSaved,
            urlInvalid = url.isNotBlank() && UrlNormalizer.normalize(url) == null,
            cleartextWarning = UrlNormalizer.normalize(url)?.let(UrlNormalizer::isCleartext) == true,
            test = f.test,
            dirty = (f.urlInput != null && f.urlInput != s.serverUrl) || f.tokenInput.isNotBlank(),
            savedJustNow = f.saved,
            routeRefresh = routes,
            routesPending = routesPending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConfigUiState())

    fun onUrlChange(v: String) = form.update { it.copy(urlInput = v, saved = false, test = ConnectionTest.Idle) }
    fun onTokenChange(v: String) = form.update { it.copy(tokenInput = v.trim(), saved = false, test = ConnectionTest.Idle) }

    fun save() {
        viewModelScope.launch {
            val f = form.value
            f.urlInput?.let { raw ->
                settings.setServerUrl(UrlNormalizer.normalize(raw) ?: raw.trim())
            }
            if (f.tokenInput.isNotBlank()) tokens.set(f.tokenInput)
            form.update { Form(tokenSaved = tokens.hasToken(), saved = true) }
        }
    }

    fun clearToken() {
        tokens.clear()
        form.update { it.copy(tokenSaved = false, tokenInput = "", test = ConnectionTest.Idle) }
    }

    /** `GET /health` (reachability) then `GET /ping` (token), with the typed values or, if empty, the saved ones. */
    fun testConnection() {
        viewModelScope.launch {
            form.update { it.copy(test = ConnectionTest.Running) }
            val s = settings.current()
            val f = form.value
            val config = ServerConfig(
                baseUrl = f.urlInput ?: s.serverUrl,
                token = f.tokenInput.ifBlank { tokens.get() },
            )
            val result = runTest(config)
            form.update { it.copy(test = result) }
        }
    }

    private suspend fun runTest(config: ServerConfig): ConnectionTest {
        if (UrlNormalizer.normalize(config.baseUrl) == null) return ConnectionTest.Failed(ConnectionTest.Failed.Reason.BAD_URL)
        val api = apiFor(config)
        when (val h = api.health()) {
            is ApiResult.NetworkError -> return ConnectionTest.Failed(h.kind.toReason())
            is ApiResult.HttpError -> return ConnectionTest.Failed(ConnectionTest.Failed.Reason.SERVER)
            is ApiResult.Ok -> Unit
        }
        if (config.token.isNullOrBlank()) return ConnectionTest.Failed(ConnectionTest.Failed.Reason.NO_TOKEN)
        return when (val p = api.ping()) {
            is ApiResult.Ok -> {
                val skew = p.value.serverTime?.let { runCatching { Duration.between(Instant.parse(it), clock()).seconds }.getOrNull() }
                ConnectionTest.Ok(p.value.tokenName, p.value.apiVersion, skew?.takeIf { abs(it) > 120 })
            }
            is ApiResult.HttpError -> ConnectionTest.Failed(
                when {
                    p.code == "token_revoked" -> ConnectionTest.Failed.Reason.REVOKED_TOKEN
                    p.status == 401 -> ConnectionTest.Failed.Reason.INVALID_TOKEN
                    else -> ConnectionTest.Failed.Reason.SERVER
                },
            )
            is ApiResult.NetworkError -> ConnectionTest.Failed(p.kind.toReason())
        }
    }

    private fun NetworkErrorKind.toReason() = when (this) {
        NetworkErrorKind.NOT_CONFIGURED, NetworkErrorKind.BAD_URL -> ConnectionTest.Failed.Reason.BAD_URL
        NetworkErrorKind.CLEARTEXT_BLOCKED -> ConnectionTest.Failed.Reason.CLEARTEXT
        NetworkErrorKind.UNREACHABLE -> ConnectionTest.Failed.Reason.UNREACHABLE
        NetworkErrorKind.TIMEOUT -> ConnectionTest.Failed.Reason.TIMEOUT
        NetworkErrorKind.TLS -> ConnectionTest.Failed.Reason.TLS
    }

    fun setInterval(hours: Int) {
        viewModelScope.launch {
            settings.setIntervalHours(hours)
            scheduler.schedulePeriodic(settings.current())
        }
    }

    fun setWifiOnly(v: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(v)
            scheduler.schedulePeriodic(settings.current())
        }
    }

    fun setReduceMotion(v: Boolean) {
        viewModelScope.launch { settings.setReduceMotion(v) }
    }

    /**
     * Starts the GPS routes job. [restart] = from the beginning of the history; otherwise it resumes from its own
     * cursor. It never touches the normal sync cursors or the Changes token.
     */
    fun refreshRoutes(restart: Boolean) {
        if (routesJob?.isActive == true) return
        routesJob = viewModelScope.launch {
            routesRunning.value = true
            try {
                if (restart) store.commit(emptyList(), StateUpdate(routeRefresh = RouteRefreshState()))
                runner.refreshRoutes()
            } finally {
                routesRunning.value = false
            }
        }
    }

    /** Stops the GPS job (button, or the app leaving the foreground). Its cursor is saved: it can be resumed. */
    fun pauseRoutes() {
        routesJob?.cancel()
    }

    /** Forgets cursors and the Changes token: the next sync re-reads the whole history (idempotent upserts). */
    fun resetBackfill() {
        viewModelScope.launch {
            store.resetBackfill(HealthTypes.all.map { it.id })
            scheduler.syncNow(settings.current().wifiOnly)
        }
    }
}

class LogViewModel(store: SyncStore) : ViewModel() {
    /** Null until the first emission, so the screen can tell "loading" from "empty". */
    val logs: StateFlow<List<SyncLogEntry>?> =
        store.logs(100).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

class GuideViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val tick = MutableStateFlow(0)

    val done: StateFlow<Set<String>> = combine(settings.settings, tick) { s, _ -> s.guideDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun refresh() {
        tick.value++
    }

    fun setDone(step: String, done: Boolean) {
        viewModelScope.launch { settings.setGuideStepDone(step, done) }
    }
}
