package app.sbh.reader.health

import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of what the app may do with Health Connect right now. */
data class HealthAccessState(
    val loaded: Boolean = false,
    val sdk: SdkStatus = SdkStatus.AVAILABLE,
    val granted: Set<String> = emptySet(),
    val historyAvailable: Boolean = false,
    val backgroundAvailable: Boolean = false,
    val unsupportedTypeIds: Set<String> = emptySet(),
) {
    val historyGranted: Boolean get() = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
    val backgroundGranted: Boolean get() = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted

    fun isGranted(spec: HealthTypeSpec) = spec.readPermission in granted
    fun isSupported(spec: HealthTypeSpec) = spec.id !in unsupportedTypeIds

    /** Enabled, supported types still lacking their read permission. */
    fun missingFor(enabledIds: Set<String>): List<HealthTypeSpec> =
        HealthTypes.all.filter { it.id in enabledIds && isSupported(it) && !isGranted(it) }

    /**
     * Permissions to ask in one request: the given types plus the history and background permissions when the
     * installed Health Connect supports them (requesting an unsupported permission would fail).
     */
    fun permissionsToRequest(types: Collection<HealthTypeSpec>): Set<String> = buildSet {
        types.filter { isSupported(it) }.forEach { add(it.readPermission) }
        if (historyAvailable) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        if (backgroundAvailable) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }
}

/** Holds [HealthAccessState]; refreshed when the app comes to the foreground and after permission dialogs. */
class HealthAccess(private val repository: HealthRepository) {
    private val _state = MutableStateFlow(HealthAccessState())
    val state: StateFlow<HealthAccessState> = _state.asStateFlow()

    suspend fun refresh() {
        val sdk = repository.sdkStatus()
        if (sdk != SdkStatus.AVAILABLE) {
            _state.value = HealthAccessState(loaded = true, sdk = sdk)
            return
        }
        _state.value = try {
            HealthAccessState(
                loaded = true,
                sdk = sdk,
                granted = repository.grantedPermissions(),
                historyAvailable = repository.isFeatureAvailable(HcFeature.HISTORY),
                backgroundAvailable = repository.isFeatureAvailable(HcFeature.BACKGROUND),
                unsupportedTypeIds = repository.unsupportedTypeIds(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            HealthAccessState(loaded = true, sdk = sdk)
        }
    }
}
