package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.metrics.MetricResult.Score
import io.github.anbu00001.nocturne.core.metrics.MetricResult.Withheld
import io.github.anbu00001.nocturne.core.sleep.ist
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivityMetricsTest {
    private val start = LocalDate.parse("2026-09-07")

    /** A day with the screen on through the given clock hours and off otherwise; [unknown] minutes after noon are NaN. */
    private fun day(i: Int, onHours: Set<Int>, unknown: IntRange = IntRange.EMPTY): ActivityDay {
        val values = DoubleArray(SleepDay.EPOCHS) { m -> if (((12 * 60 + m) % SleepDay.EPOCHS) / 60 in onHours) 1.0 else 0.0 }
        for (m in unknown) values[m] = Double.NaN
        return ActivityDay(start.plusDays(i.toLong()), values)
    }

    /** A day built from a function of the clock minute. */
    private fun dayOf(i: Int, valueAt: (Int) -> Double) =
        ActivityDay(start.plusDays(i.toLong()), DoubleArray(SleepDay.EPOCHS) { m -> valueAt((12 * 60 + m) % SleepDay.EPOCHS) })

    /** 18:00 to 23:00. */
    private val evening = (18..22).toSet()

    @Test
    fun `the same day repeated is perfectly stable`() {
        val score = assertIs<Score>(InterdailyStability.compute((0 until 7).map { day(it, evening) }))
        assertEquals(1.0, score.value, 1e-12)
        assertEquals(7, score.nights)
    }

    @Test
    fun `intradaily variability of a repeated evening block matches the hand calculation`() {
        // 168 hourly means, 5 of every 24 on: 2 changes a day, 14 in all; mean 5/24 and Σ(x - mean)² = 168 · (5/24)(19/24).
        // IV = 168 · 14 / (167 · 168 · 95/576) = 8064 / 15865.
        val score = assertIs<Score>(IntradailyVariability.compute((0 until 7).map { day(it, evening) }))
        assertEquals(8064.0 / 15865, score.value, 1e-12)
    }

    @Test
    fun `an unknown stretch is left out of IV rather than read as a jump`() {
        // Day 3 misses 13:00 to 15:00: 166 known hours, 3 neighbouring pairs lost, still 14 changes.
        val days = (0 until 7).map { i -> if (i == 3) day(i, evening, unknown = 60 until 180) else day(i, evening) }
        val score = assertIs<Score>(IntradailyVariability.compute(days))
        assertEquals(166.0 * 14 / (164 * (35.0 * 131 / 166)), score.value, 1e-12)
    }

    @Test
    fun `noise is unstable and fragmented`() {
        val random = Random(7)
        val days = (0 until 28).map { i -> ActivityDay(start.plusDays(i.toLong()), DoubleArray(SleepDay.EPOCHS) { random.nextDouble() }) }
        val stability = assertIs<Score>(InterdailyStability.compute(days)).value
        val variability = assertIs<Score>(IntradailyVariability.compute(days)).value
        assertTrue(stability < 0.15, "IS $stability")
        assertTrue(variability in 1.7..2.3, "IV $variability")
    }

    @Test
    fun `L5 and M10 find the quietest 5 and busiest 10 hours, and when they start`() {
        // 0.2 by default, nothing from 01:00 to 06:00, the screen on from 14:00 to midnight.
        val days = (0 until 7).map { i ->
            dayOf(i) { clock ->
                when {
                    clock in 60 until 360 -> 0.0
                    clock >= 840 -> 1.0
                    else -> 0.2
                }
            }
        }
        val l5 = assertIs<Score>(LeastActive5.compute(days))
        assertEquals(0.0, l5.value, 1e-9)
        assertEquals(60, l5.atMinute)
        val m10 = assertIs<Score>(MostActive10.compute(days))
        assertEquals(1.0, m10.value, 1e-9)
        assertEquals(840, m10.atMinute)
        assertEquals(1.0, assertIs<Score>(RelativeAmplitude.compute(days)).value, 1e-9)
    }

    @Test
    fun `relative amplitude of a partial contrast`() {
        // 0.5 by default, 0.1 for 5 quiet hours, 0.9 for 10 busy ones: RA = (0.9 - 0.1) / (0.9 + 0.1) = 0.8.
        val days = (0 until 7).map { i ->
            dayOf(i) { clock ->
                when {
                    clock in 60 until 360 -> 0.1
                    clock >= 840 -> 0.9
                    else -> 0.5
                }
            }
        }
        assertEquals(0.8, assertIs<Score>(RelativeAmplitude.compute(days)).value, 1e-9)
    }

    @Test
    fun `the circadian function index averages IS, inverted IV and RA`() {
        val days = (0 until 7).map { day(it, evening) }
        // IS 1; RA 1, since any 10 h window around the evening block beats the zero L5.
        val iv = 8064.0 / 15865
        assertEquals((1.0 + (2 - iv) / 2 + 1.0) / 3, assertIs<Score>(CircadianFunctionIndex.compute(days)).value, 1e-9)
    }

    @Test
    fun `six days, or a screen that never changes, are withheld`() {
        assertEquals(Withheld(WithheldReason.TOO_FEW_NIGHTS, 6, 7), InterdailyStability.compute((0 until 6).map { day(it, evening) }))
        assertEquals(Withheld(WithheldReason.NO_VARIATION, 0, 0), InterdailyStability.compute((0 until 7).map { day(it, emptySet()) }))
        assertEquals(Withheld(WithheldReason.NO_VARIATION, 0, 0), CircadianFunctionIndex.compute((0 until 7).map { day(it, emptySet()) }))
    }

    @Test
    fun `screen spans become minute shares, and minutes outside the history are unknown`() {
        val day = ActivityDay.fromScreenSpans(
            start, 330, listOf(ist("2026-09-07", "21:00:30") until ist("2026-09-07", "21:02")),
            knownFromTs = Long.MIN_VALUE, knownToTs = ist("2026-09-08", "06:00"),
        )
        assertEquals(0.5, day[540], 1e-12)
        assertEquals(1.0, day[541], 1e-12)
        assertEquals(0.0, day[542], 1e-12)
        assertTrue(day[1080].isNaN())
        assertEquals(0.75, day.coverage, 1e-12)
    }
}
