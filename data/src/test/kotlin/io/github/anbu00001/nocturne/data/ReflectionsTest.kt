package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.focus.RunningTimer
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.reflect.GapLabel
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGap
import io.github.anbu00001.nocturne.core.reflect.ReflectionSource
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReflectionsTest {

    private lateinit var db: NocturneDatabase
    private var now = 0L
    private val config = SleepConfig(screenOffTimeoutMs = 30 * 60_000L)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun ist(date: String, time: String) = LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    private fun at(time: String) = ist("2026-09-16", time)

    private suspend fun unlock(time: String, date: String = "2026-09-16", kind: SessionKind = SessionKind.SHORT) {
        val start = ist(date, time)
        db.sessions().insertAll(
            listOf(
                SessionEntity(
                    startTs = start, endTs = start + 60_000, kind = kind, unlocked = kind != SessionKind.GLANCE_NO_UNLOCK,
                    trigger = WakeTrigger.UNKNOWN, countsAsGlance = false, dominantPackage = null, appCount = 1, endInferred = false,
                    utcOffsetMinutes = 330, nightDate = "2026-09-15", inEveningWindow = false, sleepOnsetOffsetMin = null,
                    lastActivityTs = start + 60_000,
                ),
            ),
        )
    }

    private suspend fun givenTheNightBefore() {
        db.harvest().insertZone(ZoneChangeEntity(0, "Asia/Kolkata"))
        db.sleep().upsertNights(
            listOf(
                NightEntity(
                    dateOfNight = "2026-09-15", estimatedSleepOnset = ist("2026-09-16", "00:30"), estimatedWakeTime = at("07:30"),
                    confidence = 0.8f, source = SleepSource.INFERRED, eveningScreenMinutes = 0, postOnsetInterruptions = 0,
                    modelledSuppressionPct = null, suppressionLowPct = null, suppressionHighPct = null, modelledPhaseShiftMin = null,
                    melanopicDoseLuxHours = null, utcOffsetMinutes = 330, eveningWindowStartMinute = 23 * 60, eveningWindowEndMinute = 7 * 60,
                ),
            ),
        )
    }

    private fun reflections() = Reflections(db, now = { now }, fallbackZoneId = { "Asia/Kolkata" })

    @Test
    fun aCardIsRecordedOnceAnsweredOnceAndTheWeeklyListTakesTheRest() = runTest {
        givenTheNightBefore()
        for (time in listOf("06:00", "08:00", "10:00", "13:00")) unlock(time)
        unlock("11:30", kind = SessionKind.GLANCE_NO_UNLOCK)
        val r = reflections()

        now = at("15:00")
        val card = r.card(config)!!
        // 06:01 to 08:00 touches the night's sleep and window; 10:01 to 13:00 is the latest, with a lock-screen glance inside.
        assertEquals(PhoneDownGap(at("10:01"), at("13:00")), card.gap)
        now = at("15:05")
        assertEquals(card, r.card(config)) // shown again, not asked again
        r.answer(card.reflectionId, GapLabel.DEEP_WORK)
        assertNull(r.card(config))

        unlock("17:00")
        now = at("18:05")
        val next = r.card(config)!!
        assertEquals(PhoneDownGap(at("13:01"), at("17:00")), next.gap)
        now = at("23:30")
        assertNull(r.card(config)) // inside the evening window

        now = at("18:10")
        assertEquals(listOf(PhoneDownGap(at("13:01"), at("17:00")), PhoneDownGap(at("08:01"), at("10:00"))), r.unlabelled(config))
        assertEquals(next.reflectionId, r.label(PhoneDownGap(at("13:01"), at("17:00")), GapLabel.REST))
        val backfilled = r.label(PhoneDownGap(at("08:01"), at("10:00")), GapLabel.LIGHT_WORK)
        assertEquals(emptyList<PhoneDownGap>(), r.unlabelled(config))
        assertEquals(ReflectionSource.BACKFILL, db.reflections().byId(backfilled)!!.source)
        assertEquals(3, db.reflections().since(0).size)
        assertEquals(
            mapOf(GapLabel.DEEP_WORK to 179 * 60_000L, GapLabel.REST to 239 * 60_000L, GapLabel.LIGHT_WORK to 119 * 60_000L),
            r.weekByLabel(),
        )
        r.note(backfilled, "  ")
        assertNull(db.reflections().byId(backfilled)!!.note)
    }

    @Test
    fun aDismissedCardIsKeptAsDismissedAndNeverListed() = runTest {
        givenTheNightBefore()
        for (time in listOf("08:00", "10:00")) unlock(time)
        val r = reflections()
        now = at("12:00")
        val card = r.card(config)!!
        r.dismiss(card.reflectionId)
        val row = db.reflections().byId(card.reflectionId)!!
        assertEquals(true, row.dismissed)
        assertEquals(at("12:00"), row.answeredAt)
        assertEquals(emptyList<PhoneDownGap>(), r.unlabelled(config))
    }

    @Test
    fun focusBlocksCountUnlocksInsideThemAndFollowEveryRecompute() = runTest {
        unlock("09:59") // the unlock the block was started from
        unlock("10:05")
        unlock("10:20", kind = SessionKind.GLANCE_NO_UNLOCK)
        unlock("10:24")
        unlock("10:25")
        val block = FocusBlocks(db).record(RunningTimer(TimerKind.FOCUS, at("10:00"), 25), at("10:25"), completed = true)
        assertEquals(2, block.interruptionCount)

        unlock("10:10")
        db.focus().recount()
        assertEquals(3, db.focus().all().single().interruptionCount)
        // No raw events here, so a full recompute leaves no sessions, and the count follows.
        DerivedTables(db).recomputeAll(ClassifierConfig(), config, "Asia/Kolkata")
        assertEquals(0, db.focus().all().single().interruptionCount)
    }
}
