package io.github.anbu00001.nocturne.core.mind

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MindTest {
    private val d0 = LocalDate.parse("2026-11-01")

    @Test
    fun `a check-in survives its trip through one integer, with and without tension`() {
        assertEquals(CheckIn(4, 2, 5), CheckIn.ofRating(CheckIn(4, 2, 5).rating))
        assertEquals(CheckIn(1, 5), CheckIn.ofRating(CheckIn(1, 5).rating))
        assertEquals(150, CheckIn(1, 5).rating)
        // A gap card's rating (1 to 4) is not a check-in.
        assertNull(CheckIn.ofRating(3))
        assertNull(CheckIn.ofRating(null))
        assertFailsWith<IllegalArgumentException> { CheckIn(0, 3) }
    }

    @Test
    fun `a tap before 04 00 is about the day that is ending`() {
        val ist = 330
        // 2026-11-11 01:00 IST is 2026-11-10 19:30 UTC.
        val oneAm = LocalDate.parse("2026-11-10").toEpochDay() * 86_400_000L + 19 * 3_600_000L + 30 * 60_000L
        assertEquals(LocalDate.parse("2026-11-10"), CheckIn.dayOf(oneAm, ist))
        assertEquals(LocalDate.parse("2026-11-11"), CheckIn.dayOf(oneAm + 3 * 3_600_000L, ist))
        assertEquals(LocalDate.parse("2026-11-10"), CheckIn.dayOf(CheckIn.dayStartUtc(LocalDate.parse("2026-11-10"), ist), ist))
    }

    @Test
    fun `onset jumps compare only nights that follow each other`() {
        val onsets = (0..8).associate { d0.plusDays(it.toLong()) to if (it % 2 == 0) 700.0 else 850.0 } + (d0.plusDays(20) to 100.0)
        val jumps = assertNotNull(OnsetJumps.of(onsets))
        assertEquals(8, jumps.pairs)
        assertEquals(150.0, jumps.medianJumpMinutes)
        assertEquals(8, jumps.overTwoHours)
        assertNull(OnsetJumps.of(onsets.filterKeys { it < d0.plusDays(7) }))
    }

    @Test
    fun `an alarm group gives the median count and span of mornings that had alarms`() {
        val minute = 60_000L
        val group = assertNotNull(
            AlarmLadder.group(mapOf(d0 to listOf(0L, 30 * minute, 255 * minute), d0.plusDays(1) to listOf(5 * minute), d0.plusDays(2) to emptyList()), nights = 5),
        )
        assertEquals(AlarmGroup(nights = 5, mornings = 2, medianAlarms = 2.0, medianSpanMinutes = 127.5), group)
        assertNull(AlarmLadder.group(emptyMap(), nights = 3))
    }

    private fun day(i: Int, energy: Int, sleep: Double?, pleasant: Int = 3, music: Double? = 0.0) =
        MindDay(d0.plusDays(i.toLong()), CheckIn(pleasant, energy), sleep, music, pickups = null)

    @Test
    fun `links are withheld under 28 days with a check-in and sleep, and say how many there are`() {
        val report = MoodLinks.of((0 until 12).map { day(it, 3, 7.0) } + day(40, 3, null))
        assertTrue(report.withheld)
        assertEquals(12, report.days)
        assertNull(report.sleepEnergy)
    }

    @Test
    fun `a link is the slope of day-to-day differences, so a steady drift in both adds nothing`() {
        // Energy follows sleep exactly, one point per hour, alternating 6 h and 8 h: slope 1 with no spread.
        val exact = (0 until 30).map { day(it, if (it % 2 == 0) 2 else 4, if (it % 2 == 0) 6.0 else 8.0) }
        val link = assertNotNull(MoodLinks.of(exact).sleepEnergy)
        assertEquals(29, link.pairs)
        assertEquals(1.0, link.slope, 1e-9)
        assertEquals(1.0, link.low, 1e-9)
        // Music never changed, so there is nothing to compare it with.
        assertNull(MoodLinks.of(exact).musicPleasant)
        // By hand, 28 differences alternating (+1 h, +1 point) and (-1 h, 0 points): slope 14 / 28 = 0.5, residual 28 * 0.25,
        // standard error sqrt(7 / 27 / 28) = 0.09623, so the interval is 0.5 minus and plus 0.19245. The rating climbs past
        // the 1 to 5 scale, so it is handed to the link directly instead of through a check-in.
        val days = (0 until 29).map { day(it, 3, if (it % 2 == 1) 8.0 else 7.0) }
        val climbing = days.associate { it.day to ((it.day.toEpochDay() - d0.toEpochDay() + 1) / 2).toDouble() }
        val half = assertNotNull(MoodLinks.link(days, { it.sleepHours }, { climbing.getValue(it.day) }))
        assertEquals(28, half.pairs)
        assertEquals(0.5, half.slope, 1e-9)
        assertEquals(0.5 - 0.19245, half.low, 1e-4)
        assertEquals(0.5 + 0.19245, half.high, 1e-4)
    }
}
