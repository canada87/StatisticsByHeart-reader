package app.sbh.reader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.sbh.reader.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** WorkManager wiring: hourly periodic sync (network constraint, exponential backoff) and "sync now". */
class SyncScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context.applicationContext)

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Idempotent: call on app start and whenever interval / Wi-Fi-only change. */
    fun schedulePeriodic(settings: Settings) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(settings.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodic() {
        wm.cancelUniqueWork(PERIODIC)
    }

    /** "Sincronizza ora": expedited when quota allows, otherwise a normal job. */
    fun syncNow(wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** "Riscarica tracce GPS": one job at a time (KEEP), same network constraint as the sync. */
    fun refreshRoutes(wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<RouteRefreshWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(ROUTES, ExistingWorkPolicy.KEEP, request)
    }

    /** True while the routes job is queued or running. */
    val routesPending: Flow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(ROUTES).map { list ->
        list.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
    }

    /** True while a "sync now" job is queued or running. */
    val manualPending: Flow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(NOW).map { list ->
        list.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
    }

    companion object {
        const val PERIODIC = "sbh-periodic-sync"
        const val NOW = "sbh-sync-now"
        const val ROUTES = "sbh-routes-refresh"
    }
}
