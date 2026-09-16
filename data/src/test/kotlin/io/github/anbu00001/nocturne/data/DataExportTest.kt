package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.reflect.ReflectionSource
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataExportTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun everyTableIsExportedOrDeliberatelyLeftOut() {
        val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        val exported = DataExport.TABLES.map { it.table }.toSet() + DataExport.RAW_EVENTS
        assertEquals(tables - DataExport.NOT_EXPORTED, exported)
        assertTrue("an exported table that does not exist", tables.containsAll(exported))
    }

    @Test
    fun theZipHoldsEveryTableAsCsvWithNamesJoinedAndNoteTextLeftOut() = runTest {
        db.rawEvents().insertAll(
            listOf(
                RawEvent(timestamp = 1_000, utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
                RawEvent(timestamp = 2_000, utcOffsetMinutes = 330, eventType = 1, packageName = "com.example", className = "Main, \"quoted\""),
            ),
        )
        db.sessions().insertAll(
            listOf(
                SessionEntity(
                    startTs = 1_000, endTs = 61_000, kind = SessionKind.SHORT, unlocked = true, trigger = WakeTrigger.UNKNOWN,
                    countsAsGlance = false, dominantPackage = null, appCount = 0, endInferred = false, utcOffsetMinutes = 330,
                    nightDate = "2026-09-16", inEveningWindow = false, sleepOnsetOffsetMin = null, lastActivityTs = 61_000,
                ),
            ),
        )
        db.sleep().upsertNights(
            listOf(
                NightEntity(
                    dateOfNight = "2026-09-16", estimatedSleepOnset = null, estimatedWakeTime = null, confidence = 0.25f,
                    source = SleepSource.INFERRED, eveningScreenMinutes = 1, postOnsetInterruptions = 0, modelledSuppressionPct = 12.5f,
                    suppressionLowPct = null, suppressionHighPct = null, modelledPhaseShiftMin = null, melanopicDoseLuxHours = null,
                ),
            ),
        )
        db.reflections().insert(ReflectionEntity(promptedAt = 5, answeredAt = 6, gapStartTs = 1, gapEndTs = 4, rating = 3, note = "private words", dismissed = false, source = ReflectionSource.PROMPT))
        db.laptop().insertSpans(listOf(LaptopSpanEntity("workbook", 100, 200, 0.25, null)))
        // More than a page, to cross the keyset boundary.
        repeat(5_003) { db.light().insert(LightSampleEntity(timestamp = it.toLong() * 30_000, ambientLux = 1.5f, screenBrightness = null, screenOn = true, foregroundPackage = null)) }

        val bytes = ByteArrayOutputStream()
        val summary = DataExport.writeZip(db, bytes, listOf("model" to "CPH2591", "note" to "a, b"))
        val files = unzip(bytes.toByteArray())

        assertEquals(listOf("raw_events.csv") + DataExport.TABLES.map { it.file } + DataExport.ABOUT, files.keys.toList())
        assertEquals(
            listOf("id,timestamp_utc_ms,utc_offset_minutes,event_type,package_name,class_name", "1,1000,330,15,android,", "2,2000,330,1,com.example,\"Main, \"\"quoted\"\"\""),
            files.getValue("raw_events.csv").lines().filter(String::isNotEmpty),
        )
        val light = files.getValue("light_samples.csv").lines().filter(String::isNotEmpty)
        assertEquals(5_004, light.size)
        assertEquals((0 until 5_003).map { it.toLong() * 30_000 }, light.drop(1).map { it.split(',')[1].toLong() })

        val reflections = files.getValue("reflections.csv")
        assertFalse(reflections.contains("private words"))
        assertEquals("id,promptedAt,answeredAt,gapStartTs,gapEndTs,rating,hasNote,dismissed,source", reflections.lines().first())
        assertEquals("1,5,6,1,4,3,1,0,PROMPT", reflections.lines()[1])

        val night = files.getValue("nights.csv").lines()
        val columns = night.first().split(',')
        val values = night[1].split(',')
        assertEquals("", values[columns.indexOf("estimatedSleepOnset")])
        assertEquals("12.5", values[columns.indexOf("modelledSuppressionPct")])
        assertEquals("workbook,100,200,0.25,", files.getValue("laptop_spans.csv").lines()[1])

        assertEquals(
            listOf("key,value", "model,CPH2591", "note,\"a, b\"", "rows_raw_events,2", "rows_sessions,1"),
            files.getValue(DataExport.ABOUT).lines().take(5),
        )
        assertEquals(2L, summary.rows["raw_events"])
        assertEquals(5_003L, summary.rows["light_samples"])
        assertEquals(15, summary.rows.size)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return files
    }
}
