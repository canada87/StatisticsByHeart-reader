package app.sbh.reader.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the guided "keep the app alive" screen (PROJECT_GUIDE §3.1, HyperOS/MIUI).
 * Only battery-optimisation exemption is detectable; the other steps are confirmed by the user.
 */
object PowerGuide {
    const val STEP_BATTERY = "battery"
    const val STEP_AUTOSTART = "autostart"
    const val STEP_NO_RESTRICTION = "no_restriction"
    const val STEP_LOCK_RECENTS = "lock_recents"

    val steps = listOf(STEP_BATTERY, STEP_AUTOSTART, STEP_NO_RESTRICTION, STEP_LOCK_RECENTS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("xiaomi", true) ||
        Build.MANUFACTURER.equals("redmi", true) || Build.MANUFACTURER.equals("poco", true)

    /** System dialog asking to exempt the app from battery optimisation. */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = startFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** MIUI/HyperOS "Autostart" list; falls back to the app details page. */
    fun openAutostart(context: Context): Boolean = startFirst(
        context,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        appDetails(context),
    )

    /** MIUI/HyperOS per-app battery saver ("No restrictions"); falls back to the app details page. */
    fun openAppBatterySaver(context: Context): Boolean = startFirst(
        context,
        Intent()
            .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "StatisticsByHeart"),
        appDetails(context),
    )

    fun openAppDetails(context: Context): Boolean = startFirst(context, appDetails(context))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun startFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next fallback
            } catch (_: SecurityException) {
                // MIUI may block its internal activities for third-party apps
            }
        }
        return false
    }
}
