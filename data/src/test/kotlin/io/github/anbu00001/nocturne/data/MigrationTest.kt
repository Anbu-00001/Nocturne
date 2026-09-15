package io.github.anbu00001.nocturne.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds databases straight from the exported 1.json and 2.json, the way the installed versions created them on
 * the phone, then opens them with the current Room database. Room runs every migration up to the current schema
 * and validates each table on open, so a migration that loses or mangles anything fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun schemaOneMigratesToTheCurrentSchemaKeepingEveryRow() = runTest {
        val file = freshFile("migration-test-1.db")
        createSchema(file, version = 1) { db ->
            db.execSQL("INSERT INTO raw_events (timestamp, utcOffsetMinutes, eventType, packageName, className) VALUES (1000, 330, 15, 'android', '')")
            db.execSQL("INSERT INTO raw_events (timestamp, utcOffsetMinutes, eventType, packageName, className) VALUES (2000, 330, 1, 'com.whatsapp', 'Main')")
            db.execSQL(
                """INSERT INTO sessions (startTs, endTs, kind, unlocked, trigger, countsAsGlance, dominantPackage, appCount,
                   endInferred, utcOffsetMinutes, nightDate, inEveningWindow, sleepOnsetOffsetMin)
                   VALUES (1000, 5000, 'SHORT', 1, 'UNKNOWN', 0, NULL, 0, 0, 330, '2026-09-14', 0, NULL)""",
            )
            db.execSQL(
                """INSERT INTO harvest_runs (startedAt, finishedAt, outcome, queryFrom, queryTo, eventsSeen, eventsInserted, error)
                   VALUES (1, 2, 'OK', 0, 3000, 2, 2, NULL)""",
            )
            db.execSQL("INSERT INTO zone_changes (sinceTs, zoneId) VALUES (0, 'Asia/Kolkata')")
        }

        val room = open(file)
        try {
            assertEquals(2L, room.rawEvents().count())
            assertEquals(0L, room.sessions().all().single().lastActivityTs)
            assertEquals(3000L, room.harvest().cursor())
            assertEquals("Asia/Kolkata", room.harvest().latestZone()?.zoneId)

            room.sleep().upsertReport(SleepReportEntity("2026-09-14", 10, 20, 330, 30))
            room.sleep().insertPowerSample(PowerSampleEntity(40, charging = true, batteryPercent = 80))
            assertEquals(1, room.sleep().reports().size)
            assertEquals(1, room.sleep().powerSamplesFrom(0).size)
        } finally {
            room.close()
        }
    }

    @Test
    fun schemaTwoMigratesToThreeKeepingEveryRowAndDefaultingTheNewColumns() = runTest {
        val file = freshFile("migration-test-2.db")
        createSchema(file, version = 2) { db ->
            db.execSQL("INSERT INTO raw_events (timestamp, utcOffsetMinutes, eventType, packageName, className) VALUES (1000, 330, 15, 'android', '')")
            db.execSQL("INSERT INTO light_samples (timestamp, ambientLux, screenBrightness, screenOn, foregroundPackage) VALUES (5000, 12.5, 0.1, 1, NULL)")
            db.execSQL(
                """INSERT INTO nights (dateOfNight, estimatedSleepOnset, estimatedWakeTime, confidence, source, eveningScreenMinutes,
                   postOnsetInterruptions, modelledSuppressionPct, suppressionLowPct, suppressionHighPct, modelledPhaseShiftMin,
                   melanopicDoseLuxHours, inferredSleepOnset, inferredWakeTime, inferredConfidence, utcOffsetMinutes,
                   eveningWindowStartMinute, eveningWindowEndMinute, windowPersonalised, windowNights)
                   VALUES ('2026-09-13', 10, 20, 0.4, 'INFERRED', 30, 2, NULL, NULL, NULL, NULL, NULL, 10, 20, 0.42, 330, 1260, 420, 0, 0)""",
            )
            db.execSQL("INSERT INTO sleep_reports (dateOfNight, onsetTs, wakeTs, utcOffsetMinutes, reportedAt) VALUES ('2026-09-13', 10, 20, 330, 30)")
        }

        val room = open(file)
        try {
            assertEquals(1L, room.rawEvents().count())
            val sample = room.light().overlapping(0, 100_000).single()
            assertEquals(12.5f, sample.ambientLux!!, 1e-6f)
            assertEquals(30_000L, sample.durationMs)
            assertEquals(0, sample.sensorEvents)
            assertNull(sample.brightnessSetting)
            assertNull(sample.warmFilter)
            val night = room.sleep().nights().single()
            assertEquals(0.42, night.inferredConfidence, 1e-9)
            assertEquals(0, night.lightScreenMinutes)
            assertEquals(0, night.lightMeasuredMinutes)
            assertFalse(night.suppressionDurationClamped)
            assertEquals(1, room.sleep().reports().size)
        } finally {
            room.close()
        }
    }

    private fun freshFile(name: String): File = context.getDatabasePath(name).apply {
        parentFile?.mkdirs()
        delete()
    }

    private fun open(file: File): NocturneDatabase = Room.databaseBuilder(context, NocturneDatabase::class.java, file.absolutePath).build()

    private fun createSchema(file: File, version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("$SCHEMAS/$version.json").readText()).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace(TABLE_PLACEHOLDER, table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_PLACEHOLDER, table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
            fill(db)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val SCHEMAS = "schemas/io.github.anbu00001.nocturne.data.NocturneDatabase"
        const val TABLE_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
