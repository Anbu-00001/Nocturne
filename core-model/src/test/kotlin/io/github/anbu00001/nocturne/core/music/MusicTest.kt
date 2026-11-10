package io.github.anbu00001.nocturne.core.music

import io.github.anbu00001.nocturne.core.event.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicTest {
    private val start = EventType.FOREGROUND_SERVICE_START
    private val stop = EventType.FOREGROUND_SERVICE_STOP
    private val minute = 60_000L

    @Test
    fun `service starts and stops pair into spans`() {
        assertEquals(
            listOf(MusicSpan(1_000, 5_000), MusicSpan(8_000, 9_000)),
            PhoneMusic.spans(listOf(1_000L to start, 5_000L to stop, 8_000L to start, 9_000L to stop), dataToTs = 20_000),
        )
    }

    @Test
    fun `a start with no stop is dropped, unless it is the last and still playing`() {
        // The phone was switched off at some unknown moment after 1 000: that span's end is not known, so it is not counted.
        val events = listOf(1_000L to start, 6_000L to start, 7_000L to stop, 9_000L to start)
        assertEquals(listOf(MusicSpan(6_000, 7_000), MusicSpan(9_000, 12_000)), PhoneMusic.spans(events, dataToTs = 12_000))
        assertEquals(emptyList(), PhoneMusic.spans(listOf(3_000L to stop), dataToTs = 12_000))
    }

    @Test
    fun `a span under 30 s is dropped, and one left running is ended where the screen went dark for 90 minutes`() {
        val spans = listOf(
            MusicSpan(0, 20_000), // a tap on play and pause
            MusicSpan(10 * minute, 800 * minute), // left paused across a night
            MusicSpan(1000 * minute, 1080 * minute), // 80 minutes with the screen off throughout: kept whole
            MusicSpan(2000 * minute, 2200 * minute), // screen dark from its start for 200 minutes: nothing left
        )
        val screenOn = listOf(
            MusicSpan(5 * minute, 12 * minute),
            MusicSpan(60 * minute, 70 * minute), // 48 minutes dark before it: under 90, the music goes on
            MusicSpan(400 * minute, 405 * minute), // 330 minutes dark before it: the span ends at 70
        )
        assertEquals(listOf(MusicSpan(10 * minute, 70 * minute), MusicSpan(1000 * minute, 1080 * minute)), PhoneMusic.kept(spans, screenOn))
    }

    @Test
    fun `phone and laptop playing at once count once`() {
        val merged = MusicNights.merged(listOf(MusicSpan(10 * minute, 40 * minute), MusicSpan(0, 20 * minute), MusicSpan(50 * minute, 55 * minute)))
        assertEquals(listOf(MusicSpan(0, 40 * minute), MusicSpan(50 * minute, 55 * minute)), merged)
        assertEquals(45 * minute, MusicNights.msWithin(merged, 0, 60 * minute))
        assertEquals(15 * minute, MusicNights.msWithin(merged, 30 * minute, 55 * minute))
    }

    @Test
    fun `a night's music runs from the evening window's start to onset, and its last hour is counted apart`() {
        val onset = 300 * minute
        val merged = MusicNights.merged(
            listOf(
                MusicSpan(0, 30 * minute), // before the evening window
                MusicSpan(100 * minute, 130 * minute), // in the evening, not in the last hour
                MusicSpan(290 * minute, 400 * minute), // runs on past onset: only its first ten minutes count
            ),
        )
        assertEquals(NightMusic(eveningMinutes = 40, lastHourMinutes = 10, screenOffMinutes = 40), MusicNights.forNight(merged, eveningStartTs = 60 * minute, onsetTs = onset))
        // An onset before the window opened: there is no evening to count, and the last hour before sleep still is.
        assertEquals(NightMusic(eveningMinutes = 0, lastHourMinutes = 30), MusicNights.forNight(merged, eveningStartTs = 60 * minute, onsetTs = 50 * minute))
    }

    @Test
    fun `evening music is split by whether a screen was in use, and a player running into sleep is counted past ten minutes`() {
        val onset = 300 * minute
        val merged = listOf(MusicSpan(200 * minute, 260 * minute), MusicSpan(290 * minute, 325 * minute))
        // Two sessions that overlap each other and 25 minutes of the music; a third after onset, which is not the evening's.
        val screens = listOf(MusicSpan(190 * minute, 215 * minute), MusicSpan(210 * minute, 225 * minute), MusicSpan(305 * minute, 310 * minute))
        val night = MusicNights.forNight(merged, eveningStartTs = 120 * minute, onsetTs = onset, screens = screens, wakeTs = 700 * minute)
        assertEquals(70, night.eveningMinutes)
        assertEquals(45, night.screenOffMinutes)
        assertEquals(15, night.afterOnsetMinutes)
        // Ten minutes or less past onset is within how well the onset itself is known.
        assertEquals(0, MusicNights.forNight(listOf(MusicSpan(290 * minute, 309 * minute)), 120 * minute, onset, wakeTs = 700 * minute).afterOnsetMinutes)
    }

    @Test
    fun `a day's listening is counted midnight to midnight, a span across midnight shared, with the screen-off part apart`() {
        val day = 24 * 60 * minute
        val d0 = java.time.LocalDate.ofEpochDay(0)
        // At +05:30 local midnight of day 1 is 18:30 UTC of day 0. Music from 23:30 to 00:40 local, a screen on for its first 20 minutes.
        val midnight = day - 330 * minute
        val merged = listOf(MusicSpan(midnight - 30 * minute, midnight + 40 * minute))
        val screens = listOf(MusicSpan(midnight - 60 * minute, midnight - 10 * minute))
        assertEquals(
            listOf(DayMusic(d0, 30, 10), DayMusic(d0.plusDays(1), 40, 40), DayMusic(d0.plusDays(2), 0, 0)),
            MusicDays.of(merged, screens, offsetMinutes = 330, from = d0, to = d0.plusDays(2)),
        )
    }

    @Test
    fun `nights with ten minutes or more of evening music are set beside the rest`() {
        val nights = listOf(700.0, 710.0, 720.0, 730.0, 740.0).map { MusicNight(it, 480.0, 30, screenOffMusicMinutes = 12) } +
            listOf(780.0, 800.0, 820.0, 840.0, 860.0, 880.0).map { MusicNight(it, 400.0, 9) }
        val split = MusicSplit.of(nights)!!
        assertEquals(5, split.with.nights)
        assertEquals(720.0, split.with.medianOnsetMinute)
        assertEquals(480.0, split.with.medianSleepMinutes)
        // statistics.stdev([700, 710, 720, 730, 740]) = 15.8114
        assertEquals(15.8114, split.with.onsetSpreadMinutes, 0.0001)
        assertEquals(30.0, split.with.medianMusicMinutes)
        assertEquals(12.0, split.with.medianScreenOffMinutes)
        assertEquals(6, split.without.nights)
        assertEquals(830.0, split.without.medianOnsetMinute)
    }

    @Test
    fun `no comparison when either side has fewer than five nights`() {
        val music = (1..4).map { MusicNight(700.0, 480.0, 30) }
        val none = (1..9).map { MusicNight(700.0, 480.0, 0) }
        assertNull(MusicSplit.of(music + none))
        assertNull(MusicSplit.of(none))
    }
}
