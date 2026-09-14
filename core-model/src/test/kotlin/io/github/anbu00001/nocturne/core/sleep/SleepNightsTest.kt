package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepNightsTest {

    private fun reported(date: LocalDate, onset: Long, wake: Long) = NightSleep(
        date = date,
        offsetMinutes = IST,
        inferred = null,
        report = SleepReport(date, onset, wake),
        onsetTs = onset,
        wakeTs = wake,
        confidence = 1.0,
        source = SleepSource.USER_REPORTED,
        interruptions = 0,
    )

    /** [count] nights from [from], each asleep 01:00 to 09:00. */
    private fun steadyNights(from: String, count: Int): List<NightSleep> = (0 until count).map { i ->
        val date = LocalDate.parse(from).plusDays(i.toLong())
        val morning = date.plusDays(1).toString()
        reported(date, ist(morning, "01:00"), ist(morning, "09:00"))
    }

    @Test
    fun `seven or more nights give a window from 3 h before habitual onset until habitual wake`() {
        val window = SleepNights.windowFor(LocalDate.parse("2026-09-21"), steadyNights("2026-09-07", 14))
        assertTrue(window.personalised)
        assertEquals(14, window.nights)
        assertEquals(EveningWindow(22 * 60, 9 * 60), window.window)
    }

    @Test
    fun `fewer than seven nights keep the provisional window`() {
        assertEquals(HabitualWindow.PROVISIONAL, SleepNights.windowFor(LocalDate.parse("2026-09-14"), steadyNights("2026-09-07", 6)))
    }

    @Test
    fun `before four weeks of history the earliest nights stand in for the weeks before them`() {
        val window = SleepNights.windowFor(LocalDate.parse("2026-09-08"), steadyNights("2026-09-05", 10))
        assertTrue(window.personalised)
        assertEquals(10, window.nights)
    }

    @Test
    fun `the personal prior is the median and scaled MAD of earlier nights, with a floor`() {
        val prior = assertNotNull(personalPrior(steadyNights("2026-09-01", 7), SleepConfig()))
        assertEquals(13 * 60.0, prior.onsetMinutesSinceNoon)
        assertEquals(SleepConfig().minOnsetSdMin, prior.onsetSdMin)
        assertEquals(8 * 60.0, prior.durationMin)
        assertNull(personalPrior(steadyNights("2026-09-01", 6), SleepConfig()))
    }

    @Test
    fun `reports teach a corrective offset that moves the nights without one`() {
        val dates = (0L until 4).map { LocalDate.parse("2026-09-05").plusDays(it) }
        val inputs = dates.map { date ->
            NightInput(
                date, IST,
                busy(ist(date.toString(), "21:00"), ist(date.toString(), "23:30")) +
                    busy(ist(date.plusDays(1).toString(), "07:30"), ist(date.plusDays(1).toString(), "11:30")),
            )
        }
        // The user fell asleep 20 min after the phone went quiet on three nights.
        val reports = dates.take(3).associateWith { SleepReport(it, ist(it.toString(), "23:51:30"), ist(it.plusDays(1).toString(), "07:30")) }
        val nights = SleepNights.infer(emptyList(), inputs, reports)

        assertEquals(SleepSource.USER_REPORTED, nights[0].source)
        val unreported = nights[3]
        assertEquals(SleepSource.INFERRED, unreported.source)
        assertEquals(ist("2026-09-08", "23:31:30"), assertNotNull(unreported.inferred).onsetTs)
        assertEquals(ist("2026-09-08", "23:51:30"), unreported.onsetTs)
        assertEquals(ist("2026-09-09", "07:30"), unreported.wakeTs)
    }

    @Test
    fun `recomputing from any night, with earlier nights as context, matches recomputing all of them`() {
        val inputs = (0 until 12).map { i ->
            val date = LocalDate.parse("2026-09-01").plusDays(i.toLong())
            val bed = ist(date.toString(), "23:00") + i * 7 * LocalClock.MINUTE_MS
            NightInput(
                date, IST,
                busy(bed - 2 * LocalClock.HOUR_MS, bed) + busy(bed + 8 * LocalClock.HOUR_MS, bed + 11 * LocalClock.HOUR_MS),
            )
        }
        val full = SleepNights.infer(emptyList(), inputs, emptyMap())
        for (k in 1 until inputs.size) {
            assertEquals(full.drop(k), SleepNights.infer(full.take(k), inputs.drop(k), emptyMap()), "recomputed from night $k")
        }
        assertTrue(full.all { it.inferred != null })
    }

    @Test
    fun `sessions are grouped into nights from 17 00 to 17 00`() {
        val sessions = listOf(
            OffsetSession(session(ist("2026-09-05", "16:59"), 10), IST),
            OffsetSession(session(ist("2026-09-05", "17:00"), 10), IST),
            OffsetSession(session(ist("2026-09-06", "16:59"), 10), IST),
        )
        val inputs = SleepNights.inputs(sessions)
        assertEquals(listOf(LocalDate.parse("2026-09-04"), LocalDate.parse("2026-09-05")), inputs.map { it.date })
        assertEquals(2, inputs[1].sessions.size)
    }

    @Test
    fun `charging samples become intervals, capped where the samples stop`() {
        val m = LocalClock.MINUTE_MS
        val intervals = SleepNights.chargingIntervals(listOf(0L to true, 15 * m to true, 30 * m to false, 120 * m to true))
        assertEquals(listOf(0L..30 * m, 120 * m..150 * m), intervals)
    }
}
