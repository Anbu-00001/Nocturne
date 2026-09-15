package io.github.anbu00001.nocturne.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Developer check before installing a schema change: migrates a copy of the database pulled from the
 * phone (`adb exec-out run-as io.github.anbu00001.nocturne cat databases/nocturne.db`, plus -wal and -shm)
 * and recomputes everything on it. Skipped unless NOCTURNE_LIVE_DB names that copy. Writes a summary to
 * build/live-database.txt. The copy is personal data: keep it out of the repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveDatabaseTest {

    @Test
    fun thePhoneDatabaseMigratesWithEveryRawEventAndRecomputes() = runTest {
        val source = System.getenv("NOCTURNE_LIVE_DB")
        assumeTrue("NOCTURNE_LIVE_DB is not set", source != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = context.getDatabasePath("live-copy.db").apply { parentFile?.mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            val file = File(source + suffix)
            if (file.exists()) file.copyTo(File(target.path + suffix), overwrite = true)
        }
        val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
        val rawBefore = db.rawQuery("SELECT COUNT(*) FROM raw_events", null).use { it.moveToFirst(); it.getLong(0) }
        val versionBefore = db.version
        db.close()

        val room = Room.databaseBuilder(context, NocturneDatabase::class.java, target.path).build()
        try {
            assertEquals(rawBefore, room.rawEvents().count())
            val sessions = DerivedTables(room).recomputeAll(
                ClassifierConfig(),
                SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS),
                fallbackZoneId = "Asia/Kolkata",
            )
            val nights = room.sleep().nights()
            File("build/live-database.txt").writeText(
                buildString {
                    val versionAfter = room.openHelper.readableDatabase.version
                    appendLine("schema $versionBefore -> $versionAfter, raw events $rawBefore before and ${room.rawEvents().count()} after, sessions $sessions")
                    nights.forEach { appendLine(it.toString()) }
                    val lastJudged = nights.lastOrNull { it.noSleep || it.estimatedSleepOnset != null }?.dateOfNight
                    appendLine()
                    appendLine("regularity windows ending on $lastJudged:")
                    room.metrics().allWindows().filter { it.endDate == lastJudged }.forEach { w ->
                        appendLine("  ${w.windowDays}d ${w.metric}: " + (w.value?.let { "%.3f".format(it) + (w.atMinute?.let { m -> " at %02d:%02d".format(m / 60, m % 60) } ?: "") } ?: "${w.withheldReason} ${w.have}/${w.need}"))
                    }
                    appendLine("model runs: ${room.metrics().latestRun()}")
                },
            )
            assertTrue(nights.any { it.estimatedSleepOnset != null })
        } finally {
            room.close()
        }
    }
}
