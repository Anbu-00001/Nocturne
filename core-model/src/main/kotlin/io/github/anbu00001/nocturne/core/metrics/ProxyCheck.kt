package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.time.LocalClock

/**
 * Checks the screen-use stand-in for actigraphy against sleep inference before its rhythm figures are read as rest and
 * activity (NOCTURNE_ANALYTICS.md §5 and pitfall 8). Per window:
 * - IS and IV on wakefulness from the sleep estimates (1 awake, 0 asleep), computed exactly as for screen use and
 *   stored beside it. How far the screen-use values sit from these shows how much of the screen rhythm is sleep and
 *   wake, and how much is when the phone happens to be picked up.
 * - How much of the screen-use L5 the sleep estimates call asleep. When the quietest screen hours are mostly awake
 *   time (a class, the phone in another room), L5, RA and CFI describe phone habits rather than rest.
 *
 * Not an independent validation: sleep inference reads the same screen events, so some agreement is built in. Sleep
 * inference itself is checked against the nights you entered (Phase 2c).
 */
object ProxyCheck {
    /**
     * At or above this share of the quietest 5 hours asleep, the screen-use rhythm is read as following sleep. A
     * Nocturne choice (a majority), not a published cut-off: no study gives one for phones.
     */
    const val FOLLOWS_SLEEP_SHARE = 0.5

    fun followsSleep(share: Double): Boolean = share >= FOLLOWS_SLEEP_SHARE

    /**
     * The share of known minutes inside the screen-use L5 window, across the window's counted sleep days, that the
     * sleep estimates call asleep. Withheld whenever L5 is, or when too few nights have a verdict.
     */
    fun quietestHoursAsleep(activity: List<ActivityDay>, sleep: List<SleepDay>): MetricResult {
        val l5 = LeastActive5.compute(activity)
        if (l5 !is MetricResult.Score) return l5
        val requirement = LeastActive5.requirement
        SufficiencyGate.checkNights(sleep, requirement)?.let { return it }
        val valid = SufficiencyGate.validDays(sleep, requirement)
        val clockMinute = l5.atMinute ?: return MetricResult.Withheld(WithheldReason.LOW_COVERAGE, 0, (100 * requirement.minCoverage).toInt())
        val start = Math.floorMod(clockMinute - LocalClock.NIGHT_BOUNDARY_HOUR * 60, SleepDay.EPOCHS)
        var asleep = 0
        var known = 0
        for (day in valid) {
            for (i in 0 until L5_MINUTES) {
                when (day[(start + i) % SleepDay.EPOCHS]) {
                    SleepDay.ASLEEP -> {
                        asleep++
                        known++
                    }
                    SleepDay.AWAKE -> known++
                }
            }
        }
        SufficiencyGate.checkCoverage(known.toLong(), valid.size.toLong() * L5_MINUTES, requirement)?.let { return it }
        return MetricResult.Score(asleep.toDouble() / known, valid.size, known.toDouble() / (valid.size * L5_MINUTES), atMinute = clockMinute)
    }

    private const val L5_MINUTES = 5 * 60
}
