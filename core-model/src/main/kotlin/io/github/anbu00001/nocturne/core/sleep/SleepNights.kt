package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/** The user's own "I slept about X to Y" (spec §6.3). Primary data: kept, never derived. */
data class SleepReport(val date: LocalDate, val onsetTs: Long, val wakeTs: Long)

/** A session with the UTC offset captured for its own start. */
data class OffsetSession(val session: NightSession, val offsetMinutes: Int)

data class NightSleep(
    val date: LocalDate,
    val offsetMinutes: Int,
    val inferred: SleepEstimate?,
    val report: SleepReport?,
    /** The user's report where there is one, else the inference with any corrective offset applied. */
    val onsetTs: Long?,
    val wakeTs: Long?,
    val confidence: Double,
    val source: SleepSource,
    val interruptions: Int,
) {
    /** Priors learn from the user's word or the raw inference, never a corrected value that would feed back on itself. */
    internal val priorOnsetTs: Long? get() = report?.onsetTs ?: inferred?.onsetTs
    internal val priorWakeTs: Long? get() = report?.wakeTs ?: inferred?.wakeTs

    internal fun usable(config: SleepConfig) = report != null || (inferred != null && inferred.confidence >= config.confidentAt)
}

/**
 * The systematic gap between where the phone went quiet and when the user says they fell asleep, learned
 * from their own reports: the "corrective terms" of Abdullah et al., UbiComp 2016.
 */
data class CorrectiveOffsets(val onsetMs: Long, val wakeMs: Long, val nights: Int) {
    companion object {
        val NONE = CorrectiveOffsets(0, 0, 0)

        fun from(nights: List<NightSleep>, config: SleepConfig): CorrectiveOffsets {
            val pairs = nights.mapNotNull { n -> n.report?.let { r -> n.inferred?.let { i -> r to i } } }
            if (pairs.size < config.minCorrectionNights) return NONE
            fun clamp(ms: Double) = ms.toLong().coerceIn(-config.maxCorrectionMs, config.maxCorrectionMs)
            return CorrectiveOffsets(
                onsetMs = clamp(median(pairs.map { (r, i) -> (r.onsetTs - i.onsetTs).toDouble() })),
                wakeMs = clamp(median(pairs.map { (r, i) -> (r.wakeTs - i.wakeTs).toDouble() })),
                nights = pairs.size,
            )
        }
    }
}

data class HabitualWindow(
    val window: EveningWindow,
    val personalised: Boolean,
    /** Nights the estimate rests on; 0 when provisional. */
    val nights: Int,
    val onsetMinute: Int? = null,
    val wakeMinute: Int? = null,
) {
    companion object {
        val PROVISIONAL = HabitualWindow(EveningWindow.PROVISIONAL, personalised = false, nights = 0)
    }
}

object SleepNights {
    /** Sessions are grouped from 17:00 on a night's evening to 17:00 the next day, which covers its search window. */
    const val GROUP_START_HOUR = 17

    fun nightOf(ts: Long, offsetMinutes: Int): LocalDate =
        LocalDate.ofEpochDay(Math.floorDiv(LocalClock.localMillis(ts, offsetMinutes) - GROUP_START_HOUR * LocalClock.HOUR_MS, LocalClock.DAY_MS))

    /** UTC instant at which the sessions for [date] begin. */
    fun groupStartUtc(date: LocalDate, offsetMinutes: Int): Long =
        localMidnightUtc(date, offsetMinutes) + GROUP_START_HOUR * LocalClock.HOUR_MS

    fun inputs(
        sessions: List<OffsetSession>,
        charging: List<LongRange> = emptyList(),
        dataFromTs: Long = Long.MIN_VALUE,
        dataToTs: Long = Long.MAX_VALUE,
    ): List<NightInput> =
        sessions.groupBy { nightOf(it.session.startTs, it.offsetMinutes) }
            .toSortedMap()
            .map { (date, group) ->
                val sorted = group.sortedBy { it.session.startTs }
                val offset = sorted.first().offsetMinutes
                val from = groupStartUtc(date, offset)
                val to = from + LocalClock.DAY_MS
                NightInput(date, offset, sorted.map { it.session }, charging.filter { it.last > from && it.first < to }, dataFromTs, dataToTs)
            }

    /**
     * Charging samples from harvester runs as intervals. A charging phone is out of Doze, so runs come every
     * 15 min; each charging sample is taken to hold until the next sample, but never longer than [maxGapMs].
     */
    fun chargingIntervals(samples: List<Pair<Long, Boolean>>, maxGapMs: Long = 30 * LocalClock.MINUTE_MS): List<LongRange> {
        val sorted = samples.sortedBy { it.first }
        val intervals = ArrayList<LongRange>()
        sorted.forEachIndexed { i, (ts, charging) ->
            if (!charging) return@forEachIndexed
            val end = minOf(sorted.getOrNull(i + 1)?.first ?: Long.MAX_VALUE, ts + maxGapMs)
            val last = intervals.lastOrNull()
            if (last != null && last.last >= ts) intervals[intervals.lastIndex] = last.first..maxOf(last.last, end) else intervals += ts..end
        }
        return intervals
    }

    /**
     * Infers [inputs] in date order, each night's personal prior drawn from the nights before it.
     * [context] holds already-computed earlier nights; results for a night never depend on later ones
     * except through [CorrectiveOffsets], which change only when reports do. So recomputing from any
     * night onwards with the earlier nights as context gives the same rows as recomputing everything.
     */
    fun infer(
        context: List<NightSleep>,
        inputs: List<NightInput>,
        reports: Map<LocalDate, SleepReport>,
        config: SleepConfig = SleepConfig(),
    ): List<NightSleep> {
        val sortedInputs = inputs.sortedBy { it.date }
        val firstDate = sortedInputs.firstOrNull()?.date ?: return emptyList()
        val inference = SleepInference(config)
        val known = context.filter { it.date < firstDate }.sortedBy { it.date }.toMutableList()
        val fresh = ArrayList<NightSleep>()
        for (input in sortedInputs) {
            val earliest = input.date.minusDays(config.priorNights.toLong())
            val prior = personalPrior(known.filter { it.date >= earliest && it.date < input.date }, config)
            val night = NightSleep(
                date = input.date,
                offsetMinutes = input.offsetMinutes,
                inferred = inference.infer(input, prior),
                report = reports[input.date],
                onsetTs = null,
                wakeTs = null,
                confidence = 0.0,
                source = SleepSource.INFERRED,
                interruptions = 0,
            )
            known += night
            fresh += night
        }
        val offsets = CorrectiveOffsets.from(known, config)
        return fresh.zip(sortedInputs) { night, input -> finalise(night, offsets, input.sessions, config) }
    }

    internal fun finalise(night: NightSleep, offsets: CorrectiveOffsets, sessions: List<NightSession>, config: SleepConfig): NightSleep {
        val report = night.report
        val inferred = night.inferred
        return when {
            report != null -> night.copy(
                onsetTs = report.onsetTs,
                wakeTs = report.wakeTs,
                confidence = 1.0,
                source = SleepSource.USER_REPORTED,
                interruptions = interruptionsBetween(sessions, report.onsetTs, report.wakeTs, config),
            )
            inferred != null -> {
                val onset = inferred.onsetTs + offsets.onsetMs
                val wake = inferred.wakeTs + offsets.wakeMs
                val corrected = wake > onset
                night.copy(
                    onsetTs = if (corrected) onset else inferred.onsetTs,
                    wakeTs = if (corrected) wake else inferred.wakeTs,
                    confidence = inferred.confidence,
                    source = SleepSource.INFERRED,
                    interruptions = inferred.interruptions,
                )
            }
            else -> night
        }
    }

    fun interruptionsBetween(sessions: List<NightSession>, fromTs: Long, toTs: Long, config: SleepConfig = SleepConfig()): Int =
        marksOf(sessions, config).count { it.startTs in fromTs until toTs && it.activeEndTs > fromTs }

    /**
     * Spec §6.3: evening window = habitual onset − 3 h until habitual wake, from at least 7 nights,
     * recomputed weekly. The estimate for a night uses the 4 weeks before the Monday of its week, so a
     * night's window is fixed once its week starts. Until 4 weeks of history exist, the earliest
     * nights stand in for the weeks before them.
     */
    fun windowFor(date: LocalDate, nights: List<NightSleep>, config: SleepConfig = SleepConfig()): HabitualWindow =
        windowsFor(listOf(date), nights, config).getValue(date)

    /** [windowFor] for many dates; dates in the same week share one computation. */
    fun windowsFor(dates: Collection<LocalDate>, nights: List<NightSleep>, config: SleepConfig = SleepConfig()): Map<LocalDate, HabitualWindow> {
        val usable = nights.filter { it.onsetTs != null && it.wakeTs != null && it.usable(config) }.sortedBy { it.date }
        val earliest = usable.take(config.windowLookbackDays)
        val byWeek = HashMap<LocalDate, HabitualWindow>()
        return dates.associateWith { date ->
            val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            byWeek.getOrPut(weekStart) {
                val from = weekStart.minusDays(config.windowLookbackDays.toLong())
                val recent = usable.filter { it.date >= from && it.date < weekStart }
                val basis = if (recent.size >= config.minWindowNights) recent else earliest.takeIf { it.size >= config.minWindowNights }
                basis?.let(::habitual) ?: HabitualWindow.PROVISIONAL
            }
        }
    }

    private fun habitual(basis: List<NightSleep>): HabitualWindow {
        val onsetSinceNoon = median(basis.map { minutesSinceNoon(it.onsetTs!!, it.offsetMinutes) })
        val onsetMinute = Math.floorMod(onsetSinceNoon.roundToInt() + 12 * 60, LocalClock.MINUTES_PER_DAY)
        val wakeMinute = Math.floorMod(
            median(basis.map { LocalClock.minuteOfDay(it.wakeTs!!, it.offsetMinutes).toDouble() }).roundToInt(),
            LocalClock.MINUTES_PER_DAY,
        )
        return HabitualWindow(EveningWindow.fromSleepOnset(onsetMinute, wakeMinute), true, basis.size, onsetMinute, wakeMinute)
    }
}

internal const val MAD_TO_SD = 1.4826

internal fun personalPrior(previous: List<NightSleep>, config: SleepConfig): PersonalPrior? {
    val usable = previous.filter { it.usable(config) && it.priorOnsetTs != null && it.priorWakeTs != null }
    if (usable.size < config.minPriorNights) return null
    val onsets = usable.map { minutesSinceNoon(it.priorOnsetTs!!, it.offsetMinutes) }
    val durations = usable.map { (it.priorWakeTs!! - it.priorOnsetTs!!) / 60_000.0 }
    return PersonalPrior(
        onsetMinutesSinceNoon = median(onsets),
        onsetSdMin = maxOf(MAD_TO_SD * mad(onsets), config.minOnsetSdMin),
        durationMin = median(durations),
        durationSdMin = maxOf(MAD_TO_SD * mad(durations), config.minDurationSdMin),
        nights = usable.size,
    )
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

internal fun mad(values: List<Double>): Double {
    val m = median(values)
    return median(values.map { abs(it - m) })
}
