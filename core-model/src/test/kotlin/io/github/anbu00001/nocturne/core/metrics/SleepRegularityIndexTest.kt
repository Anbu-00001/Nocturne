package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.sleep.ist
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SleepRegularityIndexTest {
    private val start = LocalDate.parse("2026-09-01")

    /** Day [i], asleep from [onset] until [wake] in minutes after noon (720 is midnight), with [unknown] epochs unknown. */
    private fun day(i: Int, onset: Int, wake: Int, unknown: IntRange = IntRange.EMPTY): SleepDay {
        val states = ByteArray(SleepDay.EPOCHS) { j -> if (j in onset until wake) SleepDay.ASLEEP else SleepDay.AWAKE }
        for (j in unknown) states[j] = SleepDay.UNKNOWN
        return SleepDay(start.plusDays(i.toLong()), states)
    }

    /** Asleep 00:00 to 08:00. */
    private fun regular(i: Int, unknown: IntRange = IntRange.EMPTY) = day(i, 720, 1200, unknown)

    @Test
    fun `a hand-computed eight-day case`() {
        // Asleep 00:00 to 08:00 every day except day 4, asleep 01:00 to 09:00. Day pairs (3, 4) and (4, 5) each
        // differ in 120 epochs (00:00 to 01:00 and 08:00 to 09:00); the other 5 pairs are identical.
        // Matching epoch pairs: 5 × 1440 + 2 × 1320 = 9840, of 7 × 1440 = 10080 compared.
        val days = (0 until 8).map { i -> if (i == 4) day(i, 780, 1260) else regular(i) }
        val score = assertIs<MetricResult.Score>(SleepRegularityIndex.compute(days))
        assertEquals(-100 + 200.0 * 9840 / 10080, score.value, 1e-9)
        assertEquals(95.238, score.value, 0.0005)
        assertEquals(8, score.nights)
        assertEquals(1.0, score.coverage)
    }

    @Test
    fun `a perfectly regular sleeper scores 100`() {
        val score = assertIs<MetricResult.Score>(SleepRegularityIndex.compute((0 until 14).map { regular(it) }))
        assertEquals(100.0, score.value, 1e-12)
    }

    @Test
    fun `a random sleeper scores about 0`() {
        val random = Random(42)
        val days = (0 until 28).map { i ->
            SleepDay(start.plusDays(i.toLong()), ByteArray(SleepDay.EPOCHS) { if (random.nextBoolean()) SleepDay.ASLEEP else SleepDay.AWAKE })
        }
        val score = assertIs<MetricResult.Score>(SleepRegularityIndex.compute(days))
        // 27 pairs × 1440 epochs: the standard deviation of a random score is about 0.5.
        assertTrue(kotlin.math.abs(score.value) < 3, "${score.value}")
    }

    @Test
    fun `days that swap every state score -100`() {
        val days = (0 until 8).map { i -> if (i % 2 == 0) day(i, 0, SleepDay.EPOCHS) else day(i, 0, 0) }
        assertEquals(-100.0, assertIs<MetricResult.Score>(SleepRegularityIndex.compute(days)).value, 1e-12)
    }

    @Test
    fun `six nights are withheld, saying how many there are`() {
        assertEquals(
            MetricResult.Withheld(WithheldReason.TOO_FEW_NIGHTS, have = 6, need = 7),
            SleepRegularityIndex.compute((0 until 6).map { regular(it) }),
        )
    }

    @Test
    fun `a day under 80 percent known is not a night`() {
        // 300 unknown epochs leave day 3 79.2% known.
        val days = (0 until 7).map { i -> if (i == 3) regular(i, unknown = 0 until 300) else regular(i) }
        assertEquals(MetricResult.Withheld(WithheldReason.TOO_FEW_NIGHTS, have = 6, need = 7), SleepRegularityIndex.compute(days))
    }

    @Test
    fun `unknown epochs are left out of both counts, not taken as awake`() {
        // Day 2 misses 00:00 to 02:00. Counted as awake, those epochs would look like two changed hours of sleep.
        val days = (0 until 8).map { i -> if (i == 2) regular(i, unknown = 720 until 840) else regular(i) }
        val score = assertIs<MetricResult.Score>(SleepRegularityIndex.compute(days))
        assertEquals(100.0, score.value, 1e-12)
        assertEquals((7 * 1440 - 2 * 120) / (7.0 * 1440), score.coverage, 1e-12)
    }

    @Test
    fun `seven nights that are not consecutive give too few day pairs`() {
        val days = listOf(0, 1, 2, 3, 5, 6, 7).map { regular(it) }
        assertEquals(MetricResult.Withheld(WithheldReason.TOO_FEW_DAY_PAIRS, have = 5, need = 6), SleepRegularityIndex.compute(days))
    }

    @Test
    fun `the compared share must itself reach 80 percent`() {
        // Every day is 85% known, but even days miss the afternoon and odd days the night: each pair compares 70%.
        val days = (0 until 8).map { i -> regular(i, unknown = if (i % 2 == 0) 0 until 216 else 720 until 936) }
        assertEquals(MetricResult.Withheld(WithheldReason.LOW_COVERAGE, have = 70, need = 80), SleepRegularityIndex.compute(days))
    }

    @Test
    fun `a night across midnight lands in one noon-to-noon day`() {
        val day = SleepDay.fromIntervals(
            start, 330, listOf(ist("2026-09-01", "23:00") until ist("2026-09-02", "07:00")),
            knownFromTs = Long.MIN_VALUE, knownToTs = Long.MAX_VALUE,
        )
        assertEquals(SleepDay.AWAKE, day[659])
        assertEquals(SleepDay.ASLEEP, day[660])
        assertEquals(SleepDay.ASLEEP, day[1139])
        assertEquals(SleepDay.AWAKE, day[1140])

        val partly = SleepDay.fromIntervals(start, 330, emptyList(), knownFromTs = ist("2026-09-01", "18:00"), knownToTs = Long.MAX_VALUE)
        assertEquals(SleepDay.UNKNOWN, partly[359])
        assertEquals(SleepDay.AWAKE, partly[360])
        assertEquals(0.75, partly.coverage)
    }
}
