package app.sbh.reader.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.sbh.reader.MainActivity
import app.sbh.reader.R
import app.sbh.reader.sync.SyncPhase
import app.sbh.reader.sync.SyncProgress

/** Discreet foreground notification for long syncs (backfill). Shows only phase and counts, never values. */
class SyncNotifier(private val context: Context) {

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_sync_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun foregroundInfo(progress: SyncProgress? = null): ForegroundInfo {
        ensureChannel()
        val notification = build(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    fun update(progress: SyncProgress) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(progress))
    }

    private fun build(progress: SyncProgress?): Notification {
        val text = when (progress?.phase) {
            SyncPhase.BACKFILL -> context.getString(R.string.notif_phase_backfill, progress.recordsDelivered)
            SyncPhase.UPLOADING -> context.getString(R.string.notif_phase_uploading, progress.recordsDelivered)
            SyncPhase.ROUTES -> context.getString(R.string.notif_phase_routes, progress.recordsDelivered)
            SyncPhase.CHANGES, SyncPhase.AGGREGATES -> context.getString(R.string.notif_phase_changes)
            else -> context.getString(R.string.notif_phase_preparing)
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrows_clockwise)
            .setContentTitle(context.getString(R.string.notif_title_sync))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "sbh_sync"
        const val NOTIFICATION_ID = 4201
    }
}
