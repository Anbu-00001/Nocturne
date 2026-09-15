package io.github.anbu00001.nocturne.collector

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts and stops the light sampler. It stays off until the user turns it on (spec §4.2 calls it optional);
 * after that every chance to start it is taken: opening the app, each harvester run, boot and app updates.
 *
 * Android 12+ refuses foreground-service starts from the background unless an exemption applies. Nocturne's are
 * the battery-optimisation exemption for the harvester's runs, and BOOT_COMPLETED / MY_PACKAGE_REPLACED, from
 * which Android 15 still allows `specialUse` (only camera, dataSync, mediaPlayback, mediaProjection, microphone
 * and phoneCall are barred). A refusal is recorded for Settings, never thrown.
 */
object LightService {

    data class Refusal(val at: Long, val reason: String)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) ensureRunning(context) else context.stopService(Intent(context, LightSamplerService::class.java))
    }

    /** Returns whether the sampler is running or has been asked to start. */
    fun ensureRunning(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (LightSamplerService.running) return true
        return try {
            ContextCompat.startForegroundService(context, Intent(context, LightSamplerService::class.java))
            true
        } catch (e: IllegalStateException) {
            recordRefusal(context, e)
            false
        } catch (e: SecurityException) {
            recordRefusal(context, e)
            false
        }
    }

    fun lastRefusal(context: Context): Refusal? {
        val p = prefs(context)
        val reason = p.getString(KEY_REFUSAL, null) ?: return null
        return Refusal(p.getLong(KEY_REFUSAL_AT, 0), reason)
    }

    internal fun recordRefusal(context: Context, error: Exception) {
        prefs(context).edit()
            .putString(KEY_REFUSAL, error.javaClass.simpleName)
            .putLong(KEY_REFUSAL_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "nocturne-light"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REFUSAL = "lastRefusal"
    private const val KEY_REFUSAL_AT = "lastRefusalAt"
}
