package io.github.anbu00001.nocturne.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §10: a DST transition and a timezone change must not corrupt "after 23:00" queries. */
class TimeHandlingTest {

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()
    private val halfPastEleven = 23 * 60 + 30

    @Test
    fun `23-30 stays 23-30 across the October DST change when the offset is captured per event`() {
        val london = ZoneTimeline(listOf(ZoneChange(0, "Europe/London")))
        val beforeChange = utc("2026-10-24T22:30:00Z") // BST
        val afterChange = utc("2026-10-25T23:30:00Z") // GMT
        assertEquals(60, london.offsetMinutesAt(beforeChange))
        assertEquals(0, london.offsetMinutesAt(afterChange))
        for (t in listOf(beforeChange, afterChange)) {
            assertEquals(halfPastEleven, LocalClock.minuteOfDay(t, london.offsetMinutesAt(t)))
        }
        // The §11 pitfall: rendering an old event with today's offset moves it out of the window.
        assertEquals(22 * 60 + 30, LocalClock.minuteOfDay(beforeChange, london.offsetMinutesAt(afterChange)))
    }

    @Test
    fun `23-30 stays 23-30 across the March DST change`() {
        val london = ZoneTimeline(listOf(ZoneChange(0, "Europe/London")))
        for (t in listOf(utc("2027-03-27T23:30:00Z"), utc("2027-03-28T22:30:00Z"))) {
            assertEquals(halfPastEleven, LocalClock.minuteOfDay(t, london.offsetMinutesAt(t)))
        }
    }

    @Test
    fun `a trip changes the offset only for events after the change`() {
        val landed = utc("2026-11-01T08:00:00Z")
        // Deliberately unsorted: the timeline must not depend on insertion order.
        val trip = ZoneTimeline(listOf(ZoneChange(landed, "Europe/London"), ZoneChange(0, "Asia/Kolkata")))
        val lastNightInIndia = utc("2026-10-31T18:00:00Z")
        val firstNightInLondon = utc("2026-11-02T23:30:00Z")
        assertEquals(330, trip.offsetMinutesAt(lastNightInIndia))
        assertEquals(0, trip.offsetMinutesAt(firstNightInLondon))
        assertEquals(halfPastEleven, LocalClock.minuteOfDay(lastNightInIndia, 330))
        assertEquals(halfPastEleven, LocalClock.minuteOfDay(firstNightInLondon, 0))
    }

    @Test
    fun `instants before the first recorded zone use the earliest zone`() {
        val t = ZoneTimeline(listOf(ZoneChange(utc("2026-01-01T00:00:00Z"), "Asia/Kolkata")))
        assertEquals(330, t.offsetMinutesAt(utc("2025-06-01T00:00:00Z")))
    }

    @Test
    fun `an empty zone log is rejected`() {
        assertFailsWith<IllegalArgumentException> { ZoneTimeline(emptyList()) }
    }

    @Test
    fun `early-morning use belongs to the previous evening's night`() {
        val ist = 330
        fun local(iso: String) = LocalDateTime.parse(iso).toInstant(ZoneOffset.ofTotalSeconds(ist * 60)).toEpochMilli()
        assertEquals(LocalDate.of(2026, 9, 14), LocalClock.nightOf(local("2026-09-15T01:30:00"), ist))
        assertEquals(LocalDate.of(2026, 9, 14), LocalClock.nightOf(local("2026-09-14T12:00:00"), ist))
        assertEquals(LocalDate.of(2026, 9, 13), LocalClock.nightOf(local("2026-09-14T11:59:59"), ist))
    }

    @Test
    fun `minuteOfDay wraps correctly for negative offsets`() {
        assertEquals(19 * 60, LocalClock.minuteOfDay(utc("2026-09-15T00:00:00Z"), -300))
    }

    @Test
    fun `evening window for a 02-00 sleeper starts at 23-00, so 21-00 is not flagged`() {
        val w = EveningWindow.fromSleepOnset(onsetMinute = 2 * 60, wakeMinute = 10 * 60)
        assertEquals(23 * 60, w.startMinute)
        assertFalse(w.contains(21 * 60))
        assertTrue(w.contains(23 * 60))
        assertTrue(w.contains(3 * 60))
        assertFalse(w.contains(10 * 60))
    }

    @Test
    fun `provisional evening window runs 21-00 to 07-00, half-open`() {
        val w = EveningWindow.PROVISIONAL
        assertEquals(EveningWindow(21 * 60, 7 * 60), w)
        assertTrue(w.contains(21 * 60))
        assertTrue(w.contains(0))
        assertTrue(w.contains(6 * 60 + 59))
        assertFalse(w.contains(7 * 60))
        assertFalse(w.contains(20 * 60 + 59))
    }

    @Test
    fun `a window that does not cross midnight`() {
        val w = EveningWindow(60, 300)
        assertTrue(w.contains(60))
        assertTrue(w.contains(299))
        assertFalse(w.contains(300))
        assertFalse(w.contains(0))
    }
}
