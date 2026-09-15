package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.metrics.MetricResult.Score
import io.github.anbu00001.nocturne.core.metrics.MetricResult.Withheld
import io.github.anbu00001.nocturne.core.sleep.IST
import io.github.anbu00001.nocturne.core.sleep.ist
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SleepTimingMetricsTest {
    /** Monday 7 Sept 2026, so night 4 is a Friday and night 5 a Saturday. */
    private val monday = LocalDate.parse("2026-09-07")
    private val week = FreeNights()

    /** Night [i] asleep from [onset] to [wake], local clock times: onsets from noon on belong to the night's own date. */
    private fun night(i: Int, onset: String, wake: String): NightTiming {
        val date = monday.plusDays(i.toLong())
        fun at(time: String) = ist((if (LocalTime.parse(time).hour >= 12) date else date.plusDays(1)).toString(), time)
        return NightTiming(date, IST, at(onset), at(wake), week.isFree(date))
    }

    /** Work nights 23:00 to 07:00 (midsleep 03:00), free nights 01:00 to 09:00 (midsleep 05:00). */
    private fun shiftedWeekend(count: Int) = (0 until count).map { i ->
        if (week.isFree(monday.plusDays(i.toLong()))) night(i, "01:00", "09:00") else night(i, "23:00", "07:00")
    }

    @Test
    fun `onset variability is the sample standard deviation in minutes`() {
        // Onsets 01:00 and 03:00, four of each: every onset is 60 min from the mean.
        val nights = (0 until 8).map { i -> if (i % 4 < 2) night(i, "01:00", "09:00") else night(i, "03:00", "11:00") }
        assertEquals(sqrt(8 * 3600.0 / 7), assertIs<Score>(SleepOnsetVariability.compute(nights)).value, 1e-9)
    }

    @Test
    fun `social jetlag is the gap between free-night and work-night midsleep`() {
        assertEquals(120.0, assertIs<Score>(SocialJetlag.compute(shiftedWeekend(7))).value, 1e-9)
    }

    @Test
    fun `social jetlag is withheld without free nights`() {
        val nights = shiftedWeekend(7).map { it.copy(freeNight = false) }
        assertEquals(Withheld(WithheldReason.MISSING_DAY_TYPES, 0, 1), SocialJetlag.compute(nights))
    }

    @Test
    fun `composite phase deviation matches the hand calculation`() {
        // Chronotype = free-night midsleep 05:00 (equal durations, no correction). For nights 1 to 7:
        // work after work (2 h, 0) -> 2; Friday after Thursday (0, 2 h) -> 2; Saturday after Friday -> 0;
        // Sunday after Saturday (-2 h, -2 h) -> 2√2; Monday after Sunday -> 2. Mean (10 + 2√2) / 7.
        val score = assertIs<Score>(CompositePhaseDeviation().compute(shiftedWeekend(8)))
        assertEquals((10 + 2 * sqrt(2.0)) / 7, score.value, 1e-9)
    }

    @Test
    fun `a perfectly regular sleeper has no phase deviation`() {
        val nights = (0 until 8).map { night(it, "23:00", "07:00") }
        assertEquals(0.0, assertIs<Score>(CompositePhaseDeviation().compute(nights)).value, 1e-12)
    }

    @Test
    fun `the chronotype is corrected for sleeping in on free nights`() {
        // Free nights 01:00 to 11:00 (10 h, midsleep 06:00), work nights 23:00 to 07:00 (8 h).
        // SDweek = (5 · 480 + 2 · 600) / 7; MSFsc = 06:00 - (600 - SDweek) / 2 after noon.
        val nights = (0 until 7).map { i -> if (week.isFree(monday.plusDays(i.toLong()))) night(i, "01:00", "11:00") else night(i, "23:00", "07:00") }
        val sdWeek = (5 * 480 + 2 * 600) / 7.0
        assertEquals(18 * 60 - (600 - sdWeek) / 2, CompositePhaseDeviation().chronotypeMinute(nights)!!, 1e-9)
    }

    @Test
    fun `nights without times do not count`() {
        val nights = shiftedWeekend(7).mapIndexed { i, n -> if (i == 2) n.copy(onsetTs = null, wakeTs = null) else n }
        assertEquals(Withheld(WithheldReason.TOO_FEW_NIGHTS, 6, 7), SleepOnsetVariability.compute(nights))
    }
}
