package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.light.DisplayProfile
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

/** Light samples reaching the nights table (spec §6.2): only nights with samples are modelled, the same way every run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NightLightTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun lightSamplesGiveTheirNightAModelledSuppressionAndNightsWithoutSamplesStayUnmodelled() = runTest {
        seedNights()
        seedLight("2026-09-10")
        NightRecomputer(db, DisplayProfile.OPPO_A18).recomputeAll(SleepConfig())
        val nights = db.sleep().nights().associateBy { it.dateOfNight }

        val lit = nights.getValue("2026-09-10")
        assertNotNull(lit.modelledSuppressionPct)
        val mid = lit.modelledSuppressionPct!!
        assertTrue("$lit", lit.suppressionLowPct!! <= mid && mid <= lit.suppressionHighPct!!)
        assertNotNull(lit.melanopicDoseLuxHours)
        assertTrue("$lit", lit.lightMeasuredMinutes in 1..lit.lightScreenMinutes)

        val dark = nights.getValue("2026-09-09")
        assertNull(dark.modelledSuppressionPct)
        assertEquals(0, dark.lightScreenMinutes)
    }

    @Test
    fun recomputingFromANightMatchesRecomputingEverythingWithLightSamples() = runTest {
        seedNights()
        seedLight("2026-09-09")
        seedLight("2026-09-11")
        val nights = NightRecomputer(db, DisplayProfile.OPPO_A18)
        nights.recomputeAll(SleepConfig())
        val fullNights = db.sleep().nights()
        assertNotNull(fullNights.single { it.dateOfNight == "2026-09-11" }.modelledSuppressionPct)

        val changed = ist("2026-09-09", "20:00")
        val tail = db.sessions().all().filter { it.startTs >= changed }
        db.sessions().deleteFrom(changed)
        db.sessions().insertAll(tail.map { it.copy(inEveningWindow = false, sleepOnsetOffsetMin = null) })
        db.sleep().deleteNightsFrom("2026-09-08")

        nights.recomputeFrom(changed, SleepConfig())
        assertEquals(fullNights, db.sleep().nights())
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

    /** A sample every 30 s from 20:00 to midnight: a lit room and a bright light-mode screen. */
    private suspend fun seedLight(nightDate: String) {
        val from = ist(nightDate, "20:00")
        generateSequence(from) { it + 30_000 }.takeWhile { it < from + 4 * HOUR }.forEach {
            db.light().insert(
                LightSampleEntity(
                    timestamp = it,
                    ambientLux = 80f,
                    screenBrightness = 0.5f,
                    screenOn = true,
                    foregroundPackage = null,
                    durationMs = 30_000,
                    brightnessSetting = 2000,
                    darkUi = false,
                    warmFilter = false,
                    sensorEvents = 150,
                ),
            )
        }
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
