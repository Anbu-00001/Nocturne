package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val IST = 330

/** Wall-clock time in India on [date], as epoch millis. */
internal fun ist(date: String, time: String): Long =
    LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofTotalSeconds(IST * 60)).toEpochMilli()

internal fun session(
    startTs: Long,
    seconds: Long,
    unlocked: Boolean = true,
    trigger: WakeTrigger = WakeTrigger.UNKNOWN,
    activeSeconds: Long = seconds,
): NightSession {
    val kind = when {
        !unlocked && seconds < 15 -> SessionKind.GLANCE_NO_UNLOCK
        !unlocked -> SessionKind.LOCKED_EXTENDED
        seconds <= 180 -> SessionKind.SHORT
        else -> SessionKind.EXTENDED
    }
    return NightSession(startTs, startTs + seconds * 1000, startTs + activeSeconds * 1000, kind, unlocked, trigger)
}

/** A session of [seconds] every [everyMinutes] from [from] up to and including [to]. */
internal fun busy(from: Long, to: Long, everyMinutes: Long = 10, seconds: Long = 90): List<NightSession> =
    generateSequence(from) { it + everyMinutes * LocalClock.MINUTE_MS }.takeWhile { it <= to }.map { session(it, seconds) }.toList()

internal fun night(date: String, sessions: List<NightSession>) = NightInput(LocalDate.parse(date), IST, sessions.sortedBy { it.startTs })

class SleepInferenceTest {
    private val inference = SleepInference()
    private val thirtyMinuteTimeout = SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS)

    private fun clock(ts: Long) = LocalDateTime.ofEpochSecond(ts / 1000, 0, ZoneOffset.ofTotalSeconds(IST * 60)).toLocalTime()

    private fun assertNear(expected: Long, actual: Long, toleranceMinutes: Long) = assertTrue(
        abs(expected - actual) <= toleranceMinutes * LocalClock.MINUTE_MS,
        "expected about ${clock(expected)}, was ${clock(actual)}",
    )

    @Test
    fun `a quiet night runs from the end of the last evening session to the first morning one`() {
        val sessions = busy(ist("2026-09-05", "21:00"), ist("2026-09-05", "23:30")) +
            busy(ist("2026-09-06", "07:30"), ist("2026-09-06", "11:30"))
        val s = assertNotNull(inference.infer(night("2026-09-05", sessions), prior = null))
        assertEquals(ist("2026-09-05", "23:31:30"), s.onsetTs)
        assertEquals(ist("2026-09-06", "07:30"), s.wakeTs)
        assertEquals(0, s.interruptions)
        assertTrue(s.confidence >= 0.6, "${s.confidence}")
    }

    @Test
    fun `brief night-time checks stay inside one night and count as interruptions`() {
        val sessions = busy(ist("2026-09-06", "21:05"), ist("2026-09-06", "23:55"), seconds = 120) +
            session(ist("2026-09-07", "03:00"), 1, unlocked = false) +
            session(ist("2026-09-07", "04:15"), 40) +
            session(ist("2026-09-07", "04:16:10"), 1, unlocked = false) +
            busy(ist("2026-09-07", "08:22"), ist("2026-09-07", "12:00"))
        val s = assertNotNull(inference.infer(night("2026-09-06", sessions), prior = null))
        assertEquals(ist("2026-09-06", "23:57"), s.onsetTs)
        assertEquals(ist("2026-09-07", "08:22"), s.wakeTs)
        assertEquals(3, s.interruptions)
    }

    @Test
    fun `hours of use before dawn end the night instead of being absorbed into it`() {
        val sessions = busy(ist("2026-09-07", "21:02"), ist("2026-09-08", "01:02"), seconds = 60) +
            session(ist("2026-09-08", "04:15"), 44, trigger = WakeTrigger.ALARM) +
            busy(ist("2026-09-08", "04:25"), ist("2026-09-08", "07:49"), everyMinutes = 5, seconds = 60) +
            busy(ist("2026-09-08", "09:31"), ist("2026-09-08", "12:01"))
        val s = assertNotNull(inference.infer(night("2026-09-07", sessions), prior = null))
        assertEquals(ist("2026-09-08", "01:03"), s.onsetTs)
        assertNear(ist("2026-09-08", "04:15"), s.wakeTs, toleranceMinutes = 15)
        assertTrue(s.confidence < 0.8, "a 3 h night is less plausible: ${s.confidence}")
    }

    @Test
    fun `wakes the phone caused and nobody touched neither interrupt nor end the night`() {
        val sessions = busy(ist("2026-09-08", "21:00"), ist("2026-09-08", "23:00")) +
            session(ist("2026-09-09", "01:00"), 10, unlocked = false, trigger = WakeTrigger.NOTIFICATION, activeSeconds = 0) +
            session(ist("2026-09-09", "02:30"), 10, unlocked = false, trigger = WakeTrigger.NOTIFICATION, activeSeconds = 0) +
            session(ist("2026-09-09", "06:30"), 10, unlocked = false, trigger = WakeTrigger.ALARM, activeSeconds = 0) +
            session(ist("2026-09-09", "06:45"), 30, unlocked = false, trigger = WakeTrigger.ALARM, activeSeconds = 25) +
            busy(ist("2026-09-09", "06:50"), ist("2026-09-09", "10:00"), everyMinutes = 5)
        val s = assertNotNull(inference.infer(night("2026-09-08", sessions), prior = null))
        assertEquals(ist("2026-09-08", "23:01:30"), s.onsetTs)
        assertEquals(ist("2026-09-09", "06:45"), s.wakeTs)
        assertEquals(0, s.interruptions)
    }

    @Test
    fun `a screen left to time out puts onset at the last activity, not at screen-off`() {
        val sessions = busy(ist("2026-09-10", "21:00"), ist("2026-09-11", "00:20")) +
            session(ist("2026-09-11", "00:30"), seconds = 61 * 60, activeSeconds = 31 * 60) +
            busy(ist("2026-09-11", "08:00"), ist("2026-09-11", "12:00"))
        val s = assertNotNull(SleepInference(thirtyMinuteTimeout).infer(night("2026-09-10", sessions), prior = null))
        assertEquals(ist("2026-09-11", "01:01"), s.onsetTs)
    }

    @Test
    fun `a timeout followed at once by a new wake means the user was still awake`() {
        // The A18, 12 Sep: a browser page timed out at 03:25:47 and was back on 4 s later.
        val first = ist("2026-09-12", "02:55:02")
        val second = ist("2026-09-12", "03:25:51")
        val sessions = busy(ist("2026-09-11", "21:00"), ist("2026-09-12", "02:50")) +
            NightSession(first, ist("2026-09-12", "03:25:47"), first + 1_000, SessionKind.EXTENDED, true, WakeTrigger.UNKNOWN) +
            NightSession(second, ist("2026-09-12", "03:56:45"), second + 2_000, SessionKind.EXTENDED, true, WakeTrigger.UNKNOWN) +
            busy(ist("2026-09-12", "10:25"), ist("2026-09-12", "12:00"))
        val s = assertNotNull(SleepInference(thirtyMinuteTimeout).infer(night("2026-09-11", sessions), prior = null))
        assertEquals(ist("2026-09-12", "03:26:45"), s.onsetTs)
        val firstMark = marksOf(sessions, thirtyMinuteTimeout).single { it.startTs == first }
        assertEquals(ist("2026-09-12", "03:25:47"), firstMark.activeEndTs)
    }

    @Test
    fun `with no evening activity the onset is unanchored and confidence drops`() {
        val quiet = assertNotNull(
            inference.infer(night("2026-09-13", busy(ist("2026-09-14", "07:00"), ist("2026-09-14", "12:00"))), prior = null),
        )
        assertFalse(quiet.onsetAnchored)
        assertTrue(quiet.confidence <= 0.5, "${quiet.confidence}")
    }

    @Test
    fun `a night with charging records but off the charger is less certain than one on it`() {
        val base = night(
            "2026-09-05",
            busy(ist("2026-09-05", "21:00"), ist("2026-09-05", "23:30")) + busy(ist("2026-09-06", "07:30"), ist("2026-09-06", "11:30")),
        )
        val on = assertNotNull(inference.infer(base.copy(charging = listOf(ist("2026-09-05", "23:00")..ist("2026-09-06", "08:00"))), null))
        val off = assertNotNull(inference.infer(base.copy(charging = listOf(ist("2026-09-06", "12:00")..ist("2026-09-06", "13:00"))), null))
        assertTrue(on.confidence > off.confidence)
    }

    @Test
    fun `a night the history does not reach back to, or one still under way, gets no estimate`() {
        val base = night(
            "2026-09-05",
            busy(ist("2026-09-05", "21:00"), ist("2026-09-05", "23:30")) + busy(ist("2026-09-06", "07:30"), ist("2026-09-06", "11:30")),
        )
        assertNull(inference.infer(base.copy(dataFromTs = ist("2026-09-05", "22:00")), null))
        assertNull(inference.infer(base.copy(dataToTs = ist("2026-09-06", "01:30")), null))
        assertEquals(ist("2026-09-06", "07:30"), assertNotNull(inference.infer(base.copy(dataToTs = ist("2026-09-06", "07:45")), null)).wakeTs)
    }
}
