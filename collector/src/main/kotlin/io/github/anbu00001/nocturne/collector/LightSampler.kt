package io.github.anbu00001.nocturne.collector

import io.github.anbu00001.nocturne.core.light.LuxWindow
import io.github.anbu00001.nocturne.data.LightSampleEntity

/**
 * The light service's logic without the service (spec §4.2): screen on starts a window, sensor events and a
 * periodic tick close windows, screen off keeps the cut-short one. Every call comes from one thread.
 */
class LightSampler(
    private val display: () -> DisplaySnapshot,
    private val brightnessMax: Int,
    private val write: (LightSampleEntity) -> Unit,
    private val window: LuxWindow = LuxWindow(),
) {
    val isSampling: Boolean get() = window.isRunning

    /** A second screen-on while already sampling (a missed screen-off) keeps the current window. */
    fun screenOn(ts: Long) {
        if (!window.isRunning) window.start(ts)
    }

    fun onLux(ts: Long, lux: Float) = record(window.onValue(ts, lux.toDouble()))

    fun tick(ts: Long) = record(window.tick(ts))

    fun screenOff(ts: Long) = record(window.stop(ts))

    private fun record(readings: List<LuxWindow.Reading>) {
        if (readings.isEmpty()) return
        val now = display()
        for (r in readings) {
            write(
                LightSampleEntity(
                    timestamp = r.startTs,
                    ambientLux = r.medianLux?.toFloat(),
                    screenBrightness = now.brightnessSetting?.let { (it.toFloat() / brightnessMax).coerceIn(0f, 1f) },
                    screenOn = true,
                    foregroundPackage = null,
                    durationMs = r.durationMs,
                    brightnessSetting = now.brightnessSetting,
                    darkUi = now.darkUi,
                    warmFilter = now.warmFilter,
                    sensorEvents = r.events,
                ),
            )
        }
    }
}
