package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.detect.SentinelResult
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SentinelsTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun onsetsThatMovedLaterAreReportedAndWeeklySriWaitsForItsWeeks() = runTest {
        val random = Random(4)
        val first = LocalDate.parse("2026-07-01")
        val rows = (0 until 80).map { i ->
            val date = first.plusDays(i.toLong())
            // 60 nights around 00:20, then around 02:20; every tenth night unconfident, and one sleepless night.
            val onset = LocalDateTime.of(date.plusDays(1), java.time.LocalTime.of(0, 20))
                .plusMinutes(((if (i < 60) 0.0 else 120.0) + 40 * random.nextGaussian()).toLong())
                .toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
            NightEntity(
                dateOfNight = date.toString(), estimatedSleepOnset = if (i == 40) null else onset,
                estimatedWakeTime = if (i == 40) null else onset + 7 * LocalClock.HOUR_MS,
                confidence = if (i % 10 == 5) 0.2f else 0.7f, source = SleepSource.INFERRED, eveningScreenMinutes = 0,
                postOnsetInterruptions = 0, modelledSuppressionPct = null, suppressionLowPct = null, suppressionHighPct = null,
                modelledPhaseShiftMin = null, melanopicDoseLuxHours = null, utcOffsetMinutes = 330, noSleep = i == 40,
            )
        }
        db.sleep().upsertNights(rows)
        db.metrics().upsertWindows(
            (0 until 30).map { i ->
                WindowMetricEntity(first.plusDays(10L + i).toString(), 7, "SRI", 60.0 + i % 3, null, 7, 1.0, null, 0, 0, 0)
            },
        )

        val report = Sentinels(db).assess()
        val shift = report.onset as SentinelResult.Shift
        assertEquals(71, shift.values)
        assertTrue("$shift", shift.around in first.plusDays(57)..first.plusDays(63))
        // 30 daily SRI windows give 5 non-overlapping weeks.
        assertEquals(SentinelResult.Withheld(5, 8), report.sri)
    }
}
