package io.github.anbu00001.nocturne.data

import io.github.anbu00001.nocturne.core.detect.ChangeSentinel
import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.light.EveningLight
import io.github.anbu00001.nocturne.core.music.DayMusic
import io.github.anbu00001.nocturne.core.music.MusicDays
import io.github.anbu00001.nocturne.core.music.MusicNight
import io.github.anbu00001.nocturne.core.music.MusicNights
import io.github.anbu00001.nocturne.core.music.MusicSpan
import io.github.anbu00001.nocturne.core.music.MusicSplit
import io.github.anbu00001.nocturne.core.music.NightMusic
import io.github.anbu00001.nocturne.core.music.PhoneMusic
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/**
 * Music before sleep, night by night: the phone's playback service from raw_events and the laptops' players from
 * laptop_music, merged so a minute both played counts once. Nothing is stored, as with [Sentinels] and [Digests]: a few
 * hundred events and spans are read in milliseconds, and history from before this existed is covered at once because the
 * phone's part was in raw_events all along.
 */
class MusicHistory(private val db: NocturneDatabase) {

    private companion object {
        /** A week and the four before it, as the week digest reads them. */
        const val DAYS = 35
    }

    data class Report(
        /** Every night with a sleep onset, by date. A night without one has no "before sleep" to measure. */
        val byNight: Map<String, NightMusic>,
        /** Null until both kinds of night are common enough; see [MusicSplit]. */
        val split: MusicSplit?,
        /** Whether any music was ever seen, on either device; false means "nothing recorded", not "no music played". */
        val anyMusic: Boolean,
        /** The last [DAYS] local days the history covers, oldest first: listening as such, whatever it was for. */
        val days: List<DayMusic> = emptyList(),
    )

    /** All music spans, phone and laptop, merged. */
    suspend fun spans(): List<MusicSpan> {
        val dataToTs = maxOf(db.rawEvents().lastTimestamp() ?: Long.MIN_VALUE, db.harvest().cursor() ?: Long.MIN_VALUE)
        val types = listOf(EventType.FOREGROUND_SERVICE_START, EventType.FOREGROUND_SERVICE_STOP)
        val phone = PhoneMusic.PLAYBACK_SERVICES.flatMap { (packageName, className) ->
            PhoneMusic.spans(db.rawEvents().componentEvents(packageName, className, types).map { it.timestamp to it.eventType }, dataToTs)
        }
        // A player left paused holds its service until the phone is next picked up; see PhoneMusic.kept.
        val phoneScreen = db.sessions().startingFrom(Long.MIN_VALUE).map { MusicSpan(it.startTs, it.endTs) }
        val laptop = db.laptop().allMusic().map { MusicSpan(it.startTs, it.endTs) }
        return MusicNights.merged(PhoneMusic.kept(phone, phoneScreen) + laptop)
    }

    suspend fun report(nights: List<NightEntity>? = null): Report {
        val merged = spans()
        val rows = nights ?: db.sleep().nights()
        // Any screen in use: every phone session and every laptop span. A few thousand rows, read once.
        val screens = MusicNights.merged(
            db.sessions().startingFrom(Long.MIN_VALUE).map { MusicSpan(it.startTs, it.endTs) } +
                db.laptop().allSpans().map { MusicSpan(it.startTs, it.endTs) },
        )
        val byNight = LinkedHashMap<String, NightMusic>()
        val forSplit = ArrayList<MusicNight>()
        for (night in rows) {
            val onset = night.estimatedSleepOnset ?: continue
            val wake = night.estimatedWakeTime ?: continue
            val window = EveningWindow(night.eveningWindowStartMinute, night.eveningWindowEndMinute)
            // The evening as the light model cuts it: from the window's start to onset. An onset before the window opened leaves only the last hour.
            val evening = EveningLight.interval(LocalDate.parse(night.dateOfNight), night.utcOffsetMinutes, window, onset, Long.MAX_VALUE)
            val from = evening?.startTs ?: onset
            val music = MusicNights.forNight(merged, from, onset, screens.filter { it.endTs > from - LocalClock.DAY_MS && it.startTs < onset }, wake)
            byNight[night.dateOfNight] = music
            forSplit += MusicNight(
                onsetMinute = ChangeSentinel.onsetMinutesAfterNoon(onset, night.utcOffsetMinutes),
                sleepMinutes = (wake - onset).toDouble() / LocalClock.MINUTE_MS,
                eveningMusicMinutes = music.eveningMinutes,
                screenOffMusicMinutes = music.screenOffMinutes,
            )
        }
        // Days up to the last one harvested, at the offset of the latest session; a zone change inside five weeks moves a day's edge, no more.
        val lastTs = db.rawEvents().lastTimestamp()
        val firstTs = db.rawEvents().firstTimestamp()
        val days = if (lastTs == null || firstTs == null) {
            emptyList()
        } else {
            val offset = db.sessions().offsetAtOrBefore(lastTs) ?: 0
            fun day(ts: Long) = LocalDate.ofEpochDay(Math.floorDiv(LocalClock.localMillis(ts, offset), LocalClock.DAY_MS))
            val to = day(lastTs)
            MusicDays.of(merged, screens, offset, maxOf(day(firstTs), to.minusDays(DAYS - 1L)), to)
        }
        return Report(byNight, MusicSplit.of(forSplit), anyMusic = merged.isNotEmpty(), days = days)
    }
}
