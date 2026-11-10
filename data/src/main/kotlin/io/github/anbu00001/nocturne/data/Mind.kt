package io.github.anbu00001.nocturne.data

import androidx.room.withTransaction
import io.github.anbu00001.nocturne.core.detect.ChangeSentinel
import io.github.anbu00001.nocturne.core.metrics.MetricKey
import io.github.anbu00001.nocturne.core.mind.AlarmGroup
import io.github.anbu00001.nocturne.core.mind.AlarmLadder
import io.github.anbu00001.nocturne.core.mind.CheckIn
import io.github.anbu00001.nocturne.core.mind.MindDay
import io.github.anbu00001.nocturne.core.mind.MoodLinks
import io.github.anbu00001.nocturne.core.mind.OnsetJumps
import io.github.anbu00001.nocturne.core.music.MusicNights
import io.github.anbu00001.nocturne.core.reflect.ReflectionSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The daily check-in, kept in the reflections table (source MOOD): gapStartTs and gapEndTs are the day it is about, from
 * 04:00 to 04:00 local, promptedAt and answeredAt the tap, rating the taps packed by [CheckIn.rating]. One per day; a
 * later tap replaces it.
 */
class CheckIns(private val db: NocturneDatabase) {

    data class Entry(val day: LocalDate, val checkIn: CheckIn, val at: Long)

    suspend fun save(checkIn: CheckIn, now: Long, offsetMinutes: Int): Entry = db.withTransaction {
        val day = CheckIn.dayOf(now, offsetMinutes)
        val start = CheckIn.dayStartUtc(day, offsetMinutes)
        db.reflections().deleteCheckIn(start)
        db.reflections().insert(
            ReflectionEntity(
                promptedAt = now, answeredAt = now, gapStartTs = start, gapEndTs = start + LocalClock.DAY_MS,
                rating = checkIn.rating, note = null, dismissed = false, source = ReflectionSource.MOOD,
            ),
        )
        Entry(day, checkIn, now)
    }

    /** Today's answer, if one was given since 04:00. */
    suspend fun today(now: Long, offsetMinutes: Int): Entry? {
        val start = CheckIn.dayStartUtc(CheckIn.dayOf(now, offsetMinutes), offsetMinutes)
        return all(offsetMinutes).lastOrNull { CheckIn.dayStartUtc(it.day, offsetMinutes) == start }
    }

    /** Every check-in, oldest first. The day is read back at [offsetMinutes]; a zone change moves no stored instant. */
    suspend fun all(offsetMinutes: Int): List<Entry> = db.reflections().checkIns().mapNotNull { it.toEntry(offsetMinutes) }
}

internal fun ReflectionEntity.toEntry(offsetMinutes: Int): CheckIns.Entry? {
    val checkIn = CheckIn.ofRating(rating) ?: return null
    val start = gapStartTs ?: return null
    // The middle of the stored day, so an offset that differs by an hour or two from the one at the tap still names the same day.
    return CheckIns.Entry(CheckIn.dayOf(start + LocalClock.DAY_MS / 2, offsetMinutes), checkIn, answeredAt ?: promptedAt)
}

/**
 * Patterns' "Rhythm" section: what the stored history shows about the shape of days and nights, and the check-in beside
 * it. Read from what recomputes wrote, nothing stored. It describes behaviour; it measures no mood and no hormone.
 */
class MindHistory(private val db: NocturneDatabase) {

    data class Report(
        /** Onset from one night to the next over the last [SPAN_NIGHTS] nights; null with too few neighbouring nights. */
        val jumps: OnsetJumps?,
        /** The 28-night social jetlag the regularity windows already hold, in minutes; null while withheld. */
        val socialJetlagMinutes: Double?,
        /** Alarm mornings after Sunday to Wednesday nights, and after the others, over the last [SPAN_NIGHTS] nights. */
        val alarmsEarlyWeek: AlarmGroup?,
        val alarmsOther: AlarmGroup?,
        /** Music minutes by part of the local day over the last [SPAN_NIGHTS] days: 04 to 12, 12 to 18, 18 to 04. */
        val musicMorning: Int,
        val musicAfternoon: Int,
        val musicEvening: Int,
        /** Median unlocks a day, the last 7 full days and the [SPAN_NIGHTS] minus 7 before. */
        val pickupsWeek: Double?,
        val pickupsBefore: Double?,
        val checkIns: List<CheckIns.Entry>,
        val links: MoodLinks.Report,
    )

    suspend fun report(now: Long, offsetMinutes: Int): Report {
        val today = CheckIn.dayOf(now, offsetMinutes)
        val from = today.minusDays(SPAN_NIGHTS.toLong())
        val nights = db.sleep().nights()
        val recent = nights.filter { LocalDate.parse(it.dateOfNight) >= from }
        val onsets = recent.mapNotNull { n -> n.estimatedSleepOnset?.let { LocalDate.parse(n.dateOfNight) to ChangeSentinel.onsetMinutesAfterNoon(it, n.utcOffsetMinutes) } }.toMap()

        val latest = nights.lastOrNull { it.noSleep || it.estimatedSleepOnset != null }?.dateOfNight
        val jetlag = db.metrics().allWindows()
            .firstOrNull { it.endDate == latest && it.windowDays == 28 && it.metric == MetricKey.SOCIAL_JETLAG.name }?.value

        val alarms = db.reflections().alarmWakes(from.toString()).groupBy({ LocalDate.parse(it.nightDate) }, { it.startTs })
        val judged = recent.map { LocalDate.parse(it.dateOfNight) }
        val (early, other) = judged.partition { it.dayOfWeek in EARLY_WEEK }

        // Music and unlocks by check-in day (04:00 to 04:00), so a day here is the day a check-in is about.
        val music = MusicHistory(db).spans()
        fun dayStart(day: LocalDate) = CheckIn.dayStartUtc(day, offsetMinutes)
        fun musicMs(day: LocalDate, fromHour: Int, toHour: Int) = MusicNights.msWithin(
            music, dayStart(day) + (fromHour - CheckIn.DAY_START_HOUR) * LocalClock.HOUR_MS, dayStart(day) + (toHour - CheckIn.DAY_START_HOUR) * LocalClock.HOUR_MS,
        )
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it < today }.toList()
        fun minutes(fromHour: Int, toHour: Int) = (days.sumOf { musicMs(it, fromHour, toHour) } / LocalClock.MINUTE_MS).toInt()
        val unlocks = db.reflections().unlocksFrom(dayStart(from)).groupingBy { CheckIn.dayOf(it, offsetMinutes) }.eachCount()
        val firstData = db.rawEvents().firstTimestamp()?.let { CheckIn.dayOf(it, offsetMinutes) } ?: today
        val counted = days.filter { it > firstData }.map { (unlocks[it] ?: 0).toDouble() }

        val checkIns = CheckIns(db).all(offsetMinutes)
        val sleepByMorning = nights.mapNotNull { n ->
            val onset = n.estimatedSleepOnset ?: return@mapNotNull null
            val wake = n.estimatedWakeTime ?: return@mapNotNull null
            LocalDate.parse(n.dateOfNight).plusDays(1) to (wake - onset).toDouble() / LocalClock.HOUR_MS
        }.toMap()
        val mindDays = checkIns.map { entry ->
            MindDay(
                day = entry.day,
                checkIn = entry.checkIn,
                sleepHours = sleepByMorning[entry.day],
                musicHours = musicMs(entry.day, CheckIn.DAY_START_HOUR, CheckIn.DAY_START_HOUR + 24).toDouble() / LocalClock.HOUR_MS,
                pickups = unlocks[entry.day]?.toDouble(),
            )
        }
        return Report(
            jumps = OnsetJumps.of(onsets),
            socialJetlagMinutes = jetlag,
            alarmsEarlyWeek = AlarmLadder.group(alarms.filterKeys { it in early }, early.size),
            alarmsOther = AlarmLadder.group(alarms.filterKeys { it in other }, other.size),
            musicMorning = minutes(4, 12),
            musicAfternoon = minutes(12, 18),
            musicEvening = minutes(18, 28),
            pickupsWeek = counted.takeLast(7).takeIf { it.size == 7 }?.let(::medianOf),
            pickupsBefore = counted.dropLast(7).takeIf { it.size >= 7 }?.let(::medianOf),
            checkIns = checkIns,
            links = MoodLinks.of(mindDays),
        )
    }

    private fun medianOf(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private companion object {
        const val SPAN_NIGHTS = 35

        /** Nights before the early working days, where this history holds its ladders of alarms. */
        val EARLY_WEEK = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
    }
}
