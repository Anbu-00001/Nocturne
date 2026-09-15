package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/**
 * One noon-to-noon day of phone activity, the analytics spec's stand-in for actigraphy (NOCTURNE_ANALYTICS.md §5):
 * the share of each minute the screen was on, 0..1, or NaN where the history does not reach. It is not movement.
 * In a 2024 JMIR comparison phone-derived IS and IV came out significantly lower than actigraphy's, so values from
 * these days are labelled as phone-activity values and never compared with published actigraphy norms.
 */
class ActivityDay(override val date: LocalDate, values: DoubleArray) : CoveredDay {
    private val values = values.copyOf()

    init {
        require(values.size == SleepDay.EPOCHS) { "a day has ${SleepDay.EPOCHS} minutes, not ${values.size}" }
        require(values.all { it.isNaN() || it in 0.0..1.0 })
    }

    operator fun get(minute: Int): Double = values[minute]

    override val coverage: Double = values.count { !it.isNaN() }.toDouble() / SleepDay.EPOCHS

    /** The mean of hour [hour] (0 starts at 12:00), or NaN when fewer than half of its minutes are known. */
    fun hour(hour: Int): Double {
        var sum = 0.0
        var known = 0
        for (m in hour * 60 until (hour + 1) * 60) {
            val v = values[m]
            if (!v.isNaN()) {
                sum += v
                known++
            }
        }
        return if (known >= MIN_KNOWN_MINUTES_PER_HOUR) sum / known else Double.NaN
    }

    companion object {
        const val MIN_KNOWN_MINUTES_PER_HOUR = 30

        /** A day from screen-on spans (UTC millis, end exclusive); minutes starting outside the known range are NaN. */
        fun fromScreenSpans(date: LocalDate, offsetMinutes: Int, spans: List<LongRange>, knownFromTs: Long, knownToTs: Long): ActivityDay {
            val noon = noonUtc(date, offsetMinutes)
            val values = DoubleArray(SleepDay.EPOCHS)
            for (span in spans) {
                val end = span.last + 1
                if (end <= noon || span.first >= noon + LocalClock.DAY_MS) continue
                val first = ((maxOf(span.first, noon) - noon) / LocalClock.MINUTE_MS).toInt()
                val last = ((minOf(end, noon + LocalClock.DAY_MS) - 1 - noon) / LocalClock.MINUTE_MS).toInt()
                for (m in first..last) {
                    val minuteStart = noon + m * LocalClock.MINUTE_MS
                    val overlap = minOf(end, minuteStart + LocalClock.MINUTE_MS) - maxOf(span.first, minuteStart)
                    if (overlap > 0) values[m] = minOf(1.0, values[m] + overlap.toDouble() / LocalClock.MINUTE_MS)
                }
            }
            for (m in 0 until SleepDay.EPOCHS) {
                val minuteStart = noon + m * LocalClock.MINUTE_MS
                if (minuteStart < knownFromTs || minuteStart >= knownToTs) values[m] = Double.NaN
            }
            return ActivityDay(date, values)
        }

        /** Wakefulness from sleep estimates, 1 awake and 0 asleep, NaN where unknown: what [ProxyCheck] compares with. */
        fun fromSleepDay(day: SleepDay): ActivityDay = ActivityDay(
            day.date,
            DoubleArray(SleepDay.EPOCHS) { m ->
                when (day[m]) {
                    SleepDay.ASLEEP -> 0.0
                    SleepDay.AWAKE -> 1.0
                    else -> Double.NaN
                }
            },
        )
    }
}

/**
 * Non-parametric rest-activity measures (Van Someren et al., Chronobiol Int 1999; nparACT). The choices are pinned:
 * - IS and IV use hourly means, as Van Someren defined them. IV changes with epoch length, so values here are not
 *   comparable with IV computed on 1- or 5-minute epochs.
 * - An hour counts when at least 30 of its minutes are known; a day counts when 80% of its minutes are known.
 * - IV compares only neighbouring hours that are both known, so a gap never becomes a jump.
 * - L5 and M10 come from the minute-by-minute average day, with windows that wrap around it. Equal means go to the
 *   middle of the longest run of them, so an untouched night centres L5 in it (nparACT and pyActigraphy take the
 *   first); between equally long runs, the one starting earliest from noon.
 * - At least 7 counted days.
 */
private val ACTIVITY_REQUIREMENT = DataRequirement(minNights = 7, minCoverage = 0.8)

/** IS = N · Σ_h (x̄_h - x̄)² / (p · Σ_i (x_i - x̄)²) over hourly means: 0 is noise, 1 the same day repeated. */
object InterdailyStability : Metric<ActivityDay> {
    override val requirement = ACTIVITY_REQUIREMENT

    override fun compute(days: List<ActivityDay>): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(days, requirement)
        val hours = valid.flatMap { d -> (0 until 24).map { h -> h to d.hour(h) } }.filter { !it.second.isNaN() }
        SufficiencyGate.checkCoverage(hours.size.toLong(), valid.size * 24L, requirement)?.let { return it }
        val mean = hours.sumOf { it.second } / hours.size
        val total = hours.sumOf { sq(it.second - mean) }
        if (total == 0.0) return MetricResult.Withheld(WithheldReason.NO_VARIATION, 0, 0)
        val byHour = hours.groupBy({ it.first }, { it.second })
        val between = byHour.values.sumOf { sq(it.average() - mean) }
        return MetricResult.Score(hours.size * between / (byHour.size * total), valid.size, hours.size / (valid.size * 24.0))
    }
}

/** IV = N · Σ (x_i - x_{i-1})² / ((pairs) · Σ (x_i - x̄)²) over hourly means: near 0 smooth, about 2 noise. */
object IntradailyVariability : Metric<ActivityDay> {
    override val requirement = ACTIVITY_REQUIREMENT

    override fun compute(days: List<ActivityDay>): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(days, requirement)
        val series = hourlySeries(valid)
        val known = series.filter { !it.isNaN() }
        SufficiencyGate.checkCoverage(known.size.toLong(), valid.size * 24L, requirement)?.let { return it }
        val mean = known.average()
        val total = known.sumOf { sq(it - mean) }
        if (total == 0.0) return MetricResult.Withheld(WithheldReason.NO_VARIATION, 0, 0)
        var differences = 0.0
        var pairs = 0
        for (i in 1 until series.size) {
            val a = series[i - 1]
            val b = series[i]
            if (a.isNaN() || b.isNaN()) continue
            differences += sq(b - a)
            pairs++
        }
        SufficiencyGate.checkPairs(pairs, DataRequirement(minNights = 2, minCoverage = 0.0))?.let { return it }
        return MetricResult.Score(known.size * differences / (pairs * total), valid.size, known.size / (valid.size * 24.0))
    }

    /** Hourly means from the first to the last counted day, NaN for days that do not count or are missing. */
    private fun hourlySeries(valid: List<ActivityDay>): List<Double> {
        val byDate = valid.associateBy { it.date }
        val first = valid.first().date
        val last = valid.last().date
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.flatMap { date ->
            val day = byDate[date]
            (0 until 24).map { h -> day?.hour(h) ?: Double.NaN }
        }.toList()
    }
}

/** Mean activity over the least active 5 consecutive hours of the average day, and when they start. */
object LeastActive5 : Metric<ActivityDay> {
    override val requirement = ACTIVITY_REQUIREMENT
    override fun compute(days: List<ActivityDay>): MetricResult = RestActivity.window(days, requirement, 5 * 60, lowest = true)
}

/** Mean activity over the most active 10 consecutive hours of the average day, and when they start. */
object MostActive10 : Metric<ActivityDay> {
    override val requirement = ACTIVITY_REQUIREMENT
    override fun compute(days: List<ActivityDay>): MetricResult = RestActivity.window(days, requirement, 10 * 60, lowest = false)
}

/** RA = (M10 - L5) / (M10 + L5): 0 no rhythm, 1 a fully quiet L5. */
object RelativeAmplitude : Metric<ActivityDay> {
    override val requirement = ACTIVITY_REQUIREMENT

    override fun compute(days: List<ActivityDay>): MetricResult {
        val l5 = LeastActive5.compute(days)
        if (l5 !is MetricResult.Score) return l5
        val m10 = MostActive10.compute(days)
        if (m10 !is MetricResult.Score) return m10
        if (m10.value + l5.value == 0.0) return MetricResult.Withheld(WithheldReason.NO_VARIATION, 0, 0)
        return MetricResult.Score((m10.value - l5.value) / (m10.value + l5.value), l5.nights, l5.coverage)
    }
}

/**
 * Circadian Function Index (Ortiz-Tudela et al., PLoS Comput Biol 2010): the mean of IS, inverted IV and RA, each on
 * 0..1, with IV mapped as (2 - IV) / 2 and clipped. 0 is no rhythm, 1 a maximally robust one.
 */
object CircadianFunctionIndex : Metric<ActivityDay> {
    override val requirement = ACTIVITY_REQUIREMENT

    override fun compute(days: List<ActivityDay>): MetricResult {
        val stability = InterdailyStability.compute(days)
        if (stability !is MetricResult.Score) return stability
        val variability = IntradailyVariability.compute(days)
        if (variability !is MetricResult.Score) return variability
        val amplitude = RelativeAmplitude.compute(days)
        if (amplitude !is MetricResult.Score) return amplitude
        val invertedIv = ((2 - variability.value) / 2).coerceIn(0.0, 1.0)
        return MetricResult.Score((stability.value + invertedIv + amplitude.value) / 3, stability.nights, stability.coverage)
    }
}

internal object RestActivity {

    /** The minute-by-minute mean over the counted days, NaN where no counted day knows that minute. */
    fun averageDay(valid: List<ActivityDay>): DoubleArray = DoubleArray(SleepDay.EPOCHS) { m ->
        var sum = 0.0
        var known = 0
        for (day in valid) {
            val v = day[m]
            if (!v.isNaN()) {
                sum += v
                known++
            }
        }
        if (known == 0) Double.NaN else sum / known
    }

    fun window(days: List<ActivityDay>, requirement: DataRequirement, length: Int, lowest: Boolean): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(days, requirement)
        val profile = averageDay(valid)
        val knownMinutes = profile.count { !it.isNaN() }
        SufficiencyGate.checkCoverage(knownMinutes.toLong(), SleepDay.EPOCHS.toLong(), requirement)?.let { return it }

        // Prefix sums over the day laid out twice, so each wrapping window's mean is two subtractions, not a scan.
        val n = SleepDay.EPOCHS
        val sums = DoubleArray(2 * n + 1)
        val counts = IntArray(2 * n + 1)
        for (i in 0 until 2 * n) {
            val v = profile[i % n]
            sums[i + 1] = sums[i] + if (v.isNaN()) 0.0 else v
            counts[i + 1] = counts[i] + if (v.isNaN()) 0 else 1
        }
        val means = DoubleArray(n) { start ->
            val known = counts[start + length] - counts[start]
            if (known * 2 < length) Double.NaN else (sums[start + length] - sums[start]) / known
        }
        val candidates = means.filter { !it.isNaN() }
        if (candidates.isEmpty()) return MetricResult.Withheld(WithheldReason.LOW_COVERAGE, 0, (100 * requirement.minCoverage).toInt())
        val best = if (lowest) candidates.min() else candidates.max()
        // A quiet stretch longer than the window, a night with the phone untouched, gives a plateau of equal means. Its
        // middle stands for it; nparACT and pyActigraphy take the first start, which on a phone can land L5 on the
        // edge of an afternoon that is just as quiet.
        val bestStart = middleOfLongestRun(BooleanArray(n) { means[it] == best })
        val clockMinute = (LocalClock.NIGHT_BOUNDARY_HOUR * 60 + bestStart) % SleepDay.EPOCHS
        return MetricResult.Score(best, valid.size, knownMinutes.toDouble() / SleepDay.EPOCHS, atMinute = clockMinute)
    }

    /**
     * The middle of the longest circular run of true values (the earlier middle of an even run). Between runs of equal
     * length, the one starting earliest from noon. All true gives 0.
     */
    fun middleOfLongestRun(flags: BooleanArray): Int {
        val n = flags.size
        val firstFalse = flags.indexOfFirst { !it }
        if (firstFalse < 0) return 0
        var bestStart = -1
        var bestLength = 0
        // Scanning from just after a false value, no run is split by the end of the array.
        var k = 0
        while (k < n) {
            val i = (firstFalse + 1 + k) % n
            if (!flags[i]) {
                k++
                continue
            }
            var length = 0
            while (flags[(i + length) % n]) length++
            if (length > bestLength || (length == bestLength && i < bestStart)) {
                bestStart = i
                bestLength = length
            }
            k += length
        }
        return (bestStart + (bestLength - 1) / 2) % n
    }
}

private fun sq(x: Double) = x * x
