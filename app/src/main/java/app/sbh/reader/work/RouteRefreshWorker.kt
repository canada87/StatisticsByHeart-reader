package app.sbh.reader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * "Riscarica tracce GPS": runs [SyncRunner.refreshRoutes] as foreground work (it can be long) and retries transient
 * failures; the engine resumes from its own persisted cursor, so a restart never begins from scratch.
 */
class RouteRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: SyncRunner,
    private val notifier: SyncNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.foregroundInfo()

    override suspend fun doWork(): Result {
        val foreground = runCatching { setForeground(notifier.foregroundInfo()) }.isSuccess
        val result = runner.refreshRoutes { progress -> if (foreground) notifier.update(progress) }
        return if (result.shouldRetry) Result.retry() else Result.success()
    }
}
