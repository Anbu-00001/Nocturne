package io.github.anbu00001.nocturne.collector

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.telecom.TelecomManager
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.glance.UnlockEvidence

/** Adds this phone's actual home app, clock and dialer to the classifier's default package sets. */
class DeviceProfile(private val context: Context) {

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

    @Suppress("DEPRECATION") // the ResolveInfoFlags overload is API 33+
    private fun resolve(intent: Intent): List<String> =
        context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
