package io.github.anbu00001.nocturne.collector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import io.github.anbu00001.nocturne.data.PowerSampleEntity

fun interface PowerSource {
    fun sample(at: Long): PowerSampleEntity?

    companion object {
        val NONE = PowerSource { null }
    }
}

/**
 * Reads the sticky battery broadcast. No receiver is registered: ACTION_POWER_CONNECTED cannot be declared
 * in the manifest for apps targeting API 26+, and a live receiver would need a live process, so charging
 * is sampled on each harvest instead. A charging phone leaves Doze, so those runs come every 15 minutes.
 */
class AndroidPowerSource(private val context: Context) : PowerSource {
    override fun sample(at: Long): PowerSampleEntity? {
        val battery = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return PowerSampleEntity(
            timestamp = at,
            charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
        )
    }
}
