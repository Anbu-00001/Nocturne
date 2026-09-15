package io.github.anbu00001.nocturne.core.light

import io.github.anbu00001.nocturne.core.time.EveningWindow

/**
 * Whether a warm display filter is on, from what the ROM exposes in its settings.
 *
 * ColorOS 15 keeps eye comfort in Settings.System (read from an A18 over adb, 2026-09-15):
 * `oplus_customize_eye_protect_enable` = 1, `oplus_customize_eye_protect_timer_begin_time` = 22:00,
 * `oplus_customize_eye_protect_timer_end_time` = 07:00, `eyeprotect_display_cct` = 2700. Whether the flag means
 * "scheduled" or "on now" is not documented, so when a schedule is present it must also cover the minute.
 * Keys Android itself does not define stay readable to apps (AOSP SettingsProvider.checkReadableAnnotation),
 * so reading them needs no permission.
 */
object WarmFilter {

    /** Null when the flag is absent: not a ColorOS phone, or ColorOS renamed it. */
    fun colorOs(enabled: String?, scheduleBegin: String?, scheduleEnd: String?, minuteOfDay: Int): Boolean? {
        val flag = enabled?.trim() ?: return null
        if (flag != "1") return false
        val begin = clockMinute(scheduleBegin)
        val end = clockMinute(scheduleEnd)
        if (begin == null || end == null || begin == end) return true
        return EveningWindow(begin, end).contains(minuteOfDay)
    }

    /** "22:00" is 1320; anything that is not a valid 24-hour clock time is null. */
    internal fun clockMinute(text: String?): Int? {
        val match = Regex("""(\d{1,2}):(\d{2})""").matchEntire(text?.trim() ?: return null) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour < 24 && minute < 60) hour * 60 + minute else null
    }
}
