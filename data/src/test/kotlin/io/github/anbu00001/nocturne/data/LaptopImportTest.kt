package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.laptop.LaptopFile
import io.github.anbu00001.nocturne.core.laptop.LaptopFileFormat
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaptopImportTest {

    private lateinit var db: NocturneDatabase
    private val config = SleepConfig()
    private var now = 0L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun laptopUseMovesTheNightItBelongsToAndSendingTheSameWeekAgainChangesNothing() = runTest {
        seedTwelveNights()
        val derived = DerivedTables(db, now = { now })
        derived.recomputeNights(config)
        // The 10th's bedtime is 23:45; the laptop kept someone up to 01:30.
        assertEquals(ist("2026-09-10", "23:46:30"), night("2026-09-10").inferredSleepOnset)

        val file = file(span(ist("2026-09-10", "23:40"), ist("2026-09-11", "01:30"), backlight = 0.06, warm = false))
        now = 1_000
        var result: LaptopImport.Result? = null
        val written = derived.updateNights(config) { LaptopImport(db, now = { now }).import(file).also { result = it }.changedFromTs }
        assertEquals(LaptopImport.Result(listOf("workbook"), spans = 1, added = 1, removed = 0, changedFromTs = ist("2026-09-10", "23:40")), result)
        assertTrue("$written", written >= 1)
        assertEquals(ist("2026-09-11", "01:30"), night("2026-09-10").inferredSleepOnset)
        assertEquals(ist("2026-09-11", "08:15"), night("2026-09-10").inferredWakeTime)

        // Incremental equals full, with laptop use as with everything else.
        val incremental = db.sleep().nights()
        derived.recomputeNights(config)
        assertEquals(incremental, db.sleep().nights())

        now = 2_000
        assertEquals(0, derived.updateNights(config) { LaptopImport(db, now = { now }).import(file).also { result = it }.changedFromTs })
        assertEquals(0, result!!.added)
        assertNull(result!!.changedFromTs)
        val host = db.laptop().host("workbook")!!
        assertEquals(2_000, host.lastImportAt)
        assertEquals(COVER_FROM, host.coveredFromTs)

        // A span still growing when it was first sent is corrected by the next send.
        val longer = file(span(ist("2026-09-10", "23:40"), ist("2026-09-11", "02:10"), backlight = 0.06, warm = false))
        derived.updateNights(config) { LaptopImport(db).import(longer).also { result = it }.changedFromTs }
        assertEquals(ist("2026-09-10", "23:40"), result!!.changedFromTs)
        assertEquals(1, result!!.added)
        assertEquals(1, result!!.removed)
        assertEquals(listOf(ist("2026-09-11", "02:10")), db.laptop().allSpans().map { it.endTs })
        assertEquals(ist("2026-09-11", "02:10"), night("2026-09-10").inferredSleepOnset)
    }

    @Test
    fun theLaptopScreenJoinsTheNightsLightAndACorrectedPanelRederivesItsNights() = runTest {
        seedTwelveNights()
        val eveningStart = ist("2026-09-10", "21:00")
        db.light().insert(LightSampleEntity(timestamp = eveningStart, ambientLux = 20f, screenBrightness = 0.2f, screenOn = true, foregroundPackage = null, brightnessSetting = 800, darkUi = true, warmFilter = true))
        val derived = DerivedTables(db)
        derived.recomputeNights(config)
        val before = night("2026-09-10")
        assertEquals(0, before.lightLaptopMinutes)

        val bright = file(span(ist("2026-09-10", "21:00"), ist("2026-09-10", "22:00"), backlight = 1.0, warm = false))
        derived.updateNights(config) { LaptopImport(db).import(bright).changedFromTs }
        val after = night("2026-09-10")
        assertEquals(60, after.lightLaptopMinutes)
        assertTrue("${before.suppressionHighPct} -> ${after.suppressionHighPct}", after.suppressionHighPct!! > before.suppressionHighPct!!)

        var changedFrom: Long? = null
        val dimmer = LaptopFile(bright.displays.map { it.copy(peakNits = 120.0) }, emptyList(), emptyList())
        val coverage = bright.coverage.single().copy(fromTs = ist("2026-09-12", "12:00"))
        derived.updateNights(config) { LaptopImport(db).import(dimmer.copy(coverage = listOf(coverage))).changedFromTs.also { changedFrom = it } }
        assertEquals(COVER_FROM, changedFrom)
        assertTrue(night("2026-09-10").suppressionHighPct!! < after.suppressionHighPct!!)
        // The spans before the new coverage stay.
        assertEquals(1, db.laptop().allSpans().size)
    }

    @Test
    fun theExportListsSessionsAndNightsForActivityWatch() = runTest {
        seedTwelveNights()
        DerivedTables(db).recomputeNights(config)
        val sessions = StringBuilder()
        val rows = LaptopExport.sessions(db, ist("2026-09-12", "12:00"), sessions)
        val lines = sessions.lines().filter { it.isNotEmpty() }
        assertEquals(LaptopExport.SESSIONS_HEADER, lines.first())
        assertEquals(rows + 1, lines.size)
        val first = lines[1].split(',')
        assertEquals(10, first.size)
        assertEquals("SHORT", first[3])
        assertEquals("", first[7])

        val nights = StringBuilder()
        LaptopExport.nights(db, "2026-09-11", nights)
        val nightLines = nights.lines().filter { it.isNotEmpty() }
        assertEquals(LaptopExport.NIGHTS_HEADER, nightLines.first())
        assertEquals(12, nightLines[1].split(',').size)
        assertEquals("2026-09-11", nightLines[1].substringBefore(','))
    }

    private fun file(vararg spans: String): LaptopFile = LaptopFileFormat.parse(
        sequenceOf(LaptopFileFormat.HEADER, "display,workbook,345,215,2.0,250.0", "coverage,workbook,$COVER_FROM,$COVER_TO", *spans),
    )

    private fun span(from: Long, to: Long, backlight: Double?, warm: Boolean?) =
        "span,workbook,$from,$to,${backlight ?: ""},${when (warm) { null -> ""; true -> "1"; false -> "0" }}"

    private suspend fun night(date: String) = db.sleep().nights().single { it.dateOfNight == date }

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
        db.rawEvents().insertAll(
            listOf(
                RawEvent(timestamp = ist("2026-09-01", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
                RawEvent(timestamp = ist("2026-09-14", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
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

    private companion object {
        const val MINUTE = LocalClock.MINUTE_MS
        const val HOUR = LocalClock.HOUR_MS
        fun ist(date: String, time: String): Long =
            LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
        val COVER_FROM = ist("2026-09-08", "12:00")
        val COVER_TO = ist("2026-09-14", "12:00")
    }
}
