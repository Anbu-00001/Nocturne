package io.github.anbu00001.nocturne.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.metrics.ActivityDay
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
import java.time.LocalDate

/**
 * Developer check before installing a schema change: migrates a copy of the database pulled from the
 * phone (`adb exec-out run-as io.github.anbu00001.nocturne cat databases/nocturne.db`, plus -wal and -shm)
 * and recomputes everything on it. Skipped unless NOCTURNE_LIVE_DB names that copy. Writes a summary to
 * build/live-database.txt, and the screen-use minutes of the latest 7-night window to build/live-activity-minutes.txt
 * for the nparACT cross-check (tools/actigraphy). Both are personal data: keep them out of the repository.
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
        // Before schema 5 the names were columns of raw_events: read every event as stored, to compare after interning.
        val eventsBefore = if (versionBefore >= 5) {
            null
        } else {
            db.rawQuery("SELECT id, timestamp, utcOffsetMinutes, eventType, packageName, className FROM raw_events ORDER BY timestamp, id", null).use { c ->
                buildList<RawEvent> { while (c.moveToNext()) add(RawEvent(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getString(4), c.getString(5))) }
            }
        }
        db.close()

        val room = Room.databaseBuilder(context, NocturneDatabase::class.java, target.path).addMigrations(*NocturneDatabase.MIGRATIONS).build()
        try {
            assertEquals(rawBefore, room.rawEvents().count())
            if (eventsBefore != null) assertEquals(eventsBefore, allEvents(room.rawEvents()))
            val sessions = DerivedTables(room).recomputeAll(
                ClassifierConfig(),
                SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS),
                fallbackZoneId = "Asia/Kolkata",
            )
            val nights = room.sleep().nights()
            val lastJudged = nights.lastOrNull { it.noSleep || it.estimatedSleepOnset != null }
            File("build/live-database.txt").writeText(
                buildString {
                    val versionAfter = room.openHelper.readableDatabase.version
                    appendLine("schema $versionBefore -> $versionAfter, raw events $rawBefore before and ${room.rawEvents().count()} after, sessions $sessions")
                    if (eventsBefore != null) {
                        val pairs = room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM event_components").use { it.moveToFirst(); it.getInt(0) }
                        appendLine("all ${eventsBefore.size} events identical after interning, $pairs name pairs")
                    }
                    nights.forEach { appendLine(it.toString()) }
                    appendLine()
                    appendLine("regularity windows ending on ${lastJudged?.dateOfNight}:")
                    room.metrics().allWindows().filter { it.endDate == lastJudged?.dateOfNight }.forEach { w ->
                        appendLine("  ${w.windowDays}d ${w.metric}: " + (w.value?.let { "%.3f".format(it) + (w.atMinute?.let { m -> " at %02d:%02d".format(m / 60, m % 60) } ?: "") } ?: "${w.withheldReason} ${w.have}/${w.need}"))
                    }
                    appendLine()
                    appendLine("7-night screen use against sleep, by end night (IS, IS_SLEEP, IV, IV_SLEEP, L5 start, L5_ASLEEP):")
                    room.metrics().allWindows().filter { it.windowDays == 7 }.groupBy { it.endDate }.forEach { (end, rows) ->
                        val byKey = rows.associateBy { it.metric }
                        fun v(key: String) = byKey[key]?.value?.let { "%.3f".format(it) } ?: "-"
                        val l5 = byKey["L5"]?.atMinute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "-"
                        appendLine("  $end  ${v("IS")}  ${v("IS_SLEEP")}  ${v("IV")}  ${v("IV_SLEEP")}  $l5  ${v("L5_ASLEEP")}")
                    }
                    appendLine("model runs: ${room.metrics().latestRun()}")
                },
            )
            lastJudged?.let { writeActivityMinutes(room, it) }
            assertTrue(nights.any { it.estimatedSleepOnset != null })
        } finally {
            room.close()
        }
    }

    private suspend fun allEvents(dao: RawEventDao): List<RawEvent> {
        val all = ArrayList<RawEvent>()
        while (true) {
            val page = dao.pageAfter(all.lastOrNull()?.timestamp ?: Long.MIN_VALUE, all.lastOrNull()?.id ?: Long.MIN_VALUE, 5_000)
            if (page.isEmpty()) return all
            all += page
        }
    }

    /** Seven noon-to-noon days of screen-on share per minute, ending on [last]'s night, as RegularityWindows builds them. */
    private suspend fun writeActivityMinutes(room: NocturneDatabase, last: NightEntity) {
        val raw = room.rawEvents()
        val from = raw.firstTimestamp() ?: return
        val to = maxOf(raw.lastTimestamp() ?: return, room.harvest().cursor() ?: Long.MIN_VALUE)
        val spans = room.sessions().startingFrom(0).map { it.startTs until it.endTs }
        val end = LocalDate.parse(last.dateOfNight)
        File("build/live-activity-minutes.txt").printWriter().use { out ->
            out.println("# screen-on share per minute, 7 days from 12:00 local on ${end.minusDays(6)}, UTC offset ${last.utcOffsetMinutes} min")
            for (i in 6 downTo 0) {
                val day = ActivityDay.fromScreenSpans(end.minusDays(i.toLong()), last.utcOffsetMinutes, spans, from, to)
                for (m in 0 until 1440) out.println(day[m])
            }
        }
    }
}
