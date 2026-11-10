package io.github.anbu00001.nocturne.core.music

import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import kotlin.math.sqrt

/** Music was playing from [startTs] to [endTs], on the phone or a laptop. No titles: the sleep question needs minutes and the time of night. */
data class MusicSpan(val startTs: Long, val endTs: Long) {
    init {
        require(endTs >= startTs) { "music span ends before it starts" }
    }
}

/**
 * Music on the phone, read from history Android already keeps: a player holds a foreground service while it plays, and
 * usage events record that service starting and stopping. No new permission and no notification access.
 *
 * What it is, exactly: the time the player's playback service ran. Checked on this phone's 129 intervals (10 Nov 2026):
 * the service follows playback closely (94% of starts within 3 minutes of Spotify being opened, stops within seconds of a
 * pause, and no lingering for minutes after one), so starts are reliable. Its one failure is a paused or interrupted
 * player that keeps the service until the phone is next picked up: one interval ran 12 hours across calls and a night's
 * sleep. [kept] cuts those. A stop can still be late by up to [LONG_SCREEN_OFF_MS], so the copy says "up to".
 */
object PhoneMusic {
    /**
     * Playback services by package. Spotify's is its "foreground keeper"; its WorkManager service
     * (androidx.work.impl.foreground.SystemForegroundService) runs for downloads and is not playback. Seen on the A18:
     * 129 start and stop pairs from 4 Sept to 10 Nov 2026, every start followed by its stop.
     */
    val PLAYBACK_SERVICES: Map<String, String> = mapOf(
        "com.spotify.music" to "com.spotify.musicappplatform.state.foregroundkeeperservice.impl.ForegroundKeeperService",
    )

    /** Shorter than this is a tap on play and pause, or the service blinking: 18 restarts within a minute were seen. */
    const val MIN_SPAN_MS = 30_000L

    /** A screen dark this long inside an interval is taken as the player left paused, not as an album played through. */
    const val LONG_SCREEN_OFF_MS = 90 * LocalClock.MINUTE_MS

    /**
     * The spans that are listening: those of [MIN_SPAN_MS] or more, each ended at the start of the first stretch of
     * [LONG_SCREEN_OFF_MS] or more with the phone's screen off inside it. [screenOn] is the phone's sessions, in any
     * order. On the history this was fitted to, 121 of 129 intervals stay and 7,271 minutes become 6,770. The cost is
     * stated, not hidden: more than 90 minutes of listening with the phone untouched is counted only up to the last touch.
     */
    fun kept(spans: List<MusicSpan>, screenOn: List<MusicSpan>): List<MusicSpan> {
        val on = MusicNights.merged(screenOn)
        return spans.mapNotNull { span ->
            if (span.endTs - span.startTs < MIN_SPAN_MS) return@mapNotNull null
            // Walk the dark stretches inside the span: from its start or a session's end, to the next session or the span's end.
            var darkFrom = span.startTs
            var end = span.endTs
            for (session in on) {
                if (session.endTs <= span.startTs) continue
                if (session.startTs >= span.endTs) break
                if (session.startTs - darkFrom >= LONG_SCREEN_OFF_MS) break
                darkFrom = maxOf(darkFrom, minOf(session.endTs, span.endTs))
            }
            val nextOn = on.firstOrNull { it.startTs >= darkFrom && it.startTs < span.endTs }?.startTs ?: span.endTs
            if (nextOn - darkFrom >= LONG_SCREEN_OFF_MS) end = darkFrom
            MusicSpan(span.startTs, end).takeIf { end - span.startTs >= MIN_SPAN_MS }
        }
    }

    /**
     * Spans from a playback service's events in time order, each (timestamp, event type). A start with no stop before the
     * next start is dropped, because its end is unknown (the phone was switched off, or the process was killed); the last
     * start, still open, runs to [dataToTs], the end of the harvested history. A stop with no start is ignored.
     */
    fun spans(events: List<Pair<Long, Int>>, dataToTs: Long): List<MusicSpan> {
        val spans = ArrayList<MusicSpan>()
        var open: Long? = null
        for ((ts, type) in events) {
            when (type) {
                EventType.FOREGROUND_SERVICE_START -> open = ts
                EventType.FOREGROUND_SERVICE_STOP -> {
                    open?.let { if (ts > it) spans += MusicSpan(it, ts) }
                    open = null
                }
            }
        }
        open?.let { if (dataToTs > it) spans += MusicSpan(it, dataToTs) }
        return spans
    }
}

/** One night's music before sleep. Phone and laptop are merged first, so a minute both played is one minute. */
data class NightMusic(
    /** From the evening window's start to sleep onset. */
    val eveningMinutes: Int,
    /** The [MusicNights.LAST_HOUR_MS] before sleep onset. */
    val lastHourMinutes: Int,
    /**
     * The part of [eveningMinutes] with no screen in use, phone or laptop. Music with the phone in hand is screen time with
     * a soundtrack, and the evidence on evening screens is the stronger of the two; no study separates them, so the app does.
     */
    val screenOffMinutes: Int = 0,
    /**
     * Minutes a player ran between sleep onset and wake, beyond [MusicNights.AFTER_ONSET_FLOOR_MS]. Shown, never judged: Scullin
     * et al. (Psychol Sci 2021) tie music near sleep to night-time earworms and worse measured sleep, and a player left
     * running is also the commonest way this figure is wrong.
     */
    val afterOnsetMinutes: Int = 0,
)

object MusicNights {
    const val LAST_HOUR_MS = LocalClock.HOUR_MS

    /** Music past onset is only mentioned beyond this: the onset is an estimate, and it is seldom right to ten minutes. */
    const val AFTER_ONSET_FLOOR_MS = 10 * LocalClock.MINUTE_MS

    /** Sorted, disjoint spans; overlapping and touching ones join, so two devices playing at once count once. */
    fun merged(spans: List<MusicSpan>): List<MusicSpan> {
        val out = ArrayList<MusicSpan>()
        for (span in spans.sortedBy { it.startTs }) {
            val last = out.lastOrNull()
            if (last != null && span.startTs <= last.endTs) out[out.lastIndex] = MusicSpan(last.startTs, maxOf(last.endTs, span.endTs)) else out += span
        }
        return out
    }

    /** Milliseconds of [merged] spans inside [fromTs, toTs). */
    fun msWithin(merged: List<MusicSpan>, fromTs: Long, toTs: Long): Long =
        merged.sumOf { (minOf(it.endTs, toTs) - maxOf(it.startTs, fromTs)).coerceAtLeast(0) }

    /** Milliseconds inside [fromTs, toTs) that both merged lists cover: music with a screen in use. */
    fun overlapMs(merged: List<MusicSpan>, mergedOther: List<MusicSpan>, fromTs: Long, toTs: Long): Long =
        mergedOther.sumOf { other ->
            val start = maxOf(other.startTs, fromTs)
            val end = minOf(other.endTs, toTs)
            if (end > start) msWithin(merged, start, end) else 0L
        }

    /**
     * Music from [eveningStartTs] (the evening window's start) to [onsetTs]. Only up to onset: what plays after it may be
     * a player left running, and whether music heard asleep matters is a different question from the one asked here.
     */
    fun forNight(
        merged: List<MusicSpan>,
        eveningStartTs: Long,
        onsetTs: Long,
        /** When a screen was in use, phone sessions and laptop spans together, in any order. */
        screens: List<MusicSpan> = emptyList(),
        wakeTs: Long = onsetTs,
    ): NightMusic {
        val from = minOf(eveningStartTs, onsetTs)
        val evening = msWithin(merged, from, onsetTs)
        val withScreen = overlapMs(merged, merged(screens), from, onsetTs)
        val after = (msWithin(merged, onsetTs, wakeTs) - AFTER_ONSET_FLOOR_MS).coerceAtLeast(0)
        return NightMusic(
            eveningMinutes = (evening / LocalClock.MINUTE_MS).toInt(),
            lastHourMinutes = (msWithin(merged, onsetTs - LAST_HOUR_MS, onsetTs) / LocalClock.MINUTE_MS).toInt(),
            screenOffMinutes = ((evening - withScreen).coerceAtLeast(0) / LocalClock.MINUTE_MS).toInt(),
            afterOnsetMinutes = (after / LocalClock.MINUTE_MS).toInt(),
        )
    }
}

/**
 * One local day's listening, whatever it was for: how much, and how much of it with no screen in use. The general view
 * of music; the night figures above are one question asked of the same spans.
 */
data class DayMusic(val date: LocalDate, val minutes: Int, val screenOffMinutes: Int)

object MusicDays {
    /**
     * Each local day from [from] to [to], midnight to midnight at [offsetMinutes]; a span across midnight is shared between
     * its two days. [merged] and [screens] as for [MusicNights.forNight]. Days without music are listed with zeros, so
     * "no music" and "no data" stay apart: the caller only asks for days the history covers.
     */
    fun of(merged: List<MusicSpan>, screens: List<MusicSpan>, offsetMinutes: Int, from: LocalDate, to: LocalDate): List<DayMusic> {
        val screenTime = MusicNights.merged(screens)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { date ->
            val start = date.toEpochDay() * LocalClock.DAY_MS - offsetMinutes * LocalClock.MINUTE_MS
            val end = start + LocalClock.DAY_MS
            val total = MusicNights.msWithin(merged, start, end)
            val withScreen = MusicNights.overlapMs(merged, screenTime, start, end)
            DayMusic(date, (total / LocalClock.MINUTE_MS).toInt(), ((total - withScreen).coerceAtLeast(0) / LocalClock.MINUTE_MS).toInt())
        }.toList()
    }
}

/** A night for [MusicSplit]: its onset in minutes after noon, its sleep, and its music before sleep. */
data class MusicNight(val onsetMinute: Double, val sleepMinutes: Double, val eveningMusicMinutes: Int, val screenOffMusicMinutes: Int = 0)

/** One side of the comparison. [onsetSpreadMinutes] is the sample standard deviation of onset: the regularity of that side's nights. */
data class MusicSide(
    val nights: Int,
    val medianOnsetMinute: Double,
    val medianSleepMinutes: Double,
    val onsetSpreadMinutes: Double,
    /** Median evening music on this side's nights, and the median of it that was with the screens off. */
    val medianMusicMinutes: Double = 0.0,
    val medianScreenOffMinutes: Double = 0.0,
)

/**
 * Nights with music in the evening beside nights without, in this person's own history. Observational and unrandomised:
 * the evenings music gets played on differ from the others in ways nothing here measures (the day that came before, who
 * else was awake, whether work was still going), and a short evening leaves less time for music as surely as music can
 * shorten or lengthen one. The trials do not settle it either: in 13 randomised trials of adults with insomnia, listening
 * to music improved how people rated their sleep (PSQI lower by 2.79 points, moderate certainty), while time to fall asleep
 * and total sleep showed small or no effects at low certainty and nothing on objective measures (Jespersen et al.,
 * Cochrane Database Syst Rev 2022, CD010459.pub3). So the copy states the figures and the limit, and never that music did
 * anything, least of all that it brings sleep sooner.
 */
data class MusicSplit(val with: MusicSide, val without: MusicSide) {
    companion object {
        /** A night counts as a music night from this many minutes, so a track skipped past does not. */
        const val MUSIC_FROM_MINUTES = 10

        /** Each side needs this many nights; the spread of onset is not worth quoting from fewer. */
        const val MIN_SIDE_NIGHTS = 5

        fun of(nights: List<MusicNight>): MusicSplit? {
            val (with, without) = nights.partition { it.eveningMusicMinutes >= MUSIC_FROM_MINUTES }
            if (with.size < MIN_SIDE_NIGHTS || without.size < MIN_SIDE_NIGHTS) return null
            return MusicSplit(side(with), side(without))
        }

        private fun side(nights: List<MusicNight>) = MusicSide(
            nights = nights.size,
            medianOnsetMinute = median(nights.map { it.onsetMinute }),
            medianSleepMinutes = median(nights.map { it.sleepMinutes }),
            onsetSpreadMinutes = sampleSd(nights.map { it.onsetMinute }),
            medianMusicMinutes = median(nights.map { it.eveningMusicMinutes.toDouble() }),
            medianScreenOffMinutes = median(nights.map { it.screenOffMusicMinutes.toDouble() }),
        )

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        private fun sampleSd(values: List<Double>): Double {
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        }
    }
}
