package io.github.anbu00001.nocturne.collector

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.glance.UnlockEvidence
import io.github.anbu00001.nocturne.core.light.DisplayProfile
import io.github.anbu00001.nocturne.core.sleep.SleepConfig

/** Adds this phone's actual home app, clock and dialer to the classifier's default package sets. */
class DeviceProfile(private val context: Context) {

    data class LightSensorInfo(val name: String, val resolutionLux: Float, val maximumLux: Float)

    fun classifierConfig(keyguardEventsSeen: Boolean): ClassifierConfig {
        val homes = resolve(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            .filterNot { it == SETTINGS_PACKAGE } // FallbackHome answers HOME during boot on many ROMs
        val clocks = resolve(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        val dialer = listOfNotNull(context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage)
        val base = ClassifierConfig()
        return base.copy(
            unlockEvidence = if (keyguardEventsSeen) UnlockEvidence.KEYGUARD_EVENTS else UnlockEvidence.ACTIVITY_INFERRED,
            trivialPackages = base.trivialPackages + homes + clocks,
            alarmPackages = base.alarmPackages + clocks,
            callPackages = base.callPackages + dialer,
            overLockscreenPackages = base.overLockscreenPackages + clocks + dialer,
        )
    }

    /**
     * The screen-off timeout decides how long a screen stays lit after the last touch (30 min on the A18),
     * which sleep onset has to see past. Only the current value is readable, so it applies to all history.
     */
    fun sleepConfig(): SleepConfig {
        val default = SleepConfig()
        return default.copy(
            screenOffTimeoutMs = Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, default.screenOffTimeoutMs),
        )
    }

    /** True on the phone whose panel was read over adb; everything else gets the spec's uncalibrated defaults. */
    val isOppoA18: Boolean get() = Build.MODEL == A18_MODEL

    fun displayProfile(): DisplayProfile = if (isOppoA18) DisplayProfile.OPPO_A18 else DisplayProfile.GENERIC

    fun lightSensor(): LightSensorInfo? =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?.let { LightSensorInfo(it.name, it.resolution, it.maximumRange) }

    @Suppress("DEPRECATION") // the ResolveInfoFlags overload is API 33+
    private fun resolve(intent: Intent): List<String> =
        context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val A18_MODEL = "CPH2591"
    }
}
