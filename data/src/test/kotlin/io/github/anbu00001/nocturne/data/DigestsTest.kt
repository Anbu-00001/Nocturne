package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.mind.CheckIn
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DigestsTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun theGlanceIsTheLatestNightWithAVerdictItsWeekAndTheAppOpenLate() = runTest {
        // 14 nights: the game for 20 min in the hour before sleep on even nights, which run to 01:00; the rest sleep at 23:30.
        for (i in 0 until 14) {
            val date = LocalDate.parse("2026-10-20").plusDays(i.toLong())
            val late = i % 2 == 0
            val onset = if (late) ist(date.plusDays(1).toString(), "01:00") else ist(date.toString(), "23:30")
            db.sleep().upsertNights(listOf(night(date.toString(), onset, onset + 7 * HOUR)))
            // The launcher is on screen every night and must not be the app the split picks.
            use(onset - 2 * HOUR, "com.android.launcher", 40)
            if (late) use(onset - HOUR, GAME, 20)
            // Four hours before onset is outside the three the split reads.
            use(onset - 4 * HOUR, GAME, 30)
        }
        // Tonight has sessions but no verdict yet.
        db.sleep().upsertNights(listOf(night("2026-11-03", null, null)))

        val glance = Digests(db).glance(now = ist("2026-11-03", "21:00"))
        assertEquals("2026-11-02", glance.night?.dateOfNight)
        assertNull(glance.report)
        val week = checkNotNull(glance.week)
        assertEquals(LocalDate.parse("2026-11-02"), week.endDate)
        assertEquals(7, week.week?.nights)
        assertEquals(7, week.baseline?.nights)
        val split = week.lateApps.single()
        assertEquals(GAME, split.packageName)
        assertEquals(7, split.withNights)
        assertEquals(13 * 60.0, split.withMedianOnsetMinute, 0.0)
        assertEquals(7, split.withoutNights)
        assertEquals(11.5 * 60, split.withoutMedianOnsetMinute, 0.0)
        assertNull(glance.laptopQuietSince)
    }

    @Test
    fun aLaptopThatLastSentUseDaysAgoIsQuietAndAFreshOneIsNot() = runTest {
        val covered = ist("2026-10-16", "09:50")
        db.laptop().upsertHost(LaptopHostEntity("workbook", 345, 215, 2.0, 250.0, 0, covered, covered, 26))
        assertEquals(covered, Digests(db).glance(now = ist("2026-11-10", "21:00")).laptopQuietSince)
        assertNull(Digests(db).glance(now = ist("2026-10-17", "21:00")).laptopQuietSince)
    }

    @Test
    fun confirmingKeepsTheEstimateAsTheUsersNightThroughARecompute() = runTest {
        seedTwelveNights()
        NightRecomputer(db).recomputeAll(SleepConfig())
        val before = db.sleep().nights().single { it.dateOfNight == "2026-09-05" }
        assertEquals(SleepSource.INFERRED, before.source)

        assertTrue(SleepConfirmations(db).confirm("2026-09-05", now = 42))
        val report = checkNotNull(db.sleep().report("2026-09-05"))
        assertTrue(report.confirmed)
        assertEquals(before.estimatedSleepOnset, report.onsetTs)
        assertEquals(before.estimatedWakeTime, report.wakeTs)
        assertEquals(42L, report.reportedAt)

        NightRecomputer(db).recomputeAll(SleepConfig())
        val after = db.sleep().nights().single { it.dateOfNight == "2026-09-05" }
        assertEquals(SleepSource.USER_REPORTED, after.source)
        assertEquals(1f, after.confidence)
        assertEquals(before.estimatedSleepOnset, after.estimatedSleepOnset)
        assertEquals(before.estimatedWakeTime, after.estimatedWakeTime)
        // The other nights are where they were: a confirmation teaches no offset.
        val others = db.sleep().nights().filter { it.dateOfNight != "2026-09-05" }
        assertTrue(others.all { it.estimatedSleepOnset == it.inferredSleepOnset })
    }

    @Test
    fun aTapNeverReplacesAnEntryAndThereIsNothingToConfirmWithoutAVerdict() = runTest {
        seedTwelveNights()
        db.sleep().upsertReport(SleepReportEntity("2026-09-05", ist("2026-09-06", "00:30"), ist("2026-09-06", "08:00"), 330, 0))
        NightRecomputer(db).recomputeAll(SleepConfig())
        assertFalse(SleepConfirmations(db).confirm("2026-09-05", now = 42))
        assertEquals(false, db.sleep().report("2026-09-05")?.confirmed)
        assertFalse(SleepConfirmations(db).confirm("2026-08-01", now = 42))
        db.sleep().upsertNights(listOf(night("2026-09-20", null, null)))
        assertFalse(SleepConfirmations(db).confirm("2026-09-20", now = 42))
        // A second tap on a confirmed night changes nothing.
        assertTrue(SleepConfirmations(db).confirm("2026-09-06", now = 42))
        NightRecomputer(db).recomputeAll(SleepConfig())
        assertFalse(SleepConfirmations(db).confirm("2026-09-06", now = 99))
        assertEquals(42L, db.sleep().report("2026-09-06")?.reportedAt)
    }

    @Test
    fun aNightFoundSleeplessIsConfirmedAsNoSleep() = runTest {
        db.sleep().upsertNights(listOf(night("2026-09-20", null, null).copy(noSleep = true, inferredNoSleep = true, confidence = 0.9f)))
        assertTrue(SleepConfirmations(db).confirm("2026-09-20", now = 42))
        val report = checkNotNull(db.sleep().report("2026-09-20"))
        assertTrue(report.noSleep && report.confirmed)
    }

    @Test
    fun oneCheckInADayALaterTapReplacesItAndTheGapRulesNeverSeeIt() = runTest {
        val checkIns = CheckIns(db)
        val evening = ist("2026-11-10", "21:30")
        checkIns.save(CheckIn(2, 4), evening, 330)
        // 01:00 the next morning is still the 10th; the tap replaces the evening's.
        val late = checkIns.save(CheckIn(4, 3, tension = 2), ist("2026-11-11", "01:00"), 330)
        assertEquals(LocalDate.parse("2026-11-10"), late.day)
        assertEquals(listOf(CheckIn(4, 3, 2)), checkIns.all(330).map { it.checkIn })
        assertEquals(CheckIn(4, 3, 2), checkIns.today(ist("2026-11-11", "03:59"), 330)?.checkIn)
        assertNull(checkIns.today(ist("2026-11-11", "04:00"), 330))
        checkIns.save(CheckIn(5, 5), ist("2026-11-11", "09:00"), 330)
        assertEquals(2, checkIns.all(330).size)
        // Not a gap reflection: the card and weekly-list rules read `since`, which leaves check-ins out.
        assertEquals(emptyList<ReflectionEntity>(), db.reflections().since(0))
        // The export carries the taps as columns.
        val out = java.io.ByteArrayOutputStream()
        DataExport.writeZip(db, out, emptyList())
        val csv = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "reflections.csv" }
            zip.readBytes().decodeToString()
        }
        assertTrue(csv.lines()[1].endsWith(",MOOD,4,3,2"))
        assertTrue(csv.lines()[2].endsWith(",MOOD,5,5,"))
    }

    @Test
    fun theRhythmReportDescribesJumpsAlarmsAndUnlocksAndWithholdsLinksWithACount() = runTest {
        val now = ist("2026-11-10", "21:00")
        // 20 nights before today, onsets alternating 23:00 and 02:00, eight hours each.
        for (i in 1..20) {
            val date = LocalDate.parse("2026-11-10").minusDays(i.toLong())
            val onset = if (i % 2 == 0) ist(date.toString(), "23:00") else ist(date.plusDays(1).toString(), "02:00")
            db.sleep().upsertNights(listOf(night(date.toString(), onset, onset + 8 * HOUR)))
            // Three alarms over 90 minutes on the mornings after Sunday to Wednesday nights, none after the others; 4 unlocks a day.
            if (date.dayOfWeek.value in setOf(7, 1, 2, 3)) {
                val first = ist(date.plusDays(1).toString(), "05:00")
                db.sessions().insertAll(listOf(0L, 45 * MINUTE, 90 * MINUTE).map { session(first + it, 10_000).copy(trigger = WakeTrigger.ALARM, unlocked = false, nightDate = date.toString()) })
            }
            db.sessions().insertAll((0 until 4).map { session(ist(date.toString(), "14:00") + it * HOUR, 60_000) })
        }
        db.rawEvents().insertAll(listOf(RawEvent(timestamp = ist("2026-10-01", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android")))
        for (i in 1..12) CheckIns(db).save(CheckIn(3, 3), now - i * 24 * HOUR, 330)

        val report = MindHistory(db).report(now, 330)
        val jumps = checkNotNull(report.jumps)
        assertEquals(19, jumps.pairs)
        assertEquals(180.0, jumps.medianJumpMinutes, 0.0)
        assertEquals(19, jumps.overTwoHours)
        val early = checkNotNull(report.alarmsEarlyWeek)
        assertEquals(early.nights, early.mornings)
        assertEquals(3.0, early.medianAlarms, 0.0)
        assertEquals(90.0, early.medianSpanMinutes, 0.0)
        assertNull(report.alarmsOther)
        assertEquals(4.0, checkNotNull(report.pickupsWeek), 0.0)
        assertEquals(12, report.checkIns.size)
        assertTrue(report.links.withheld)
        assertEquals(12, report.links.days)
        assertNull(report.socialJetlagMinutes)
    }

    private fun night(date: String, onset: Long?, wake: Long?) = NightEntity(
        dateOfNight = date, estimatedSleepOnset = onset, estimatedWakeTime = wake, confidence = 0.2f, source = SleepSource.INFERRED,
        eveningScreenMinutes = 0, postOnsetInterruptions = 0, modelledSuppressionPct = null, suppressionLowPct = null,
        suppressionHighPct = null, modelledPhaseShiftMin = null, melanopicDoseLuxHours = null, utcOffsetMinutes = 330,
    )

    /** One session at [startTs] with [packageName] in front for [minutes]. */
    private suspend fun use(startTs: Long, packageName: String, minutes: Int) {
        db.sessions().insertAll(listOf(session(startTs, minutes * MINUTE)))
        db.sessions().insertApps(listOf(SessionAppEntity(startTs, packageName, minutes * MINUTE)))
    }

    /** Twelve nights: phone use every 10 min for 3 h before bed (23:00 plus 5 min a night) and 4 h from 8.5 h after. */
    private suspend fun seedTwelveNights() {
        val rows = ArrayList<SessionEntity>()
        for (i in 0 until 12) {
            val date = LocalDate.parse("2026-09-01").plusDays(i.toLong())
            val bed = ist(date.toString(), "23:00") + i * 5 * MINUTE
            generateSequence(bed - 3 * HOUR) { it + 10 * MINUTE }.takeWhile { it <= bed }.forEach { rows += session(it, 90_000) }
            val up = bed + 8 * HOUR + 30 * MINUTE
            generateSequence(up) { it + 10 * MINUTE }.takeWhile { it <= up + 4 * HOUR }.forEach { rows += session(it, 90_000) }
        }
        db.sessions().insertAll(rows)
        db.rawEvents().insertAll(
            listOf(
                RawEvent(timestamp = ist("2026-09-01", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
                RawEvent(timestamp = ist("2026-09-14", "12:00"), utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
            ),
        )
    }

    private fun session(startTs: Long, lengthMs: Long) = SessionEntity(
        startTs = startTs, endTs = startTs + lengthMs, kind = SessionKind.SHORT, unlocked = true, trigger = WakeTrigger.UNKNOWN,
        countsAsGlance = false, dominantPackage = null, appCount = 1, endInferred = false, utcOffsetMinutes = 330,
        nightDate = LocalClock.nightOf(startTs, 330).toString(), inEveningWindow = false, sleepOnsetOffsetMin = null,
        lastActivityTs = startTs + lengthMs,
    )

    private fun ist(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    private companion object {
        const val MINUTE = LocalClock.MINUTE_MS
        const val HOUR = LocalClock.HOUR_MS
        const val GAME = "com.example.game"
    }
}
