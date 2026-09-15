package io.github.anbu00001.nocturne.core.metrics

import java.time.LocalDate

/**
 * Bump when any metric's formula or pinned choice changes, or a metric is added; derived rows then carry a new model
 * run (analytics §6.2). 2: the sleep check ([ProxyCheck]) and L5/M10 ties going to the middle of a quiet plateau.
 */
const val METRICS_VERSION = 2

/**
 * Stable keys stored with every window row. Never rename one; add a new key instead. IS_SLEEP, IV_SLEEP and L5_ASLEEP
 * check the screen-use keys against sleep inference ([ProxyCheck]).
 */
enum class MetricKey { SRI, ONSET_SD, SOCIAL_JETLAG, CPD, IS, IV, L5, M10, RA, CFI, IS_SLEEP, IV_SLEEP, L5_ASLEEP }

/** One night as the regularity metrics see it. */
data class NightRecord(
    val date: LocalDate,
    val offsetMinutes: Int,
    val onsetTs: Long?,
    val wakeTs: Long?,
    val noSleep: Boolean,
) {
    /** A verdict exists: sleep times, or the finding that there was no sleep. */
    val judged: Boolean get() = noSleep || (onsetTs != null && wakeTs != null)
}

data class WindowValue(val endDate: LocalDate, val windowDays: Int, val metric: MetricKey, val result: MetricResult)

/**
 * Tier 1 of the analytics layer (NOCTURNE_ANALYTICS.md §4): every regularity metric over rolling windows that end on
 * a night. A pure function of nights, screen-on spans and how far the history reaches, so recomputing from any night
 * gives the same values as recomputing everything.
 *
 * How the days are built:
 * - A sleep day (noon to noon) is known only where the history reaches and only when its night has a verdict; a night
 *   still under way, or never estimated, leaves its whole day unknown rather than awake.
 * - Sleep inside a day comes from that night and its neighbours, so a wake after noon lands in the next day.
 * - An activity day is the share of each minute the screen was on, known wherever the history reaches.
 * - A wakefulness day is the sleep day as 1 awake and 0 asleep, the reference the screen-use figures are checked with.
 */
object RegularityWindows {
    val WINDOW_DAYS = listOf(7, 14, 28)

    fun compute(
        nights: List<NightRecord>,
        screen: List<LongRange>,
        dataFromTs: Long,
        dataToTs: Long,
        endDates: Collection<LocalDate>,
        freeNights: FreeNights = FreeNights(),
        windows: List<Int> = WINDOW_DAYS,
    ): List<WindowValue> {
        val byDate = nights.associateBy { it.date }
        val sortedDates = byDate.keys.sorted()
        val spans = screen.sortedBy { it.first }
        val spanStarts = LongArray(spans.size) { spans[it].first }
        val cpd = CompositePhaseDeviation(freeNightsPerWeek = freeNights.days.size)

        fun offsetFor(date: LocalDate): Int =
            byDate[date]?.offsetMinutes ?: sortedDates.lastOrNull { it < date }?.let { byDate.getValue(it).offsetMinutes } ?: 0

        val sleepDays = HashMap<LocalDate, SleepDay>()
        fun sleepDay(date: LocalDate): SleepDay = sleepDays.getOrPut(date) {
            val night = byDate[date]
            if (night == null || !night.judged) {
                SleepDay(date, ByteArray(SleepDay.EPOCHS) { SleepDay.UNKNOWN })
            } else {
                val intervals = listOfNotNull(byDate[date.minusDays(1)], night, byDate[date.plusDays(1)]).mapNotNull { n ->
                    val onset = n.onsetTs
                    val wake = n.wakeTs
                    if (onset != null && wake != null && wake > onset) onset until wake else null
                }
                SleepDay.fromIntervals(date, night.offsetMinutes, intervals, dataFromTs, dataToTs)
            }
        }

        val wakeDays = HashMap<LocalDate, ActivityDay>()
        fun wakeDay(date: LocalDate): ActivityDay = wakeDays.getOrPut(date) { ActivityDay.fromSleepDay(sleepDay(date)) }

        val activityDays = HashMap<LocalDate, ActivityDay>()
        fun activityDay(date: LocalDate): ActivityDay = activityDays.getOrPut(date) {
            val offset = offsetFor(date)
            val noon = noonUtc(date, offset)
            val dayEnd = noon + SleepDay.EPOCHS * 60_000L
            // Spans are sorted by start; any span that reaches this day starts before its end.
            var i = lowerBound(spanStarts, dayEnd) - 1
            val inDay = ArrayList<LongRange>()
            while (i >= 0) {
                val span = spans[i]
                if (span.last + 1 > noon) inDay += span
                if (span.first < noon - MAX_SPAN_MS) break
                i--
            }
            ActivityDay.fromScreenSpans(date, offset, inDay, dataFromTs, dataToTs)
        }

        fun timing(date: LocalDate): NightTiming {
            val night = byDate[date]
            return NightTiming(date, night?.offsetMinutes ?: offsetFor(date), night?.onsetTs, night?.wakeTs, freeNights.isFree(date))
        }

        val values = ArrayList<WindowValue>()
        for (end in endDates.sorted()) {
            for (length in windows) {
                val dates = (length - 1 downTo 0).map { end.minusDays(it.toLong()) }
                val sleep = dates.map(::sleepDay)
                val wake = dates.map(::wakeDay)
                val activity = dates.map(::activityDay)
                val timings = dates.map(::timing)
                fun add(metric: MetricKey, result: MetricResult) {
                    values += WindowValue(end, length, metric, result)
                }
                add(MetricKey.SRI, SleepRegularityIndex.compute(sleep))
                add(MetricKey.ONSET_SD, SleepOnsetVariability.compute(timings))
                add(MetricKey.SOCIAL_JETLAG, SocialJetlag.compute(timings))
                add(MetricKey.CPD, cpd.compute(timings))
                add(MetricKey.IS, InterdailyStability.compute(activity))
                add(MetricKey.IV, IntradailyVariability.compute(activity))
                add(MetricKey.L5, LeastActive5.compute(activity))
                add(MetricKey.M10, MostActive10.compute(activity))
                add(MetricKey.RA, RelativeAmplitude.compute(activity))
                add(MetricKey.CFI, CircadianFunctionIndex.compute(activity))
                add(MetricKey.IS_SLEEP, InterdailyStability.compute(wake))
                add(MetricKey.IV_SLEEP, IntradailyVariability.compute(wake))
                add(MetricKey.L5_ASLEEP, ProxyCheck.quietestHoursAsleep(activity, sleep))
            }
        }
        return values
    }

    /** A screen session longer than this is not expected; it only bounds the backwards search for spans. */
    private const val MAX_SPAN_MS = 24 * 60 * 60_000L

    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
