package io.github.anbu00001.nocturne.core.digest

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WeekDigestTest {
    private val end = LocalDate.parse("2026-11-09")

    /** A night [daysBack] before [end], asleep from [onset] minutes after noon for [hours]. */
    private fun night(daysBack: Int, onset: Double, hours: Double, late: Map<String, Double> = emptyMap()) =
        DigestNight(end.minusDays(daysBack.toLong()), onset, hours * 60, lateAppMinutes = late)

    private fun steady(range: IntRange, onset: Double = 720.0, hours: Double = 7.0) = range.map { night(it, onset, hours) }

    @Test
    fun `the week is the last seven nights and the baseline the 28 before them`() {
        val digest = WeekDigest.of(steady(0..6, onset = 700.0) + steady(7..34, onset = 760.0) + steady(35..40, onset = 100.0), end)
        assertEquals(7, digest.week!!.nights)
        assertEquals(700.0, digest.week!!.medianOnsetMinute)
        assertEquals(28, digest.baseline!!.nights)
        assertEquals(760.0, digest.baseline!!.medianOnsetMinute)
    }

    @Test
    fun `nights after the end date are ignored`() {
        val later = DigestNight(end.plusDays(1), 100.0, 60.0)
        assertEquals(7, WeekDigest.of(steady(0..6) + later, end).week!!.nights)
    }

    @Test
    fun `spread is the sample standard deviation of onset, and medians ignore one extreme night`() {
        val onsets = listOf(700.0, 710.0, 720.0, 730.0, 740.0, 750.0, 1130.0)
        val week = WeekDigest.of(onsets.mapIndexed { i, onset -> night(i, onset, 7.0) }, end).week!!
        assertEquals(730.0, week.medianOnsetMinute)
        // Python: statistics.stdev([700, 710, 720, 730, 740, 750, 1130]) = 154.0254.
        assertEquals(154.0254, week.onsetSpreadMinutes!!, 0.0001)
    }

    @Test
    fun `short and long nights are counted on either side of 5 h and 9 h`() {
        val week = WeekDigest.of(listOf(4.1, 4.99, 5.0, 7.0, 8.99, 9.0, 10.6).mapIndexed { i, h -> night(i, 720.0, h) }, end).week!!
        assertEquals(2, week.shortNights)
        assertEquals(2, week.longNights)
        assertEquals(7.0 * 60, week.medianSleepMinutes)
    }

    @Test
    fun `a week with fewer than four timed nights has no summary, and sleepless nights are counted apart`() {
        val nights = steady(0..2) + DigestNight(end.minusDays(3), null, null, noSleep = true) + DigestNight(end.minusDays(4), null, null)
        assertNull(WeekDigest.of(nights, end).week)
        val four = WeekDigest.of(nights + night(5, 720.0, 7.0), end).week!!
        assertEquals(4, four.nights)
        assertEquals(1, four.noSleepNights)
    }

    @Test
    fun `a baseline needs seven timed nights`() {
        assertNull(WeekDigest.of(steady(0..12), end).baseline)
        assertNotNull(WeekDigest.of(steady(0..13), end).baseline)
    }

    @Test
    fun `the late app split compares nights with ten minutes or more of the app against the rest`() {
        val game = "com.example.game"
        val nights = (0..5).map { night(it, 810.0, 6.0, mapOf(game to 30.0, "launcher" to 400.0)) } +
            (6..12).map { night(it, 700.0, 8.0, mapOf(game to 9.9)) }
        val split = WeekDigest.of(nights, end, ignoredPackages = setOf("launcher")).lateApps.single()
        assertEquals(game, split.packageName)
        assertEquals(6, split.withNights)
        assertEquals(810.0, split.withMedianOnsetMinute)
        assertEquals(360.0, split.withMedianSleepMinutes)
        assertEquals(7, split.withoutNights)
        assertEquals(700.0, split.withoutMedianOnsetMinute)
    }

    @Test
    fun `no split when either side has fewer than five nights, and the next app is tried`() {
        val nights = (0..3).map { night(it, 810.0, 6.0, mapOf("a" to 90.0, "b" to 12.0)) } +
            (4..5).map { night(it, 800.0, 6.0, mapOf("b" to 12.0)) } +
            (6..12).map { night(it, 700.0, 8.0) }
        // "a" has the most minutes but only four nights; "b" has six.
        assertEquals(listOf("b"), WeekDigest.of(nights, end).lateApps.map { it.packageName })
        assertEquals(emptyList(), WeekDigest.of(nights.take(4) + steady(6..12), end).lateApps)
        // An app open late every night leaves nothing to compare it with.
        assertEquals(emptyList(), WeekDigest.of((0..12).map { night(it, 700.0, 7.0, mapOf("a" to 20.0)) }, end).lateApps)
    }

    @Test
    fun `the two apps with the most late minutes are shown, by minutes and not by how far apart their nights are`() {
        // "browser" has the most minutes and no gap at all, "game" a gap of two hours, "mail" the widest gap and the fewest minutes.
        val nights = (0..11).map { i ->
            val late = mapOf("browser" to if (i % 2 == 0) 60.0 else 0.0, "game" to if (i < 6) 30.0 else 0.0, "mail" to if (i < 5) 12.0 else 0.0)
            night(i, if (i < 6) 820.0 else 700.0, 7.0, late)
        }
        val splits = WeekDigest.of(nights, end).lateApps
        assertEquals(listOf("browser", "game"), splits.map { it.packageName })
        assertEquals(splits[0].withMedianOnsetMinute, splits[0].withoutMedianOnsetMinute)
        assertEquals(120.0, splits[1].withMedianOnsetMinute - splits[1].withoutMedianOnsetMinute)
    }

    @Test
    fun `a laptop is quiet from three days without recorded use, and never when none was set up`() {
        val day = 24 * 3_600_000L
        assertNull(LaptopSilence.since(null, now = 100 * day))
        assertNull(LaptopSilence.since(100 * day - 3 * day + 1, now = 100 * day))
        assertEquals(97 * day, LaptopSilence.since(97 * day, now = 100 * day))
    }
}
