package io.github.anbu00001.nocturne.core.reflect

import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.ist
import io.github.anbu00001.nocturne.core.sleep.session
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneDownGapsTest {
    private val day = "2026-09-16"
    private val config = SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS)

    private fun at(time: String) = ist(day, time)

    @Test
    fun `a lock-screen glance or an alarm leaves the phone down, an unlock or a call picks it up`() {
        val quiet = listOf(
            session(at("09:00"), 120),
            session(at("09:40"), 5, unlocked = false),
            session(at("10:00"), 60, unlocked = false, trigger = WakeTrigger.ALARM),
            session(at("11:00"), 60),
        )
        assertEquals(listOf(PhoneDownGap(at("09:02"), at("11:00"))), PhoneDownGaps.find(quiet, emptyList(), config))

        val call = quiet + session(at("10:00"), 600, unlocked = false, trigger = WakeTrigger.CALL)
        assertEquals(emptyList(), PhoneDownGaps.find(call, emptyList(), config)) // 58 min and 50 min
        assertEquals(
            listOf(PhoneDownGap(at("09:02"), at("10:00")), PhoneDownGap(at("10:10"), at("11:00"))),
            PhoneDownGaps.find(call, emptyList(), config, minGapMs = 30 * LocalClock.MINUTE_MS),
        )
    }

    @Test
    fun `a screen left to time out was put down at its last touch`() {
        // Unlocked at 13:00, last touched at 13:01, dark at 13:31 when the 30 min timeout ran out.
        val sessions = listOf(session(at("13:00"), 31 * 60, activeSeconds = 60), session(at("15:00"), 60))
        assertEquals(listOf(PhoneDownGap(at("13:01"), at("15:00"))), PhoneDownGaps.find(sessions, emptyList(), config))
        // Woken again within 2 min of going dark: the person was still there.
        val rewoken = listOf(session(at("13:00"), 31 * 60, activeSeconds = 60), session(at("13:32"), 5, unlocked = false), session(at("15:00"), 60))
        assertEquals(listOf(PhoneDownGap(at("13:31"), at("15:00"))), PhoneDownGaps.find(rewoken, emptyList(), config))
    }

    @Test
    fun `gaps touching sleep or the evening window, and the unfinished stretch after the last use, are left out`() {
        val sessions = listOf(session(at("08:00"), 60), session(at("10:00"), 60), session(at("12:00"), 60), session(at("14:00"), 60))
        assertEquals(3, PhoneDownGaps.find(sessions, emptyList(), config).size)
        val sleep = at("06:00") until at("09:30")
        assertEquals(
            listOf(PhoneDownGap(at("10:01"), at("12:00")), PhoneDownGap(at("12:01"), at("14:00"))),
            PhoneDownGaps.find(sessions, listOf(sleep), config),
        )
        // Touching the window by a minute is enough to leave a gap out; an empty range leaves nothing out.
        val window = at("13:59") until at("23:00")
        assertEquals(listOf(PhoneDownGap(at("10:01"), at("12:00"))), PhoneDownGaps.find(sessions, listOf(sleep, window), config))
        assertEquals(3, PhoneDownGaps.find(sessions, listOf(at("11:00") until at("11:00")), config).size)
    }

    @Test
    fun `an unlock inside a longer one does not open a gap from the earlier end`() {
        val sessions = listOf(session(at("09:00"), 2 * 3600), session(at("09:30"), 60), session(at("12:00"), 60))
        assertEquals(listOf(PhoneDownGap(at("11:00"), at("12:00"))), PhoneDownGaps.find(sessions, emptyList(), config))
    }
}
