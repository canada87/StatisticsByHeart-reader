package app.sbh.reader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.sbh.reader.health.HealthTypes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

data class Settings(
    val serverUrl: String = "",
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    val wifiOnly: Boolean = false,
    val reduceMotion: Boolean = false,
    val enabledTypeIds: Set<String> = HealthTypes.defaultEnabledIds,
    /** Steps of the HyperOS guide the user ticked as done (they cannot be detected programmatically). */
    val guideDone: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_INTERVAL_HOURS = 1
        val INTERVAL_OPTIONS_HOURS = listOf(1, 2, 3, 6, 12, 24)
    }
}

private val Context.dataStore by preferencesDataStore(name = "sbh_settings")

/** App settings (DataStore). The API token is NOT here: see [app.sbh.reader.security.TokenStore]. */
class SettingsRepository(private val context: Context) {
    private val store get() = context.dataStore

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setServerUrl(url: String) = edit { it[SERVER_URL] = url }
    suspend fun setIntervalHours(h: Int) = edit { it[INTERVAL_HOURS] = h }
    suspend fun setWifiOnly(v: Boolean) = edit { it[WIFI_ONLY] = v }
    suspend fun setReduceMotion(v: Boolean) = edit { it[REDUCE_MOTION] = v }
    suspend fun setTypeEnabled(typeId: String, enabled: Boolean) = edit {
        val cur = it[ENABLED_TYPES] ?: HealthTypes.defaultEnabledIds
        it[ENABLED_TYPES] = if (enabled) cur + typeId else cur - typeId
    }

    suspend fun setGuideStepDone(step: String, done: Boolean) = edit {
        val cur = it[GUIDE_DONE] ?: emptySet()
        it[GUIDE_DONE] = if (done) cur + step else cur - step
    }

    /** Stable per-install id sent as `device_id` (contract §1.1). Generated once. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var created: String? = null
        store.edit {
            val existing = it[DEVICE_ID]
            if (existing != null) {
                created = existing
            } else {
                created = UUID.randomUUID().toString().also { id -> it[DEVICE_ID] = id }
            }
        }
        return created!!
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    private fun Preferences.toSettings() = Settings(
        serverUrl = this[SERVER_URL].orEmpty(),
        intervalHours = this[INTERVAL_HOURS] ?: Settings.DEFAULT_INTERVAL_HOURS,
        wifiOnly = this[WIFI_ONLY] ?: false,
        reduceMotion = this[REDUCE_MOTION] ?: false,
        enabledTypeIds = (this[ENABLED_TYPES] ?: HealthTypes.defaultEnabledIds).filter { it in HealthTypes.byId }.toSet(),
        guideDone = this[GUIDE_DONE] ?: emptySet(),
    )

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val INTERVAL_HOURS = intPreferencesKey("interval_hours")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val ENABLED_TYPES = stringSetPreferencesKey("enabled_types")
        val GUIDE_DONE = stringSetPreferencesKey("guide_done")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
