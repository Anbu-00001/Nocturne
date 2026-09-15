package io.github.anbu00001.nocturne.core.metrics

import java.time.LocalDate

/**
 * What a metric needs before its number means what its name says (NOCTURNE_ANALYTICS.md §2, §6.1). Metrics declare
 * a [DataRequirement]; this file alone decides whether a window meets it, so no size check is scattered elsewhere.
 */
data class DataRequirement(
    /** Days (or nights) that must each be at least [minCoverage] known. */
    val minNights: Int,
    /** Share that must be known: per day, and across everything the metric compares. */
    val minCoverage: Double,
) {
    init {
        require(minNights >= 1 && minCoverage in 0.0..1.0)
    }
}

/** A day or night a metric can use, with the share of it that is known. */
interface CoveredDay {
    val date: LocalDate
    val coverage: Double
}

enum class WithheldReason {
    /** Fewer valid days than the metric needs. */
    TOO_FEW_NIGHTS,

    /** Enough valid days, but not enough of them next to each other to compare. */
    TOO_FEW_DAY_PAIRS,

    /** Enough days, but too much of what would be compared is unknown. */
    LOW_COVERAGE,

    /** Every value is the same, so a ratio of variances or amplitudes is undefined. */
    NO_VARIATION,

    /** The window lacks free nights or work nights, so there is nothing to compare between them. */
    MISSING_DAY_TYPES,
}

sealed interface MetricResult {
    /**
     * [nights] is how many valid days the value rests on; [coverage] the known share of what was compared.
     * [atMinute] is a local clock minute where the metric has one, such as the start of L5 or M10.
     */
    data class Score(val value: Double, val nights: Int, val coverage: Double, val atMinute: Int? = null) : MetricResult

    /**
     * Not enough data. The screen says what is missing ("SRI needs 7 nights; you have 4") and never shows a
     * provisional number. For [WithheldReason.LOW_COVERAGE], [have] and [need] are percentages.
     */
    data class Withheld(val reason: WithheldReason, val have: Int, val need: Int) : MetricResult
}

/** A statistic over a window of days. Adding one never touches scheduling: it only declares what it needs. */
interface Metric<in D : CoveredDay> {
    val requirement: DataRequirement
    fun compute(days: List<D>): MetricResult
}

object SufficiencyGate {

    /** The days that count at all, in date order: known for at least the requirement's share. */
    fun <D : CoveredDay> validDays(days: List<D>, requirement: DataRequirement): List<D> =
        days.filter { it.coverage >= requirement.minCoverage }.sortedBy { it.date }

    /** Null when there are enough valid days, else why not. */
    fun checkNights(days: List<CoveredDay>, requirement: DataRequirement): MetricResult.Withheld? {
        val valid = validDays(days, requirement).size
        return if (valid >= requirement.minNights) null else MetricResult.Withheld(WithheldReason.TOO_FEW_NIGHTS, valid, requirement.minNights)
    }

    /** Null when [pairs] consecutive day pairs reach what [DataRequirement.minNights] consecutive days would give. */
    fun checkPairs(pairs: Int, requirement: DataRequirement): MetricResult.Withheld? {
        val need = requirement.minNights - 1
        return if (pairs >= need) null else MetricResult.Withheld(WithheldReason.TOO_FEW_DAY_PAIRS, pairs, need)
    }

    /** Null when [known] of [possible] compared values reach the requirement's coverage. */
    fun checkCoverage(known: Long, possible: Long, requirement: DataRequirement): MetricResult.Withheld? {
        if (possible > 0 && known.toDouble() / possible >= requirement.minCoverage) return null
        val have = if (possible > 0) (100 * known / possible).toInt() else 0
        return MetricResult.Withheld(WithheldReason.LOW_COVERAGE, have, (100 * requirement.minCoverage).toInt())
    }
}
