package io.github.anbu00001.nocturne.core.mind

import java.time.LocalDate

/**
 * The evening check-in notification, which the owner asked for on 10 Nov 2026 (a departure from spec §7's "no reminders",
 * recorded in docs/PHASES.md): silent, once a day, from 20:00, and only while today has no answer. It rides on the 15-minute
 * harvest rather than an alarm, so it lands between about 20:00 and 20:15; ColorOS stretches an inexact or "exact" alarm
 * set hours ahead by up to three quarters of the time left, and an alarm-clock alarm would show the alarm icon. After
 * [UNTIL_MINUTE] it is not posted, so a harvest that runs late cannot bring it at midnight.
 */
object CheckInReminder {
    const val FROM_MINUTE = 20 * 60
    const val UNTIL_MINUTE = 23 * 60

    /** [localMinute] is minutes after local midnight; [day] the check-in day ([CheckIn.dayOf]); [postedDay] the day it was last posted. */
    fun shouldPost(localMinute: Int, day: LocalDate, postedDay: LocalDate?, answered: Boolean, enabled: Boolean): Boolean =
        enabled && !answered && postedDay != day && localMinute in FROM_MINUTE until UNTIL_MINUTE
}
