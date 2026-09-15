package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Tier 1 windows in the database (analytics §4, §6.2): written with the nights, the same way every run, carrying their run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowMetricsTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun aRunOfRegularNightsGivesRegularityWindowsCarryingTheirRun() = runTest {
        seedNights()
        val run = ModelRuns(db) { 1L }.current("1.1.1", "config")
        val pass = NightRecomputer(db).recompute(null, SleepConfig(), run)
        WindowMetricsRecomputer(db).recompute(pass.from, run)

        val rows = db.metrics().allWindows()
        val sri = rows.single { it.endDate == "2026-09-11" && it.windowDays == 7 && it.metric == "SRI" }
        assertNotNull(sri.value)
        assertTrue("$sri", sri.value!! > 90)
        val early = rows.single { it.endDate == "2026-09-03" && it.windowDays == 7 && it.metric == "SRI" }
        assertNull(early.value)
        assertEquals("TOO_FEW_NIGHTS", early.withheldReason)
        assertTrue(rows.all { it.modelRunId == run })
        assertTrue(db.sleep().nights().all { it.modelRunId == run })
    }

    @Test
    fun recomputingFromANightMatchesRecomputingEverything() = runTest {
        seedNights()
        val run = ModelRuns(db) { 1L }.current("1.1.1", "config")
        val nights = NightRecomputer(db)
        val windows = WindowMetricsRecomputer(db)
        windows.recompute(nights.recompute(null, SleepConfig(), run).from, run)
        val fullWindows = db.metrics().allWindows()

        // As if sessions from the evening of 9 Sep on had just been re-derived: tags cleared, nights and windows gone.
        val changed = ist("2026-09-09", "20:00")
        val tail = db.sessions().all().filter { it.startTs >= changed }
        db.sessions().deleteFrom(changed)
        db.sessions().insertAll(tail.map { it.copy(inEveningWindow = false, sleepOnsetOffsetMin = null) })
        db.sleep().deleteNightsFrom("2026-09-08")
        db.metrics().deleteWindowsFrom("2026-09-08")

        windows.recompute(nights.recompute(changed, SleepConfig(), run).from, run)
        assertEquals(fullWindows, db.metrics().allWindows())
    }

    @Test
    fun aRunIsReusedUntilTheModelOrItsConfigurationChanges() = runTest {
        var clock = 1L
        val runs = ModelRuns(db) { clock++ }
        val first = runs.current("3.3.1", "a")
        assertEquals(first, runs.current("3.3.1", "a"))
        val config = runs.current("3.3.1", "b")
        val model = runs.current("3.4.1", "b")
        assertEquals(listOf("FIRST", "CONFIG_CHANGE", "MODEL_CHANGE"), listOf(first, config, model).map { db.metrics().run(it)!!.trigger })
        assertEquals(2L, db.metrics().run(first)!!.lastUsedAt)
    }

    /** Twelve nights: phone use every 10 min for 3 h before bed (23:00 plus 5 min a night) and 4 h from 8.5 h after. */
    private suspend fun seedNights() {
        val rows = ArrayList<SessionEntity>()
        for (i in 0 until 12) {
            val date = LocalDate.parse("2026-09-01").plusDays(i.toLong())
            val bed = ist(date.toString(), "23:00") + i * 5 * MINUTE
            generateSequence(bed - 3 * HOUR) { it + 10 * MINUTE }.takeWhile { it <= bed }.forEach { rows += session(it) }
            val up = bed + 8 * HOUR + 30 * MINUTE
            generateSequence(up) { it + 10 * MINUTE }.takeWhile { it <= up + 4 * HOUR }.forEach { rows += session(it) }
        }
        db.sessions().insertAll(rows)
        db.rawEvents().insertAll(
            listOf(
                RawEventEntity(timestamp = ist("2026-09-01", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
                RawEventEntity(timestamp = ist("2026-09-14", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
            ),
        )
    }

    private fun session(startTs: Long) = SessionEntity(
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
        nightDate = LocalClock.nightOf(startTs, 330).toString(),
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
