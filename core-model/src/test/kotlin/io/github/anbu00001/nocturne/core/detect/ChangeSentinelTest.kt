package io.github.anbu00001.nocturne.core.detect

import java.time.LocalDate
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChangeSentinelTest {
    private val first = LocalDate.parse("2026-09-05")

    private fun nights(seed: Long, vararg parts: Pair<Int, Double>, sd: Double = 40.0, skip: Set<Int> = emptySet()): List<DatedValue> {
        val random = Random(seed)
        var day = 0
        return parts.flatMap { (n, mean) -> List(n) { mean + sd * random.nextGaussian() } }
            .mapIndexedNotNull { i, v -> (day++).let { d -> if (i in skip) null else DatedValue(first.plusDays(d.toLong()), v) } }
    }

    @Test
    fun `nothing is said before 60 nights`() {
        assertEquals(SentinelResult.Withheld(59, 60), ChangeSentinel.assess(SentinelSeries.SLEEP_ONSET, nights(1, 59 to 740.0)))
    }

    @Test
    fun `a later onset is reported with the night it began and the medians either side`() {
        // 60 nights around 00:20, then 20 around 02:20; three nights without an onset along the way.
        val values = nights(2, 60 to 740.0, 20 to 860.0, skip = setOf(10, 30, 70))
        val shift = assertIs<SentinelResult.Shift>(ChangeSentinel.assess(SentinelSeries.SLEEP_ONSET, values))
        assertTrue(shift.probability >= ChangeSentinel.SHOW_AT, "$shift")
        assertTrue(shift.around in first.plusDays(58)..first.plusDays(62), "$shift")
        assertEquals(values.count { it.date >= shift.around }, shift.valuesSince)
        assertTrue(shift.before in 720.0..760.0 && shift.after in 840.0..880.0, "$shift")
    }

    @Test
    fun `steady onsets are reported steady with the chance of a shift`() {
        val steady = assertIs<SentinelResult.Steady>(ChangeSentinel.assess(SentinelSeries.SLEEP_ONSET, nights(3, 90 to 740.0)))
        assertEquals(90, steady.values)
        assertTrue(steady.probability < ChangeSentinel.SHOW_AT)
    }

    @Test
    fun `weekly values step back seven nights from the latest and skip a missing week`() {
        val daily = (0 until 30).map { DatedValue(first.plusDays(it.toLong()), it.toDouble()) }.filter { it.date != first.plusDays(15) }
        assertEquals(listOf(1.0, 8.0, 22.0, 29.0), ChangeSentinel.weekly(daily).map { it.value })
        assertEquals(emptyList(), ChangeSentinel.weekly(emptyList()))
    }

    @Test
    fun `onsets either side of midnight sit on one scale`() {
        val offset = 330
        fun ts(date: String, time: String) = java.time.LocalDateTime.parse("${date}T$time").toInstant(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
        assertEquals(690.0, ChangeSentinel.onsetMinutesAfterNoon(ts("2026-09-14", "23:30"), offset))
        assertEquals(810.0, ChangeSentinel.onsetMinutesAfterNoon(ts("2026-09-15", "01:30"), offset))
    }
}
