package app.sbh.reader.work

import app.sbh.reader.sync.SyncEngine
import app.sbh.reader.sync.SyncProgress
import app.sbh.reader.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process view of the sync that is running right now (null = idle). Read by the UI and the notification. */
class SyncStatusHolder {
    private val _running = MutableStateFlow<SyncProgress?>(null)
    val running: StateFlow<SyncProgress?> = _running.asStateFlow()

    fun update(progress: SyncProgress?) {
        _running.value = progress
    }
}

/** Runs the engine and mirrors its progress into [SyncStatusHolder] / the foreground notification. */
class SyncRunner(
    private val engine: SyncEngine,
    private val status: SyncStatusHolder,
) {
    suspend fun run(onProgress: (SyncProgress) -> Unit = {}): SyncResult = track(onProgress) { engine.run(it) }

    /**
     * The resumable "re-download GPS routes" job (see [SyncEngine.refreshRoutes]). Run it from the UI while the app is
     * in the foreground: Health Connect hides other apps' routes from background reads.
     */
    suspend fun refreshRoutes(onProgress: (SyncProgress) -> Unit = {}): SyncResult =
        track(onProgress) { engine.refreshRoutes(it) }

    private suspend fun track(onProgress: (SyncProgress) -> Unit, block: suspend ((SyncProgress) -> Unit) -> SyncResult): SyncResult {
        try {
            return block { p ->
                status.update(p)
                onProgress(p)
            }
        } finally {
            status.update(null)
        }
    }
}
