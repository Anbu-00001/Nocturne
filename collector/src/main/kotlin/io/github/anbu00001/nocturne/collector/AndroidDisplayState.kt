package io.github.anbu00001.nocturne.collector

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import io.github.anbu00001.nocturne.core.light.WarmFilter
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.Instant
import java.time.ZoneId

/** What the display looks like right now; each value null when the phone would not say. */
data class DisplaySnapshot(
    val brightnessSetting: Int?,
    val darkUi: Boolean?,
    val warmFilter: Boolean?,
)

/**
 * Reads the display state the screen-light model needs (spec §6.1). With auto brightness on, ColorOS writes the
 * level it applies into SCREEN_BRIGHTNESS (seen on the A18: setting 13, `dumpsys display` brightness 13.0).
 */
class AndroidDisplayState(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {

    fun snapshot(): DisplaySnapshot = DisplaySnapshot(
        brightnessSetting = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull(),
        darkUi = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        warmFilter = warmFilter(),
    )

    private fun warmFilter(): Boolean? {
        val ts = now()
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(ts)).totalSeconds / 60
        WarmFilter.colorOs(system(COLOROS_ENABLED), system(COLOROS_BEGIN), system(COLOROS_END), LocalClock.minuteOfDay(ts, offset))
            ?.let { return it }
        // AOSP Night Light is a hidden Settings.Secure key, which apps targeting Android 12+ may be refused: then unknown.
        return runCatching { Settings.Secure.getInt(context.contentResolver, NIGHT_DISPLAY_ACTIVATED) == 1 }.getOrNull()
    }

    private fun system(key: String): String? = runCatching { Settings.System.getString(context.contentResolver, key) }.getOrNull()

    private companion object {
        const val COLOROS_ENABLED = "oplus_customize_eye_protect_enable"
        const val COLOROS_BEGIN = "oplus_customize_eye_protect_timer_begin_time"
        const val COLOROS_END = "oplus_customize_eye_protect_timer_end_time"
        const val NIGHT_DISPLAY_ACTIVATED = "night_display_activated"
    }
}
