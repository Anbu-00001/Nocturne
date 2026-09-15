package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoSleepTest {
    private val inference = SleepInference(SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS))

    /** This phone's user: usually asleep around 03:10, give or take 90 min, for about 9 h. */
    private val latePrior = PersonalPrior(
        onsetMinutesSinceNoon = 15 * 60 + 10.0,
        onsetSdMin = 89.0,
        durationMin = 9 * 60.0,
        durationSdMin = 60.0,
        nights = 7,
    )

    @Test
    fun `an evening away from the phone followed by use all night is no sleep for a late sleeper`() {
        // The night of 14 Sept on the A18: at the laptop 21:30 to 00:30, then on the phone until morning.
        val sessions = busy(ist("2026-09-14", "18:00"), ist("2026-09-14", "21:30")) +
            busy(ist("2026-09-15", "00:30"), ist("2026-09-15", "07:00"), everyMinutes = 20)
        val s = assertNotNull(inference.infer(night("2026-09-14", sessions).copy(dataToTs = ist("2026-09-15", "07:00")), latePrior))
        assertTrue(s.noSleep, "log odds ${s.noSleepLogOdds}")
        assertTrue(s.confidence < 0.1, "a sleepless night's sleep estimate carries little confidence: ${s.confidence}")
    }

    @Test
    fun `an ordinary night is sleep by a wide margin, with or without a personal prior`() {
        val sessions = busy(ist("2026-09-05", "22:00"), ist("2026-09-06", "02:50")) + busy(ist("2026-09-06", "12:00"), ist("2026-09-06", "15:00"))
        for (prior in listOf(null, latePrior)) {
            val s = assertNotNull(inference.infer(night("2026-09-05", sessions), prior))
            assertFalse(s.noSleep)
            assertTrue(s.noSleepLogOdds < -5, "log odds ${s.noSleepLogOdds} with prior $prior")
        }
    }

    @Test
    fun `a genuinely short night near the usual onset stays sleep`() {
        val sessions = busy(ist("2026-09-07", "21:00"), ist("2026-09-08", "01:00")) + busy(ist("2026-09-08", "04:15"), ist("2026-09-08", "12:00"))
        val s = assertNotNull(inference.infer(night("2026-09-07", sessions), latePrior))
        assertFalse(s.noSleep, "log odds ${s.noSleepLogOdds}, chose ${clock(s.onsetTs)} to ${clock(s.wakeTs)}")
    }

    private fun clock(ts: Long) = java.time.Instant.ofEpochMilli(ts).atOffset(java.time.ZoneOffset.ofTotalSeconds(IST * 60)).toLocalTime()

    @Test
    fun `after a night awake, the morning sleep is found once the history reaches it`() {
        val sessions = busy(ist("2026-09-14", "18:00"), ist("2026-09-14", "21:30")) +
            busy(ist("2026-09-15", "00:30"), ist("2026-09-15", "08:00"), everyMinutes = 20) +
            busy(ist("2026-09-15", "14:00"), ist("2026-09-15", "16:00"))
        val s = assertNotNull(inference.infer(night("2026-09-14", sessions), latePrior))
        assertFalse(s.noSleep, "log odds ${s.noSleepLogOdds}")
        // The last session before the morning sleep starts at 07:50 and lasts 90 s.
        assertTrue(abs(s.onsetTs - ist("2026-09-15", "07:51:30")) <= 5 * LocalClock.MINUTE_MS, "onset ${clock(s.onsetTs)}")
        assertEquals(ist("2026-09-15", "14:00"), s.wakeTs)
    }

    @Test
    fun `a night the user says was sleepless has no times, feeds no prior and teaches no offset`() {
        val dates = (0L until 4).map { LocalDate.parse("2026-09-05").plusDays(it) }
        val inputs = dates.map { date ->
            NightInput(
                date, IST,
                busy(ist(date.toString(), "21:00"), ist(date.toString(), "23:30")) +
                    busy(ist(date.plusDays(1).toString(), "07:30"), ist(date.plusDays(1).toString(), "11:30")),
            )
        }
        // Three nights where the user fell asleep 20 min after the phone went quiet, and one they did not sleep.
        val reports = dates.take(3).associateWith { SleepReport(it, ist(it.toString(), "23:51:30"), ist(it.plusDays(1).toString(), "07:30")) } +
            (dates[3] to SleepReport(dates[3], null, null))
        val nights = SleepNights.infer(emptyList(), inputs, reports)

        val sleepless = nights[3]
        assertTrue(sleepless.noSleep)
        assertEquals(SleepSource.USER_REPORTED, sleepless.source)
        assertNull(sleepless.onsetTs)
        assertFalse(sleepless.usable(SleepConfig()))
        assertEquals(20 * LocalClock.MINUTE_MS, CorrectiveOffsets.from(nights, SleepConfig()).onsetMs)
        assertEquals(3, CorrectiveOffsets.from(nights, SleepConfig()).nights)
    }

    @Test
    fun `lnGamma matches known values`() {
        assertEquals(ln(24.0), lnGamma(5.0), 1e-12)
        assertEquals(ln(0.75 * sqrt(PI)), lnGamma(2.5), 1e-12)
        assertEquals(ln(sqrt(PI)), lnGamma(0.5), 1e-12)
    }
}
