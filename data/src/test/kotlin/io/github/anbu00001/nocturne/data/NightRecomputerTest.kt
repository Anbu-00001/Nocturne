package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NightRecomputerTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun recomputingFromANightMatchesRecomputingEverything() = runTest {
        seedTwelveNights()
        val nights = NightRecomputer(db)
        nights.recomputeAll(SleepConfig())
        val fullNights = db.sleep().nights()
        val fullSessions = db.sessions().all()
        assertTrue(fullNights.count { it.estimatedSleepOnset != null } >= 10)
        assertTrue(fullNights.last().windowPersonalised)

        // As if sessions from the evening of 9 Sep on had just been re-derived: tags cleared, nights gone.
        val changed = ist("2026-09-09", "20:00")
        val tail = fullSessions.filter { it.startTs >= changed }
        db.sessions().deleteFrom(changed)
        db.sessions().insertAll(tail.map { it.copy(inEveningWindow = false, sleepOnsetOffsetMin = null) })
        db.sleep().deleteNightsFrom("2026-09-08")

        nights.recomputeFrom(changed, SleepConfig())
        assertEquals(fullNights, db.sleep().nights())
        assertEquals(fullSessions, db.sessions().all())
    }

    @Test
    fun sqlWindowTagsMatchEveningWindowContains() = runTest {
        val night = "2026-09-01"
        val onset = ist("2026-09-02", "01:00")
        db.sessions().insertAll((0 until 96).map { session(ist(night, "12:00") + it * 15 * MINUTE + 7_000, nightDate = night) })
        for (window in listOf(EveningWindow(22 * 60 + 5, 9 * 60), EveningWindow(13 * 60, 18 * 60))) {
            db.sessions().tagNight(night, window.startMinute, window.endMinute, onset)
            for (s in db.sessions().all()) {
                assertEquals("${s.startTs} in $window", window.contains(s.startTs, s.utcOffsetMinutes), s.inEveningWindow)
                assertEquals(((s.startTs - onset) / MINUTE).toInt(), s.sleepOnsetOffsetMin)
            }
        }
    }

    @Test
    fun aReportReplacesTheEstimateForItsNightAndKeepsTheInference() = runTest {
        seedTwelveNights()
        db.sleep().upsertReport(SleepReportEntity("2026-09-05", ist("2026-09-06", "00:30"), ist("2026-09-06", "08:00"), 330, 0))
        NightRecomputer(db).recomputeAll(SleepConfig())
        val night = db.sleep().nights().single { it.dateOfNight == "2026-09-05" }
        assertEquals(SleepSource.USER_REPORTED, night.source)
        assertEquals(ist("2026-09-06", "00:30"), night.estimatedSleepOnset)
        assertNotNull(night.inferredSleepOnset)
        val tagged = db.sessions().all().first { it.nightDate == "2026-09-05" }
        assertEquals(((tagged.startTs - ist("2026-09-06", "00:30")) / MINUTE).toInt(), tagged.sleepOnsetOffsetMin)
    }

    /** Twelve nights: phone use every 10 min for 3 h before bed (23:00 plus 5 min a night) and 4 h from 8.5 h after. */
    private suspend fun seedTwelveNights() {
        val rows = ArrayList<SessionEntity>()
        for (i in 0 until 12) {
            val date = LocalDate.parse("2026-09-01").plusDays(i.toLong())
            val bed = ist(date.toString(), "23:00") + i * 5 * MINUTE
            generateSequence(bed - 3 * HOUR) { it + 10 * MINUTE }.takeWhile { it <= bed }.forEach { rows += session(it) }
            val up = bed + 8 * HOUR + 30 * MINUTE
            generateSequence(up) { it + 10 * MINUTE }.takeWhile { it <= up + 4 * HOUR }.forEach { rows += session(it) }
        }
        db.sessions().insertAll(rows)
        // The harvested history spans the whole fortnight.
        db.rawEvents().insertAll(
            listOf(
                RawEvent(timestamp = ist("2026-09-01", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
                RawEvent(timestamp = ist("2026-09-14", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
            ),
        )
    }

    private fun session(startTs: Long, nightDate: String = LocalClock.nightOf(startTs, 330).toString()) = SessionEntity(
        startTs = startTs,
        endTs = startTs + 90_000,
        kind = SessionKind.SHORT,
        unlocked = true,
        trigger = WakeTrigger.UNKNOWN,
        countsAsGlance = false,
        dominantPackage = null,
        appCount = 1,
        endInferred = false,
        utcOffsetMinutes = 330,
        nightDate = nightDate,
        inEveningWindow = false,
        sleepOnsetOffsetMin = null,
        lastActivityTs = startTs + 90_000,
    )

    private fun ist(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    private companion object {
        const val MINUTE = LocalClock.MINUTE_MS
        const val HOUR = LocalClock.HOUR_MS
    }
}
