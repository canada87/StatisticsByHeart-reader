package app.sbh.reader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/** Periodic and "sync now" entry point. Delegates everything to [SyncRunner]; see [SyncScheduler]. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: SyncRunner,
    private val notifier: SyncNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.foregroundInfo()

    override suspend fun doWork(): Result {
        // Promote to a foreground service so long backfills survive; may be refused when started from the
        // background (then we simply run as normal work and resume from the persisted cursor next time).
        val foreground = runCatching { setForeground(notifier.foregroundInfo()) }.isSuccess
        val result = runner.run { progress -> if (foreground) notifier.update(progress) }
        return if (result.shouldRetry) Result.retry() else Result.success()
    }
}
