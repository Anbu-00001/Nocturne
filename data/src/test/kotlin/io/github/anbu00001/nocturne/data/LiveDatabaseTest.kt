package io.github.anbu00001.nocturne.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.circadian.Forger99
import io.github.anbu00001.nocturne.core.circadian.Hannay19
import io.github.anbu00001.nocturne.core.circadian.LightScenario
import io.github.anbu00001.nocturne.core.circadian.PhaseEstimator
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.laptop.LaptopFileFormat
import io.github.anbu00001.nocturne.core.light.DisplayProfile
import io.github.anbu00001.nocturne.core.light.LightReading
import io.github.anbu00001.nocturne.core.metrics.ActivityDay
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGaps
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.core.time.ZoneChange
import io.github.anbu00001.nocturne.core.time.ZoneTimeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Developer check before installing a schema change: migrates a copy of the database pulled from the
 * phone (`adb exec-out run-as io.github.anbu00001.nocturne cat databases/nocturne.db`, plus -wal and -shm)
 * and recomputes everything on it. Skipped unless NOCTURNE_LIVE_DB names that copy. Writes a summary to
 * build/live-database.txt, including modelled DLMO by night, and the screen-use minutes of the latest 7-night window to
 * build/live-activity-minutes.txt for the nparACT cross-check (tools/actigraphy). Both are personal data: keep them out of
 * the repository.
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
            val storedNights = room.sleep().nights()
            val sleepConfig = SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS)
            // The copy comes from the A18, and the app there models light with its own panel.
            val derived = DerivedTables(room, DisplayProfile.OPPO_A18)
            val sessions = derived.recomputeAll(ClassifierConfig(), sleepConfig, fallbackZoneId = "Asia/Kolkata")
            // Phase 4: laptop use sent as the laptop tool writes it, when a file is given.
            val laptopFile = System.getenv("NOCTURNE_LAPTOP_FILE")
            val beforeLaptop = room.sleep().nights()
            var laptopResult: LaptopImport.Result? = null
            if (laptopFile != null) {
                val file = LaptopFileFormat.parse(File(laptopFile).readLines().asSequence())
                derived.updateNights(sleepConfig) { LaptopImport(room).import(file).also { laptopResult = it }.changedFromTs }
            }
            val nights = room.sleep().nights()
            val lastJudged = nights.lastOrNull { it.noSleep || it.estimatedSleepOnset != null }
            File("build/live-database.txt").writeText(
                buildString {
                    val versionAfter = room.openHelper.readableDatabase.version
                    appendLine("schema $versionBefore -> $versionAfter, raw events $rawBefore before and ${room.rawEvents().count()} after, sessions $sessions")
                    // Without laptop use the new model should give the stored nights back; only the run differs.
                    val stored = storedNights.associateBy { it.dateOfNight }
                    val moved = beforeLaptop.filter { n -> stored[n.dateOfNight]?.copy(modelRunId = 0, lightLaptopMinutes = 0) != n.copy(modelRunId = 0) }
                    appendLine("recomputed nights differing from the stored ones: ${moved.size} of ${beforeLaptop.size}")
                    moved.forEach { appendLine("  was ${stored[it.dateOfNight]}\n  now $it") }
                    laptopResult?.let { r ->
                        appendLine("laptop import: $r")
                        val earlier = beforeLaptop.associateBy { it.dateOfNight }
                        nights.filter { it != earlier[it.dateOfNight] }.forEach { appendLine("  was ${earlier[it.dateOfNight]}\n  now $it") }
                    }
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
                    appendLine("shift sentinel: ${Sentinels(room, sleepConfig).assess()}")
                    appendLine()
                    appendPhases(room, nights)
                    appendLine()
                    appendGaps(room, nights)
                },
            )
            lastJudged?.let { writeActivityMinutes(room, it) }
            assertTrue(nights.any { it.estimatedSleepOnset != null })
        } finally {
            room.close()
        }
    }

    /** Spec §6.4 on the phone's history: DLMO by night under each light scenario, set against habitual sleep. */
    private suspend fun StringBuilder.appendPhases(room: NocturneDatabase, nights: List<NightEntity>) {
        val raw = room.rawEvents()
        val from = raw.firstTimestamp() ?: return
        val to = maxOf(raw.lastTimestamp() ?: return, room.harvest().cursor() ?: Long.MIN_VALUE)
        val zones = ZoneTimeline(room.harvest().zones().map { ZoneChange(it.sinceTs, it.zoneId) }.ifEmpty { listOf(ZoneChange(0, "Asia/Kolkata")) })
        val sleep = nights.mapNotNull { n ->
            val onset = n.estimatedSleepOnset
            val wake = n.estimatedWakeTime
            if (onset != null && wake != null && wake > onset) onset until wake else null
        }
        val readings = room.light().overlapping(0, to).map { LightReading(it.timestamp, it.durationMs, it.ambientLux?.toDouble(), it.brightnessSetting, it.darkUi, it.warmFilter) }
        fun clock(ts: Long) = Instant.ofEpochMilli(ts).atOffset(ZoneOffset.ofTotalSeconds(zones.offsetMinutesAt(ts) * 60)).toLocalTime().toString().take(5)
        appendLine("modelled DLMO by night under each light scenario, hours before habitual onset in brackets; plausible within -1 to 7 h; ${readings.size} light samples:")
        for ((name, model) in listOf("Hannay19" to Hannay19(), "Forger99" to Forger99())) {
            for (p in PhaseEstimator.estimate(from, to, zones, sleep, readings, model)) {
                val scenarios = LightScenario.entries.joinToString { s -> p.dlmoByScenario[s]?.let { "$s ${clock(it)} (%+.1f h)".format(p.phaseAngleHours(s)) } ?: "$s -" }
                val range = p.earliestTs?.let { "${clock(it)} to ${clock(p.latestTs!!)}" } ?: "none"
                appendLine("  $name ${p.date}: habitual onset ${clock(p.habitualOnsetTs)}; $scenarios; plausible $range; settled ${p.settled}${p.dlmoTs?.let { ", DLMO ${clock(it)}" } ?: ""}")
            }
        }
    }

    /** Spec §7 on the phone's history: how many phone-down gaps a day each minimum length would offer the card. */
    private suspend fun StringBuilder.appendGaps(room: NocturneDatabase, nights: List<NightEntity>) {
        val sessions = room.sessions().startingFrom(0).map { it.toNightSession() }
        val quiet = quietIntervals(nights)
        val config = SleepConfig(screenOffTimeoutMs = 30 * LocalClock.MINUTE_MS)
        fun day(ts: Long) = LocalDate.ofEpochDay(Math.floorDiv(ts + 330 * LocalClock.MINUTE_MS, LocalClock.DAY_MS))
        val days = sessions.map { day(it.startTs) }.distinct().sorted()
        appendLine("phone-down gaps outside sleep and evening windows, per local day of their end (${days.size} days with sessions):")
        for (minutes in listOf(30, 45, 60, 90, 120)) {
            val gaps = PhoneDownGaps.find(sessions, quiet, config, minutes * LocalClock.MINUTE_MS)
            val perDay = days.map { d -> gaps.count { day(it.endTs) == d } }
            appendLine("  at least $minutes min: ${gaps.size} gaps; per day ${perDay.joinToString(" ")}; median ${perDay.sorted().getOrNull(perDay.size / 2)}")
        }
        fun clock(ts: Long) = Instant.ofEpochMilli(ts).atOffset(ZoneOffset.ofHoursMinutes(5, 30)).toLocalDateTime().toString().replace('T', ' ').take(16)
        for (gap in PhoneDownGaps.find(sessions, quiet, config).takeLast(15)) {
            appendLine("  60 min: ${clock(gap.startTs)} to ${clock(gap.endTs).takeLast(5)} (${gap.durationMs / LocalClock.MINUTE_MS} min)")
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
