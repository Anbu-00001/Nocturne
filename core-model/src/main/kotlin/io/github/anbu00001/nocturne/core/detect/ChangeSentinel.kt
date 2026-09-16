package io.github.anbu00001.nocturne.core.detect

import io.github.anbu00001.nocturne.core.sleep.MAD_TO_SD
import io.github.anbu00001.nocturne.core.sleep.mad
import io.github.anbu00001.nocturne.core.sleep.median
import io.github.anbu00001.nocturne.core.sleep.minutesSinceNoon
import java.time.LocalDate

/** One value of a series the sentinel watches, dated by the night (or the night a weekly window ends on) it describes. */
data class DatedValue(val date: LocalDate, val value: Double)

/**
 * The series the analytics spec's Tier 2 sentinel watches, and how. Thresholds were measured on simulated steady and
 * shifted series with the reference implementation (docs/PHASES.md, "Phase 3.5").
 */
enum class SentinelSeries(
    /** Chance of a change before each value: once a quarter. */
    val hazard: Double,
    /** A change is looked for this many values back ... */
    val window: Int,
    /** ... and needs this many values since it, so a single odd night or week is not a shift. */
    val minSince: Int,
    /** The prior is centred on the median of this many first values, with their spread. */
    val burnIn: Int,
    /** The smallest spread the prior assumes, in the series' units. */
    val floorScale: Double,
    /** Values needed before anything is said (analytics §7: 60 nights). */
    val minValues: Int,
) {
    /** Sleep onset in minutes after noon, one value per night with a confident onset or one you entered. */
    SLEEP_ONSET(hazard = 1.0 / 90, window = 42, minSince = 7, burnIn = 14, floorScale = 30.0, minValues = 60),

    /**
     * The 7-night SRI, one value a week from windows that do not overlap. The spec names the nightly 7-night SRI, but
     * neighbouring windows share 6 of 7 nights, and on steady simulated sleep the sentinel then crossed 0.8 on 58% of
     * nights; weekly, in 0.2% of weeks.
     */
    SRI_WEEKLY(hazard = 1.0 / 13, window = 6, minSince = 2, burnIn = 4, floorScale = 5.0, minValues = 8),
}

sealed interface SentinelResult {
    /** Not enough values yet. */
    data class Withheld(val have: Int, val need: Int) : SentinelResult

    /** Looked, and the chance of a recent shift stays under [ChangeSentinel.SHOW_AT]. */
    data class Steady(val values: Int, val probability: Double) : SentinelResult

    /**
     * A shift is likely: the latest run began at [around] with this [probability], [valuesSince] values ago. [before] and
     * [after] are the medians of up to a window of values on either side, for the sentence that reports it.
     */
    data class Shift(
        val values: Int,
        val probability: Double,
        val around: LocalDate,
        val valuesSince: Int,
        val before: Double,
        val after: Double,
    ) : SentinelResult {
        /** The analytics spec's trigger for an early Tier 3 refit. */
        val refit: Boolean get() = probability > ChangeSentinel.REFIT_AT
    }
}

/**
 * The drift sentinel (analytics spec Tier 2): Bocpd over each series from its first value, reporting a posterior, never a
 * verdict. On simulated nights with onset spread 45 min, P(change) crossed [SHOW_AT] on 0.23% of steady nights, and a
 * one-hour shift reached it after a median of 10 nights (7 for two hours, the least [SentinelSeries.minSince] allows).
 */
object ChangeSentinel {
    const val SHOW_AT = 0.8
    const val REFIT_AT = 0.9

    fun assess(series: SentinelSeries, values: List<DatedValue>): SentinelResult {
        val sorted = values.sortedBy { it.date }
        if (sorted.size < series.minValues) return SentinelResult.Withheld(sorted.size, series.minValues)
        val xs = DoubleArray(sorted.size) { sorted[it].value }
        val head = xs.take(series.burnIn)
        val prior = NormalGammaPrior.weak(median(head), maxOf(MAD_TO_SD * mad(head), series.floorScale))
        val result = Bocpd.run(xs, prior, series.hazard)
        val signal = result.recentChange(series.window, series.minSince)
        if (signal == null || signal.probability < SHOW_AT) return SentinelResult.Steady(xs.size, signal?.probability ?: 0.0)
        val start = signal.startIndex
        return SentinelResult.Shift(
            values = xs.size,
            probability = signal.probability,
            around = sorted[start].date,
            valuesSince = signal.valuesSince,
            before = median(xs.slice(maxOf(0, start - series.window) until start)),
            after = median(xs.slice(start until xs.size)),
        )
    }

    /** A night's onset on the scale the sentinel reads: minutes after local noon, so 23:30 and 01:30 are two hours apart. */
    fun onsetMinutesAfterNoon(onsetTs: Long, offsetMinutes: Int): Double = minutesSinceNoon(onsetTs, offsetMinutes)

    /** One value a week: the latest, and those ending exactly 7, 14, ... nights before it. A week without one is skipped. */
    fun weekly(values: List<DatedValue>): List<DatedValue> {
        val byDate = values.associateBy { it.date }
        val latest = values.maxOfOrNull { it.date } ?: return emptyList()
        val earliest = values.minOf { it.date }
        return generateSequence(latest) { it.minusDays(7) }.takeWhile { it >= earliest }.mapNotNull(byDate::get).toList().reversed()
    }
}
