package app.sbh.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sbh.reader.data.SettingsRepository
import app.sbh.reader.health.HealthAccess
import app.sbh.reader.health.HealthTypeSpec
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.work.SyncScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One-shot UI commands. Delivered through a Channel (not StateFlow) so they are consumed exactly once. */
sealed interface UiEvent {
    data class RequestPermissions(val permissions: Set<String>) : UiEvent
    data object OpenHealthConnectSettings : UiEvent
    data object OpenHealthConnectStore : UiEvent
    data object RequestNotificationPermission : UiEvent
}

/** App-wide actions shared by all top-level screens: Health Connect permissions and "sync now". */
class MainViewModel(
    private val healthAccess: HealthAccess,
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    val access = healthAccess.state

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    val reduceMotion: Flow<Boolean> = settings.settings.map { it.reduceMotion }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            settings.settings
                .map { it.intervalHours to it.wifiOnly }
                .distinctUntilChanged()
                .collect { scheduler.schedulePeriodic(settings.current()) }
        }
        refreshAccess()
    }

    fun refreshAccess() {
        viewModelScope.launch { healthAccess.refresh() }
    }

    /** Asks HC for the permissions of the enabled types (default) or of the given types. */
    fun requestPermissions(types: Collection<HealthTypeSpec>? = null) {
        viewModelScope.launch {
            healthAccess.refresh()
            val state = healthAccess.state.value
            val chosen = types ?: HealthTypes.all.filter { it.id in settings.current().enabledTypeIds }
            val permissions = state.permissionsToRequest(chosen)
            if (permissions.isNotEmpty()) _events.send(UiEvent.RequestPermissions(permissions))
        }
    }

    fun onPermissionsResult() {
        viewModelScope.launch {
            healthAccess.refresh()
            // First backfill should start right after the grant (the 30-day window starts at the first grant).
            scheduler.syncNow(settings.current().wifiOnly)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _events.send(UiEvent.RequestNotificationPermission)
            scheduler.syncNow(settings.current().wifiOnly)
        }
    }

    fun openHealthConnectSettings() {
        viewModelScope.launch { _events.send(UiEvent.OpenHealthConnectSettings) }
    }

    fun openHealthConnectStore() {
        viewModelScope.launch { _events.send(UiEvent.OpenHealthConnectStore) }
    }
}
