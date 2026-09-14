package io.github.anbu00001.nocturne.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a schema-1 database straight from the exported 1.json, the way version 0.1.0 created it on the
 * phone, then opens it with the current Room database. Room runs the 1 -> 2 migration and validates every
 * table against schema 2 on open, so a migration that loses or mangles anything fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun schemaOneMigratesToTwoKeepingEveryRow() = runTest {
        val file = context.getDatabasePath("migration-test.db").apply {
            parentFile?.mkdirs()
            delete()
        }
        createSchemaOne(file) { db ->
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

        val room = Room.databaseBuilder(context, NocturneDatabase::class.java, file.absolutePath).build()
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

    private fun createSchemaOne(file: File, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File(SCHEMA_ONE).readText()).getJSONObject("database")
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
            db.version = 1
            fill(db)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val SCHEMA_ONE = "schemas/io.github.anbu00001.nocturne.data.NocturneDatabase/1.json"
        const val TABLE_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
