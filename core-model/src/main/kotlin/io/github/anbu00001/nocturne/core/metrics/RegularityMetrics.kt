package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.sleep.localMidnightUtc
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/**
 * One day from noon to noon, so a night is never split, as 1-minute sleep states: [ASLEEP], [AWAKE] or [UNKNOWN].
 * Epoch 0 is 12:00 local on [date]; epoch 720 is midnight.
 */
class SleepDay(val date: LocalDate, states: ByteArray) {
    private val states = states.copyOf()

    init {
        require(states.size == EPOCHS) { "a day has $EPOCHS epochs, not ${states.size}" }
        require(states.all { it == ASLEEP || it == AWAKE || it == UNKNOWN })
    }

    operator fun get(epoch: Int): Byte = states[epoch]

    val knownEpochs: Int = states.count { it != UNKNOWN }

    val coverage: Double get() = knownEpochs.toDouble() / EPOCHS

    companion object {
        const val EPOCHS = 24 * 60
        const val ASLEEP: Byte = 1
        const val AWAKE: Byte = 0
        const val UNKNOWN: Byte = -1

        /**
         * A day from sleep intervals (UTC millis, end exclusive), known only between [knownFromTs] and [knownToTs].
         * Everything known and outside the intervals is awake. [offsetMinutes] is the UTC offset at the day's noon.
         */
        fun fromIntervals(date: LocalDate, offsetMinutes: Int, sleep: List<LongRange>, knownFromTs: Long, knownToTs: Long): SleepDay {
            val noon = localMidnightUtc(date, offsetMinutes) + LocalClock.NIGHT_BOUNDARY_HOUR * LocalClock.HOUR_MS
            val states = ByteArray(EPOCHS) { j ->
                val t = noon + j * LocalClock.MINUTE_MS
                when {
                    t < knownFromTs || t >= knownToTs -> UNKNOWN
                    sleep.any { t in it } -> ASLEEP
                    else -> AWAKE
                }
            }
            return SleepDay(date, states)
        }
    }
}

/**
 * Sleep Regularity Index (Phillips et al., Sci Rep 2017): the chance of being in the same state, asleep or awake, at
 * two moments 24 h apart, rescaled so identical days score 100, random days 0 and days that swap every state -100.
 *
 *     SRI = -100 + 200 · matching epoch pairs / compared epoch pairs
 *
 * With nothing missing this is Phillips' -100 + 200 / (M(N-1)) · Σ_i Σ_j δ(s[i,j], s[i+1,j]). Packages disagree on
 * the choices below enough to change study conclusions (RIRI statement, SLEEP 2026), so they are pinned:
 * - Epoch 1 min, M = 1440. (GGIR uses 30 s.)
 * - Days run noon to noon, as in GGIR and sleepreg.
 * - Naps count when the input marks them. Nocturne's sleep inference finds one main sleep per night, so its days
 *   carry no naps and the result is main-sleep regularity. A sleepless night is awake throughout.
 * - A day counts only when 80% of its epochs are known. Within a pair of counted days, an epoch unknown on either
 *   day is left out of both counts (pairwise), and the compared share must itself reach 80%. (GGIR drops day pairs
 *   below 66% valid.)
 * - At least 7 counted days, compared only with the next calendar day, so 6 consecutive pairs at minimum.
 * - Reported on the -100 to 100 scale, not rescaled to 0 to 100.
 */
object SleepRegularityIndex : Metric {
    override val requirement = DataRequirement(minNights = 7, minCoverage = 0.8)

    override fun compute(days: List<SleepDay>): MetricResult {
        SufficiencyGate.checkNights(days, requirement)?.let { return it }
        val byDate = SufficiencyGate.validDays(days, requirement).associateBy { it.date }
        var matching = 0L
        var compared = 0L
        var pairs = 0
        val paired = HashSet<LocalDate>()
        for ((date, day) in byDate) {
            val next = byDate[date.plusDays(1)] ?: continue
            pairs++
            paired += date
            paired += next.date
            for (j in 0 until SleepDay.EPOCHS) {
                val a = day[j]
                val b = next[j]
                if (a == SleepDay.UNKNOWN || b == SleepDay.UNKNOWN) continue
                compared++
                if (a == b) matching++
            }
        }
        SufficiencyGate.checkPairs(pairs, requirement)?.let { return it }
        val possible = pairs.toLong() * SleepDay.EPOCHS
        SufficiencyGate.checkCoverage(compared, possible, requirement)?.let { return it }
        return MetricResult.Score(-100 + 200.0 * matching / compared, nights = paired.size, coverage = compared.toDouble() / possible)
    }
}
