package io.github.anbu00001.nocturne.ui

import io.github.anbu00001.nocturne.core.digest.PeriodSummary
import io.github.anbu00001.nocturne.core.digest.WeekDigest
import io.github.anbu00001.nocturne.core.music.DayMusic
import io.github.anbu00001.nocturne.core.music.MusicSplit
import io.github.anbu00001.nocturne.core.music.NightMusic
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.MusicHistory
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.data.SleepReportEntity
import io.github.anbu00001.nocturne.tone.Tone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * A [io.github.anbu00001.nocturne.data.Digests.Glance] in sentences. The widget, Patterns and the debug receiver all read
 * these, so what is checked from the laptop over adb is the text the home screen shows.
 */
object GlanceText {

    /** The night's verdict in one line: confirmed, entered or estimated, in that order of whose word it is. */
    fun night(night: NightEntity, report: SleepReportEntity?): String {
        val onset = night.estimatedSleepOnset
        val wake = night.estimatedWakeTime
        val reported = night.source == SleepSource.USER_REPORTED
        val confirmed = reported && report?.confirmed == true
        return when {
            night.noSleep && confirmed -> Tone.Sleep.NO_SLEEP_CONFIRMED
            night.noSleep && reported -> Tone.Sleep.NO_SLEEP_REPORTED
            night.noSleep -> Tone.Sleep.NO_SLEEP_FOUND
            onset == null || wake == null -> Tone.Widget.EMPTY
            else -> {
                val from = clockText(onset, night.utcOffsetMinutes)
                val to = clockText(wake, night.utcOffsetMinutes)
                when {
                    confirmed -> Tone.Sleep.confirmed(from, to)
                    reported -> Tone.Sleep.reported(from, to)
                    else -> Tone.Widget.estimated(from, to, confidenceLabel(night.confidence))
                }
            }
        }
    }

    /** A tap can only confirm an estimate: a night the user already entered or confirmed has nothing left to ask. */
    fun canConfirm(night: NightEntity, report: SleepReportEntity?): Boolean =
        report == null && night.source == SleepSource.INFERRED && (night.noSleep || night.estimatedSleepOnset != null)

    /** The widget's line for the week, or null while too few of the seven nights have times. */
    fun weekShort(digest: WeekDigest): String? =
        digest.week?.let { Tone.Week.short(it.nights, onsetClock(it.medianOnsetMinute), it.onsetSpreadMinutes?.let(::span)) }

    /** The widget's line for the four weeks before; only beside a week line, never by itself. */
    fun beforeShort(digest: WeekDigest): String? =
        digest.baseline?.takeIf { digest.week != null }?.let { Tone.Week.shortBefore(onsetClock(it.medianOnsetMinute), it.onsetSpreadMinutes?.let(::span)) }

    /** Patterns' paragraph for the week, each figure beside the four weeks before where those exist. */
    fun weekLines(digest: WeekDigest): List<String> {
        val week = digest.week ?: return emptyList()
        val before: PeriodSummary? = digest.baseline
        val spread = week.onsetSpreadMinutes
        val spreadBefore = before?.onsetSpreadMinutes
        return listOfNotNull(
            if (before == null) {
                Tone.Week.onset(week.nights, onsetClock(week.medianOnsetMinute))
            } else {
                Tone.Week.onsetBeside(week.nights, onsetClock(week.medianOnsetMinute), onsetClock(before.medianOnsetMinute))
            },
            when {
                spread == null -> null
                spreadBefore == null -> Tone.Week.spread(span(spread))
                else -> Tone.Week.spreadBeside(span(spread), span(spreadBefore))
            },
            if (before == null) Tone.Week.length(span(week.medianSleepMinutes)) else Tone.Week.lengthBeside(span(week.medianSleepMinutes), span(before.medianSleepMinutes)),
            Tone.Week.runs(week.shortNights, week.longNights, week.noSleepNights),
        )
    }

    /** A sentence for each app open late that the nights can be divided by; empty when none can. */
    fun lateApps(digest: WeekDigest, label: (String) -> String): List<String> = digest.lateApps.map { split ->
        Tone.Week.lateApp(
            app = label(split.packageName),
            withNights = split.withNights,
            withOnset = onsetClock(split.withMedianOnsetMinute),
            withSleep = span(split.withMedianSleepMinutes),
            withoutNights = split.withoutNights,
            withoutOnset = onsetClock(split.withoutMedianOnsetMinute),
            withoutSleep = span(split.withoutMedianSleepMinutes),
        )
    }

    /** Last night's music line, or null for a night with no onset to measure up to. */
    fun music(music: NightMusic?): List<String> = when {
        music == null -> emptyList()
        music.eveningMinutes == 0 && music.afterOnsetMinutes == 0 -> listOf(Tone.Music.EVENING_NONE)
        else -> listOfNotNull(
            if (music.eveningMinutes == 0) Tone.Music.EVENING_NONE else Tone.Music.evening(music.eveningMinutes, music.lastHourMinutes, music.screenOffMinutes),
            music.afterOnsetMinutes.takeIf { it > 0 }?.let(Tone.Music::afterOnset),
        )
    }

    /** Listening over the last seven days and the four weeks before: how much, and how much of it away from a screen. */
    fun musicDays(report: MusicHistory.Report): List<String> {
        val week = report.days.takeLast(7)
        val before = report.days.dropLast(7)
        if (week.isEmpty()) return emptyList()
        fun total(days: List<DayMusic>) = span(days.sumOf { it.minutes }.toDouble())
        val played = week.filter { it.minutes > 0 }
        return listOfNotNull(
            if (played.isEmpty()) Tone.Music.daysNone(week.size) else Tone.Music.days(played.size, week.size, total(week), span(week.sumOf { it.screenOffMinutes }.toDouble())),
            before.takeIf { it.isNotEmpty() }?.let { Tone.Music.daysBefore(it.count { d -> d.minutes > 0 }, it.size, total(it)) },
        )
    }

    /** Patterns' comparison of nights with and without evening music; while there are too few of either, how many there are. */
    fun musicSplit(report: MusicHistory.Report): List<String> {
        if (!report.anyMusic) return listOf(Tone.Music.NONE_RECORDED)
        val split = report.split
        if (split == null) {
            val with = report.byNight.values.count { it.eveningMinutes >= MusicSplit.MUSIC_FROM_MINUTES }
            return listOf(Tone.Music.pending(with, report.byNight.size - with, MusicSplit.MIN_SIDE_NIGHTS))
        }
        return listOf(
            Tone.Music.side(split.with.nights, onsetClock(split.with.medianOnsetMinute), span(split.with.medianSleepMinutes), span(split.with.onsetSpreadMinutes)),
            Tone.Music.otherSide(split.without.nights, onsetClock(split.without.medianOnsetMinute), span(split.without.medianSleepMinutes), span(split.without.onsetSpreadMinutes)),
            Tone.Music.screenOff(split.with.medianMusicMinutes.roundToInt(), split.with.medianScreenOffMinutes.roundToInt()),
        )
    }

    fun laptopQuiet(since: Long): String = Tone.Laptop.quiet(Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).format(dayFormat))

    /** Minutes after noon on the night's date, as a clock time. */
    private fun onsetClock(minutesAfterNoon: Double): String =
        minuteText(Math.floorMod(minutesAfterNoon.roundToInt() + LocalClock.NIGHT_BOUNDARY_HOUR * 60, LocalClock.MINUTES_PER_DAY))

    private fun span(minutes: Double): String = Tone.duration((minutes * LocalClock.MINUTE_MS).toLong())

    private val dayFormat = DateTimeFormatter.ofPattern("d MMM")
}
