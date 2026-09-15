package io.github.anbu00001.nocturne.core.circadian

import io.github.anbu00001.nocturne.core.light.Band
import io.github.anbu00001.nocturne.core.light.LightAssumptions
import io.github.anbu00001.nocturne.core.light.LightReading
import io.github.anbu00001.nocturne.core.sleep.localMidnightUtc
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.core.time.ZoneTimeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong

/**
 * What the model's light input assumes wherever the phone measured nothing. The phone reads the room only while the
 * screen is on, so most waking light is assumed, and each value here is a stated assumption. The estimate runs under
 * four combinations ([LightScenario]) so its answer carries their spread.
 *
 * The median of your own readings is one of the combinations, not the middle: the phone reads the room while it is in
 * use, mostly indoors and often in bed, so its readings are a biased sample of the hours it misses. On the A18's first
 * day of samples they sat far below the priors (a median of 8 lux in daylight hours and 0 lux after dark).
 */
data class CircadianLightAssumptions(
    /** The light sensor faces out of the phone, not out of the eyes: the same band as §6.1. */
    val sensorToEye: Band = LightAssumptions().sensorToEyeFactor,
    /** Local clock minutes when daylight can reach a room, a fixed stand-in for sunrise and sunset. */
    val daylightStartMinute: Int = 6 * 60,
    val daylightEndMinute: Int = 18 * 60,
    /**
     * Awake in daylight hours with nothing measured. Free-living wearable studies find mean light from under 100 to about
     * 500 lux depending on the sensor, and 250 lux melanopic, the recommended daytime minimum, is often missed.
     */
    val daylightPriorLux: Band = Band(50.0, 200.0, 1000.0),
    /** Awake after dark with nothing measured: domestic evening light is typically around 30 lux. */
    val eveningPriorLux: Band = LightAssumptions().eveningAmbientPriorLux,
    /** A reading still describes the room this long after the screen went off, while awake. */
    val carryMs: Long = 30 * LocalClock.MINUTE_MS,
    /** Measured minutes of a kind (daylight hours or after dark) before [LightScenario.OWN_READINGS] uses their median. */
    val ownMedianMinutes: Int = 60,
) {
    init {
        require(daylightStartMinute in 0 until daylightEndMinute && daylightEndMinute <= LocalClock.MINUTES_PER_DAY)
    }
}

/**
 * The combinations the estimate runs. Light before the clock's temperature minimum delays it and light after advances
 * it, so which combination gives the earliest DLMO depends on the person; the estimate reports the spread either way.
 * [OWN_READINGS] fills unmeasured hours with the median of your readings at that part of the day (the priors' middle
 * until there are enough of them).
 */
enum class LightScenario { DIM_DAY_BRIGHT_EVENING, MIDDLE, BRIGHT_DAY_DIM_EVENING, OWN_READINGS }

/** The model's light input, one photopic lux value at the eyes per minute. */
object CircadianLight {

    /**
     * Lux for each of [minutes] minutes from [fromTs]: nothing while asleep; a light sample's reading, scaled to the eyes,
     * where samples cover the minute; the last reading for [CircadianLightAssumptions.carryMs] after the screen goes off;
     * otherwise the scenario's value for daylight hours or after dark. The sensor reads 0 below 1 lux, so 0 stays 0.
     */
    fun build(
        fromTs: Long,
        minutes: Int,
        zones: ZoneTimeline,
        sleep: List<LongRange>,
        readings: List<LightReading>,
        scenario: LightScenario,
        assumptions: CircadianLightAssumptions = CircadianLightAssumptions(),
    ): DoubleArray {
        val sleeps = sleep.filter { !it.isEmpty() }.sortedBy { it.first }
        val samples = readings.filter { it.lux != null && it.durationMs > 0 }.sortedBy { it.startTs }
        val factor = assumptions.sensorToEye
        val (daylightPrior, eveningPrior, dayFactor, eveningFactor) = when (scenario) {
            LightScenario.DIM_DAY_BRIGHT_EVENING ->
                listOf(assumptions.daylightPriorLux.low, assumptions.eveningPriorLux.high, factor.low, factor.high)
            LightScenario.MIDDLE -> listOf(assumptions.daylightPriorLux.mid, assumptions.eveningPriorLux.mid, factor.mid, factor.mid)
            LightScenario.BRIGHT_DAY_DIM_EVENING ->
                listOf(assumptions.daylightPriorLux.high, assumptions.eveningPriorLux.low, factor.high, factor.low)
            LightScenario.OWN_READINGS -> {
                val (day, evening) = ownMedians(fromTs, minutes, zones, sleeps, samples, assumptions)
                listOf(day ?: assumptions.daylightPriorLux.mid, evening ?: assumptions.eveningPriorLux.mid, factor.mid, factor.mid)
            }
        }
        val carryMinutes = (assumptions.carryMs / LocalClock.MINUTE_MS).toInt()

        val lux = DoubleArray(minutes)
        var s = 0
        var r = 0
        var lastSensorLux = Double.NaN
        var lastReadingMinute = Int.MIN_VALUE
        for (m in 0 until minutes) {
            val t = fromTs + m * LocalClock.MINUTE_MS
            val end = t + LocalClock.MINUTE_MS
            while (s < sleeps.size && sleeps[s].last < t) s++
            if (s < sleeps.size && t in sleeps[s]) {
                lux[m] = 0.0
                lastSensorLux = Double.NaN
                continue
            }
            val daylight = isDaylight(t, zones, assumptions)
            val eyeFactor = if (daylight) dayFactor else eveningFactor

            while (r < samples.size && samples[r].endTs <= t) r++
            var weighted = 0.0
            var covered = 0L
            var i = r
            while (i < samples.size && samples[i].startTs < end) {
                val overlap = minOf(end, samples[i].endTs) - maxOf(t, samples[i].startTs)
                if (overlap > 0) {
                    weighted += samples[i].lux!! * overlap
                    covered += overlap
                }
                i++
            }
            lux[m] = when {
                covered > 0 -> {
                    lastSensorLux = weighted / covered
                    lastReadingMinute = m
                    lastSensorLux * eyeFactor
                }
                !lastSensorLux.isNaN() && m - lastReadingMinute <= carryMinutes -> lastSensorLux * eyeFactor
                daylight -> daylightPrior
                else -> eveningPrior
            }
        }
        return lux
    }

    /** Medians of awake readings at the eyes (middle sensor factor), in daylight hours and after dark. */
    private fun ownMedians(
        fromTs: Long,
        minutes: Int,
        zones: ZoneTimeline,
        sleeps: List<LongRange>,
        samples: List<LightReading>,
        assumptions: CircadianLightAssumptions,
    ): Pair<Double?, Double?> {
        val day = ArrayList<Double>()
        val evening = ArrayList<Double>()
        val end = fromTs + minutes * LocalClock.MINUTE_MS
        for (sample in samples) {
            if (sample.endTs <= fromTs || sample.startTs >= end || sleeps.any { sample.startTs in it }) continue
            val eyes = sample.lux!! * assumptions.sensorToEye.mid
            if (isDaylight(sample.startTs, zones, assumptions)) day += eyes else evening += eyes
        }
        // Samples run 30 s, so a minute is two of them.
        fun median(values: List<Double>) = values.sorted().takeIf { it.size >= 2 * assumptions.ownMedianMinutes }?.let { sorted ->
            val half = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[half] else (sorted[half - 1] + sorted[half]) / 2
        }
        return median(day) to median(evening)
    }

    private fun isDaylight(ts: Long, zones: ZoneTimeline, assumptions: CircadianLightAssumptions): Boolean {
        val clock = Math.floorMod((ts / LocalClock.MINUTE_MS + zones.offsetMinutesAt(ts)).toInt(), LocalClock.MINUTES_PER_DAY)
        return clock >= assumptions.daylightStartMinute && clock < assumptions.daylightEndMinute
    }
}

/** A night's modelled dim light melatonin onset under each light scenario, set against habitual sleep. */
data class NightPhase(
    /** The night, by the noon-to-noon boundary the rest of the app uses. */
    val date: LocalDate,
    /** DLMO under each scenario that marked this night. */
    val dlmoByScenario: Map<LightScenario, Long>,
    /** Habitual sleep onset (the week's median) placed on this night. */
    val habitualOnsetTs: Long,
) {
    /** Hours from DLMO to habitual sleep onset under [scenario]. */
    fun phaseAngleHours(scenario: LightScenario): Double? = dlmoByScenario[scenario]?.let { (habitualOnsetTs - it) / LocalClock.HOUR_MS.toDouble() }

    /** Scenarios whose clock fits the sleep: DLMO within [PhaseEstimator.PLAUSIBLE_PHASE_ANGLE_HOURS] of habitual onset. */
    val plausible: Map<LightScenario, Long>
        get() = dlmoByScenario.filterKeys { phaseAngleHours(it)!! in PhaseEstimator.PLAUSIBLE_PHASE_ANGLE_HOURS }

    val earliestTs: Long? get() = plausible.values.minOrNull()
    val latestTs: Long? get() = plausible.values.maxOrNull()

    /**
     * At least two plausible scenarios agree within [PhaseEstimator.SETTLED_SPREAD_MS]: the light the phone could not see
     * does not decide the answer. Only a settled night's DLMO means more than its assumptions.
     */
    val settled: Boolean
        get() = plausible.size >= 2 && latestTs!! - earliestTs!! <= PhaseEstimator.SETTLED_SPREAD_MS

    /** The middle of the plausible range, for a settled night; the model's own error, about ±1 h, comes on top. */
    val dlmoTs: Long? get() = if (settled) (earliestTs!! + latestTs!!) / 2 else null
}

/**
 * Spec §6.4: circadian phase from a phone's light history.
 *
 * - The model starts entrained to the person's own habitual sleep, dark while asleep and [ENTRAINING_LUX] otherwise, as
 *   Huang et al. (SLEEP 2021) started everyone from 16 h of 800 lux and 8 h of dark. Starting from the package's default
 *   state and looping the first week instead left the phase wherever dim light let it drift: at a few lux the model barely
 *   entrains and runs free near 24.2 h.
 * - Light is then built per minute under each [LightScenario] and the model runs through the whole history.
 * - A DLMO counts only after [MIN_DAYS] days of history, as Huang et al. required before comparing with lab DLMO.
 * - A scenario whose DLMO does not fit the week's sleep is set aside ([NightPhase.plausible]). In free-living adults,
 *   model-predicted DLMO comes a mean of about 3 h before sleep onset, SD 1.4 to 2.2 h (PMC12320674); on the A18 the
 *   population priors put it 8 to 12 h before, a clock the sleep itself contradicts.
 */
object PhaseEstimator {
    /** Days of light input before the first DLMO counts. */
    const val MIN_DAYS = 7

    /** Light while awake in the entraining schedule (Huang et al. 2021). */
    const val ENTRAINING_LUX = 800.0

    /** Days of the entraining schedule before the history starts; enough to settle a shift of many hours at 800 lux. */
    const val ENTRAINING_DAYS = 30

    /** Scenarios this close count as agreeing: twice the model's published ±1 h error. */
    const val SETTLED_SPREAD_MS = 2 * LocalClock.HOUR_MS

    /** DLMO to habitual onset, in hours, that a scenario must give: about two SDs either side of the free-living 3 h. */
    val PLAUSIBLE_PHASE_ANGLE_HOURS = -1.0..7.0

    fun estimate(
        fromTs: Long,
        toTs: Long,
        zones: ZoneTimeline,
        sleep: List<LongRange>,
        readings: List<LightReading>,
        model: CircadianModel = Hannay19(),
        assumptions: CircadianLightAssumptions = CircadianLightAssumptions(),
    ): List<NightPhase> {
        val markers = markers(fromTs, toTs, zones, sleep, readings, model, assumptions)
        return markers.values.flatMap { it.keys }.toSortedSet().map { date ->
            val offset = zones.offsetMinutesAt(localMidnightUtc(date, 0))
            val noon = localMidnightUtc(date, offset) + LocalClock.NIGHT_BOUNDARY_HOUR * LocalClock.HOUR_MS
            val week = habitualSleep(sleep, noon - (MIN_DAYS - 1) * LocalClock.DAY_MS, noon + LocalClock.DAY_MS, zones)
            val onset = noon + Math.floorMod(week.onsetMinute - LocalClock.NIGHT_BOUNDARY_HOUR * 60, LocalClock.MINUTES_PER_DAY) * LocalClock.MINUTE_MS
            NightPhase(date, markers.mapNotNull { (scenario, byNight) -> byNight[date]?.let { scenario to it } }.toMap(), onset)
        }
    }

    /** DLMO by night under each scenario, for nights after the first [MIN_DAYS] days. Empty before that. */
    fun markers(
        fromTs: Long,
        toTs: Long,
        zones: ZoneTimeline,
        sleep: List<LongRange>,
        readings: List<LightReading>,
        model: CircadianModel = Hannay19(),
        assumptions: CircadianLightAssumptions = CircadianLightAssumptions(),
    ): Map<LightScenario, Map<LocalDate, Long>> {
        // Start on a local midnight, where the entrained state is defined.
        val offset = zones.offsetMinutesAt(fromTs)
        val firstDate = Instant.ofEpochMilli(fromTs + offset * LocalClock.MINUTE_MS).atOffset(ZoneOffset.UTC).toLocalDate()
        val start = localMidnightUtc(firstDate, offset).let { if (it < fromTs) it + LocalClock.DAY_MS else it }
        val minutes = ((toTs - start) / LocalClock.MINUTE_MS).toInt()
        if (minutes <= MIN_DAYS * LocalClock.MINUTES_PER_DAY) return emptyMap()

        val initial = entrained(model, habitualSleep(sleep, start, start + MIN_DAYS * LocalClock.DAY_MS, zones))
        val hours = DoubleArray(minutes) { it / 60.0 }
        return LightScenario.entries.associateWith { scenario ->
            val lux = CircadianLight.build(start, minutes, zones, sleep, readings, scenario, assumptions)
            Circadian.integrate(model, hours, lux, initial).dlmoHours(model)
                .map { start + (it * LocalClock.HOUR_MS).roundToLong() }
                .filter { it >= start + MIN_DAYS * LocalClock.DAY_MS }
                .associateBy { nightOf(it, zones) }
        }
    }

    /** Habitual sleep as a local clock onset minute and a length in minutes. */
    data class HabitualSleep(val onsetMinute: Int, val minutes: Int)

    /**
     * Median onset (taken in minutes after noon, so late-night and early-morning onsets share one scale) and median length
     * of the sleep that starts between [fromTs] and [toTs]; midnight to 08:00 when there is none.
     */
    fun habitualSleep(sleep: List<LongRange>, fromTs: Long, toTs: Long, zones: ZoneTimeline): HabitualSleep {
        val inRange = sleep.filter { !it.isEmpty() && it.first in fromTs until toTs }
        if (inRange.isEmpty()) return HabitualSleep(0, 8 * 60)
        val afterNoon = inRange.map { span ->
            val clock = Math.floorMod((span.first / LocalClock.MINUTE_MS + zones.offsetMinutesAt(span.first)).toInt(), LocalClock.MINUTES_PER_DAY)
            Math.floorMod(clock - LocalClock.NIGHT_BOUNDARY_HOUR * 60, LocalClock.MINUTES_PER_DAY)
        }
        val lengths = inRange.map { ((it.last + 1 - it.first) / LocalClock.MINUTE_MS).toInt() }
        val onset = (median(afterNoon) + LocalClock.NIGHT_BOUNDARY_HOUR * 60) % LocalClock.MINUTES_PER_DAY
        return HabitualSleep(onset, median(lengths))
    }

    /**
     * The state at local midnight after [ENTRAINING_DAYS] days of the habitual schedule. Each day runs 1441 points, midnight
     * to midnight, so the loop repeats exactly 24 h (a 1440-point day would make the loop a minute short).
     */
    internal fun entrained(model: CircadianModel, habitual: HabitualSleep): DoubleArray {
        val points = LocalClock.MINUTES_PER_DAY + 1
        val hours = DoubleArray(points) { it / 60.0 }
        val lux = DoubleArray(points) { m ->
            if (Math.floorMod(m - habitual.onsetMinute, LocalClock.MINUTES_PER_DAY) < habitual.minutes) 0.0 else ENTRAINING_LUX
        }
        var state = model.defaultInitialState
        repeat(ENTRAINING_DAYS) { state = Circadian.integrate(model, hours, lux, state).states.last() }
        return state
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val half = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[half] else (sorted[half - 1] + sorted[half]) / 2
    }

    private fun nightOf(ts: Long, zones: ZoneTimeline): LocalDate =
        Instant.ofEpochMilli(ts + zones.offsetMinutesAt(ts) * LocalClock.MINUTE_MS - LocalClock.NIGHT_BOUNDARY_HOUR * LocalClock.HOUR_MS)
            .atOffset(ZoneOffset.UTC).toLocalDate()
}
