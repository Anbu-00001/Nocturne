package io.github.anbu00001.nocturne.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.github.anbu00001.nocturne.collector.AndroidUsageEventSource
import java.util.concurrent.ConcurrentHashMap

object SystemAccess {
    fun hasUsageAccess(context: Context): Boolean = AndroidUsageEventSource(context).hasUsageAccess()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** PACKAGE_USAGE_STATS has no dialog (spec §4.3); the package-specific page is not on every ROM. */
    fun openUsageAccessSettings(context: Context) = startFirst(
        context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(context)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    @SuppressLint("BatteryLife") // a personal sideloaded build; the exemption is the point
    fun requestIgnoreBatteryOptimizations(context: Context) = startFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** ColorOS keeps auto launch and background activity switches on the app info page. */
    fun openAppInfo(context: Context) =
        startFirst(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    /** Hands the link to the browser; Nocturne itself has no internet permission. */
    fun openUrl(context: Context, url: String) = startFirst(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun startFirst(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}

/** Human app names, falling back to the package name for apps not visible to Nocturne. */
class AppLabels(private val context: Context) {
    private val cache = ConcurrentHashMap<String, String>()

    fun of(packageName: String): String = cache.getOrPut(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
