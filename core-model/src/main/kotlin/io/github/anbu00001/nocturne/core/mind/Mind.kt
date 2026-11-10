package io.github.anbu00001.nocturne.core.mind

import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One tap a day on how the day felt: [pleasant] and [energy] from 1 (low) to 5 (high), picked together on a five by five
 * grid, and optionally [tension] on the same scale. It is the only ground truth the app has for how a day was; nothing
 * else here measures mood, and no hormone can be read from a phone. A single tap supports broad pleasant/unpleasant and
 * low/high energy and nothing finer, so the app reports the numbers as given and names no emotion from them.
 */
data class CheckIn(val pleasant: Int, val energy: Int, val tension: Int? = null) {
    init {
        require(pleasant in 1..5 && energy in 1..5 && (tension == null || tension in 1..5)) { "check-in values run 1 to 5" }
    }

    /** One integer for the reflections table's rating column: hundreds pleasant, tens energy, units tension (0 for none). */
    val rating: Int get() = pleasant * 100 + energy * 10 + (tension ?: 0)

    companion object {
        /** Local hour a check-in day starts: a tap at 01:00, before sleep, is about the day that is ending. */
        const val DAY_START_HOUR = 4

        fun ofRating(rating: Int?): CheckIn? {
            if (rating == null) return null
            val pleasant = rating / 100
            val energy = rating / 10 % 10
            val tension = rating % 10
            return if (pleasant in 1..5 && energy in 1..5 && tension in 0..5) CheckIn(pleasant, energy, tension.takeIf { it > 0 }) else null
        }

        /** The day an instant's check-in is about, and that day's start as an instant. */
        fun dayOf(ts: Long, offsetMinutes: Int): LocalDate =
            LocalDate.ofEpochDay(Math.floorDiv(LocalClock.localMillis(ts, offsetMinutes) - DAY_START_HOUR * LocalClock.HOUR_MS, LocalClock.DAY_MS))

        fun dayStartUtc(day: LocalDate, offsetMinutes: Int): Long =
            day.toEpochDay() * LocalClock.DAY_MS + DAY_START_HOUR * LocalClock.HOUR_MS - offsetMinutes * LocalClock.MINUTE_MS
    }
}

/** How far sleep onset moves from one night to the next, over nights that follow each other. */
data class OnsetJumps(val pairs: Int, val medianJumpMinutes: Double, val overTwoHours: Int) {
    companion object {
        const val BIG_JUMP_MINUTES = 120.0
        const val MIN_PAIRS = 7

        /** [onsets] by night, in minutes after noon; only nights one day apart are compared. Null under [MIN_PAIRS] pairs. */
        fun of(onsets: Map<LocalDate, Double>): OnsetJumps? {
            val jumps = onsets.entries.mapNotNull { (date, onset) -> onsets[date.plusDays(1)]?.let { abs(it - onset) } }
            if (jumps.size < MIN_PAIRS) return null
            return OnsetJumps(jumps.size, median(jumps), jumps.count { it > BIG_JUMP_MINUTES })
        }
    }
}

/** A morning's alarms: how many times the alarm lit the screen, and the minutes from the first to the last. */
data class AlarmMorning(val night: LocalDate, val alarms: Int, val spanMinutes: Double)

/** Mornings with alarms in one group of nights. Description only: what the mornings look like, and nothing about what they do. */
data class AlarmGroup(val nights: Int, val mornings: Int, val medianAlarms: Double, val medianSpanMinutes: Double)

object AlarmLadder {
    /** [alarmTimes] by night; [nights] is how many nights the group holds in all, so mornings without an alarm show as the gap. */
    fun group(alarmTimes: Map<LocalDate, List<Long>>, nights: Int): AlarmGroup? {
        val mornings = alarmTimes.filterValues { it.isNotEmpty() }.map { (night, times) ->
            AlarmMorning(night, times.size, (times.max() - times.min()).toDouble() / LocalClock.MINUTE_MS)
        }
        if (mornings.isEmpty()) return null
        return AlarmGroup(nights, mornings.size, median(mornings.map { it.alarms.toDouble() }), median(mornings.map { it.spanMinutes }))
    }
}

/** One day for [MoodLinks]: the check-in and the behaviour beside it. A null behaviour is a day it was not recorded. */
data class MindDay(
    val day: LocalDate,
    val checkIn: CheckIn,
    /** Sleep of the night that ended on this day, in hours. */
    val sleepHours: Double?,
    /** Music on this day, in hours. */
    val musicHours: Double?,
    /** Times the phone was unlocked on this day. */
    val pickups: Double?,
)

/**
 * How a check-in moved with a behaviour, in this person's own days. [slope] is the change in the rating (points on the 1
 * to 5 scale) that went with one more unit of the behaviour than the day before; [low] and [high] are an interval of two
 * standard errors either side.
 */
data class MoodLink(val pairs: Int, val slope: Double, val low: Double, val high: Double)

/**
 * Links between the check-in and behaviour, withheld until there are [MIN_DAYS] days with both. Three links, fixed here
 * before any answer existed, so none can be picked for looking large.
 *
 * Daily ratings follow yesterday's, and so does sleep, so two drifting series correlate by themselves. Each link is
 * therefore computed on day-to-day differences (today minus yesterday, consecutive days only), which removes a person's
 * level and slow drift, and is given with an interval. It is an association in unrandomised days and cannot show cause
 * (Daza, Methods Inf Med 2018); days without a check-in are not missing at random either.
 */
object MoodLinks {
    const val MIN_DAYS = 28

    /** Fewer difference pairs than this and no slope is given even past [MIN_DAYS]: scattered days leave few neighbours. */
    const val MIN_PAIRS = 14

    data class Report(val days: Int, val sleepEnergy: MoodLink?, val sleepPleasant: MoodLink?, val musicPleasant: MoodLink?) {
        val withheld: Boolean get() = days < MIN_DAYS
    }

    fun of(days: List<MindDay>): Report {
        val paired = days.filter { it.sleepHours != null }
        if (paired.size < MIN_DAYS) return Report(paired.size, null, null, null)
        return Report(
            days = paired.size,
            sleepEnergy = link(days, { it.sleepHours }, { it.checkIn.energy.toDouble() }),
            sleepPleasant = link(days, { it.sleepHours }, { it.checkIn.pleasant.toDouble() }),
            musicPleasant = link(days, { it.musicHours }, { it.checkIn.pleasant.toDouble() }),
        )
    }

    /** Least squares through the origin on first differences; null with too few pairs or a behaviour that never changed. */
    internal fun link(days: List<MindDay>, behaviour: (MindDay) -> Double?, rating: (MindDay) -> Double): MoodLink? {
        val byDay = days.associateBy { it.day }
        val diffs = days.mapNotNull { today ->
            val yesterday = byDay[today.day.minusDays(1)] ?: return@mapNotNull null
            val x = behaviour(today) ?: return@mapNotNull null
            val x0 = behaviour(yesterday) ?: return@mapNotNull null
            (x - x0) to (rating(today) - rating(yesterday))
        }
        val sxx = diffs.sumOf { it.first * it.first }
        if (diffs.size < MIN_PAIRS || sxx == 0.0) return null
        val slope = diffs.sumOf { it.first * it.second } / sxx
        val residual = diffs.sumOf { (dx, dy) -> (dy - slope * dx) * (dy - slope * dx) }
        val se = sqrt(residual / (diffs.size - 1) / sxx)
        return MoodLink(diffs.size, slope, slope - 2 * se, slope + 2 * se)
    }
}

internal fun median(values: List<Double>): Double {
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
