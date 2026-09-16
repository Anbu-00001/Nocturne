package io.github.anbu00001.nocturne.export

import android.Manifest
import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.collector.LightService
import io.github.anbu00001.nocturne.core.glance.CLASSIFIER_VERSION
import io.github.anbu00001.nocturne.core.metrics.METRICS_VERSION
import io.github.anbu00001.nocturne.core.sleep.SLEEP_MODEL_VERSION
import io.github.anbu00001.nocturne.data.DataExport
import io.github.anbu00001.nocturne.ui.SystemAccess
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * "Export all data" (Settings, and the debug receiver): [DataExport]'s tables, with what the tables cannot say about the
 * phone they came from. That second part is what makes another person's export useful for development: the ROM, the
 * permissions and battery state that decide whether Nocturne ran, and the packages the classifier treats specially.
 */
object FullExport {

    /** Harvests and recomputes wait until the export is written. */
    suspend fun write(app: NocturneApp, out: OutputStream): DataExport.Summary =
        app.harvester.whileIdle { DataExport.writeZip(app.database, out, about(app)) }

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "nocturne-export-${Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "-")}-${Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()}.zip"

    private fun about(app: NocturneApp): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val profile = app.deviceProfile
        val metrics = app.resources.displayMetrics
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val alarms = app.getSystemService(AlarmManager::class.java)
        val usage = app.getSystemService(UsageStatsManager::class.java)
        val sensor = profile.lightSensor()
        fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
        return listOf(
            "exported_at_utc_ms" to now.toString(),
            "exported_at_local" to Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toString(),
            "time_zone" to ZoneId.systemDefault().id,
            "app_version" to (packageInfo.versionName ?: ""),
            "model_version" to "$CLASSIFIER_VERSION.$SLEEP_MODEL_VERSION.$METRICS_VERSION",
            "schema_version" to app.database.openHelper.readableDatabase.version.toString(),
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android_release" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT.toString(),
            "rom_build" to Build.DISPLAY,
            "security_patch" to Build.VERSION.SECURITY_PATCH,
            "screen_px" to "${metrics.widthPixels}x${metrics.heightPixels}",
            "screen_density_dpi" to metrics.densityDpi.toString(),
            "display_profile" to if (profile.isOppoA18) "OPPO_A18" else "GENERIC",
            "screen_off_timeout_ms" to profile.sleepConfig().screenOffTimeoutMs.toString(),
            "light_sensor" to (sensor?.let { "${it.name}, resolution ${it.resolutionLux} lx, max ${it.maximumLux} lx" } ?: "none"),
            "light_service_enabled" to LightService.isEnabled(app).toString(),
            "usage_access" to SystemAccess.hasUsageAccess(app).toString(),
            "battery_exempt" to SystemAccess.isIgnoringBatteryOptimizations(app).toString(),
            // 5 exempted, 10 active, 20 working set, 30 frequent, 40 rare, 45 restricted.
            "standby_bucket" to usage.appStandbyBucket.toString(),
            "exact_alarms" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()).toString(),
            "notifications_granted" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)).toString(),
            "classifier_packages" to profile.classifierConfig(keyguardEventsSeen = true).let {
                "trivial=${it.trivialPackages.sorted()}; alarm=${it.alarmPackages.sorted()}; call=${it.callPackages.sorted()}; " +
                    "over_lockscreen=${it.overLockscreenPackages.sorted()}"
            },
        )
    }
}
