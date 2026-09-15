package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One night's sleep timing: onset and wake as instants, or neither when the night has no estimate or no sleep.
 * A night without times does not count for timing metrics; there is no midsleep to measure.
 */
data class NightTiming(
    override val date: LocalDate,
    val offsetMinutes: Int,
    val onsetTs: Long?,
    val wakeTs: Long?,
    /** The next day is a free day, so this night's sleep is not bound by work or classes. */
    val freeNight: Boolean,
) : CoveredDay {
    override val coverage: Double get() = if (onsetTs != null && wakeTs != null) 1.0 else 0.0

    /** Minutes after noon on the night's own date, so every onset and midsleep sits on one unbroken scale. */
    val onsetMinute: Double? get() = onsetTs?.let { minutesAfterNoon(it) }
    val midsleepMinute: Double? get() = if (onsetTs != null && wakeTs != null) minutesAfterNoon((onsetTs + wakeTs) / 2) else null
    val durationMinutes: Double? get() = if (onsetTs != null && wakeTs != null) (wakeTs - onsetTs).toDouble() / LocalClock.MINUTE_MS else null

    private fun minutesAfterNoon(ts: Long) = (ts - noonUtc(date, offsetMinutes)).toDouble() / LocalClock.MINUTE_MS
}

/**
 * Which nights come before a free day. By default Friday and Saturday nights, a working or teaching week; a student's
 * or shift worker's week can differ, so it is a setting rather than a constant.
 */
data class FreeNights(val days: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)) {
    fun isFree(night: LocalDate) = night.dayOfWeek in days
}

private val TIMING_REQUIREMENT = DataRequirement(minNights = 7, minCoverage = 1.0)

/** Sample standard deviation of sleep onset, in minutes, over nights with times. */
object SleepOnsetVariability : Metric<NightTiming> {
    override val requirement = TIMING_REQUIREMENT

    override fun compute(days: List<NightTiming>): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(days, requirement)
        val onsets = valid.map { it.onsetMinute!! }
        val mean = onsets.average()
        val sd = sqrt(onsets.sumOf { (it - mean) * (it - mean) } / (onsets.size - 1))
        return MetricResult.Score(sd, valid.size, valid.size.toDouble() / days.size)
    }
}

/**
 * Social jetlag (Wittmann et al., Chronobiol Int 2006): |MSF - MSW|, in minutes, the gap between mean midsleep on
 * free nights and on work nights.
 */
object SocialJetlag : Metric<NightTiming> {
    override val requirement = TIMING_REQUIREMENT

    override fun compute(days: List<NightTiming>): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(days, requirement)
        val (free, work) = valid.partition { it.freeNight }
        if (free.isEmpty() || work.isEmpty()) return MetricResult.Withheld(WithheldReason.MISSING_DAY_TYPES, minOf(free.size, work.size), 1)
        val gap = free.map { it.midsleepMinute!! }.average() - work.map { it.midsleepMinute!! }.average()
        return MetricResult.Score(kotlin.math.abs(gap), valid.size, valid.size.toDouble() / days.size)
    }
}

/**
 * Composite Phase Deviation (Fischer et al., Sci Rep 2016), in hours: for each night, the length of the vector whose
 * sides are mistiming (midsleep against the chronotype) and irregularity (midsleep against the previous night),
 * averaged over the window.
 *
 * Fischer took the chronotype from the Munich ChronoType Questionnaire. Here it is derived from the same window, as
 * the MCTQ defines it: MSFsc = MSF - (SDF - SDweek) / 2 when free-night sleep is longer than work-night sleep, else
 * MSF, with SDweek = (SDW · work nights + SDF · free nights) / 7 per week. The MCTQ withholds MSFsc when an alarm is
 * used on free days; the phone cannot tell, so free-night alarms go unnoticed. A night counts only when the night
 * before has times, and at least 6 such pairs are needed.
 */
class CompositePhaseDeviation(private val freeNightsPerWeek: Int = 2) : Metric<NightTiming> {
    override val requirement = TIMING_REQUIREMENT

    override fun compute(days: List<NightTiming>): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(days, requirement)
        val chronotype = chronotypeMinute(valid) ?: return MetricResult.Withheld(
            WithheldReason.MISSING_DAY_TYPES,
            minOf(valid.count { it.freeNight }, valid.count { !it.freeNight }),
            1,
        )
        val byDate = valid.associateBy { it.date }
        val deviations = valid.mapNotNull { night ->
            val previous = byDate[night.date.minusDays(1)] ?: return@mapNotNull null
            val midsleep = night.midsleepMinute!!
            // Both midsleeps count from their own night's noon, so the difference is the change in clock time.
            hypot((midsleep - chronotype) / 60, (midsleep - previous.midsleepMinute!!) / 60)
        }
        SufficiencyGate.checkPairs(deviations.size, requirement)?.let { return it }
        return MetricResult.Score(deviations.average(), valid.size, valid.size.toDouble() / days.size)
    }

    /** MSFsc in minutes after noon, or null without both free and work nights. */
    internal fun chronotypeMinute(valid: List<NightTiming>): Double? {
        val (free, work) = valid.partition { it.freeNight }
        if (free.isEmpty() || work.isEmpty()) return null
        val msf = free.map { it.midsleepMinute!! }.average()
        val sdf = free.map { it.durationMinutes!! }.average()
        val sdw = work.map { it.durationMinutes!! }.average()
        if (sdf <= sdw) return msf
        val sdWeek = (sdw * (7 - freeNightsPerWeek) + sdf * freeNightsPerWeek) / 7
        return msf - (sdf - sdWeek) / 2
    }
}
