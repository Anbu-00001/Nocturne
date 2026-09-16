package io.github.anbu00001.nocturne.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.reflect.GapCard
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGap
import io.github.anbu00001.nocturne.export.FullExport
import java.io.File
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Debug builds only: drives Nocturne from the laptop without touching the phone's screen, which would itself become
 * events in what is being measured. The receiver requires DUMP, which adb's shell holds and apps cannot, so only
 * `adb shell` can send it. Times are local, in the phone's current zone. `am broadcast` prints the result.
 *
 *   adb shell am broadcast -n io.github.anbu00001.nocturne/.debug.DebugReportReceiver \
 *       --es night 2026-09-09 --es onset 2026-09-10T01:59 --es wake 2026-09-10T10:48
 *   ... --es night 2026-09-14 --ez noSleep true      a night without sleep
 *   ... --es night 2026-09-14 --ez remove true       take an entry back
 *   ... --es focus start --ei minutes 2              a short focus block (--es kind BREAK for a break)
 *   ... --es focus stop                              stop it early
 *   ... --es focus state                             the running timer and whether exact alarms are allowed
 *   ... --es focus list                              recorded blocks with their ids
 *   ... --es focus delete --el id 3                  remove a test block from the history
 *   ... --es gaps preview                            the card the rules would show now (no prompt recorded) and the weekly list
 *   ... --es export all                              Settings' "Export all data" into files/exports, then
 *       adb exec-out run-as io.github.anbu00001.nocturne cat files/exports/<name printed> > export.zip
 */
class DebugReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NocturneApp
        when {
            intent.getStringExtra("focus") in setOf("list", "delete") -> async(app) { focusBlocks(app, intent) }
            intent.hasExtra("focus") -> reply(runCatching { focus(app, intent) })
            intent.hasExtra("gaps") -> async(app) { gaps(app) }
            intent.hasExtra("export") -> async(app) { export(app) }
            else -> reply(runCatching { sleep(app, intent) })
        }
    }

    /** Database work off the main thread; `am broadcast` still waits for the result. */
    private fun async(app: NocturneApp, work: suspend () -> String) {
        val pending = goAsync()
        app.appScope.launch {
            val result = runCatching { work() }
            pending.setResult(code(result), text(result), null)
            pending.finish()
        }
    }

    private suspend fun focusBlocks(app: NocturneApp, intent: Intent): String {
        val dao = app.database.focus()
        if (intent.getStringExtra("focus") == "delete") {
            val id = intent.getLongExtra("id", -1)
            return if (dao.delete(id) == 1) "deleted block $id" else error("no block $id")
        }
        val zone = ZoneId.systemDefault()
        return dao.all().joinToString("; ") { b ->
            val start = Instant.ofEpochMilli(b.startTs).atZone(zone).toLocalDateTime().toString().take(16)
            "${b.id}: $start, ${(b.endTs - b.startTs) / 1000} s of ${b.plannedMinutes} min, completed ${b.completed}, unlocks ${b.interruptionCount}"
        }.ifEmpty { "no blocks" }
    }

    private fun focus(app: NocturneApp, intent: Intent): String {
        val focus = app.focus
        return when (val command = intent.getStringExtra("focus")) {
            "start" -> {
                val kind = TimerKind.valueOf(intent.getStringExtra("kind") ?: TimerKind.FOCUS.name)
                val minutes = intent.getIntExtra("minutes", if (kind == TimerKind.FOCUS) focus.focusMinutes else focus.breakMinutes)
                require(minutes in 1..120) { "--ei minutes must be 1 to 120" }
                focus.start(kind, minutes)
                "started: ${focus.running.value}, exact alarms ${focus.exactAlarms}"
            }
            "stop" -> {
                focus.stop()
                "stopped"
            }
            "state" -> "running ${focus.running.value}, exact alarms ${focus.exactAlarms}"
            else -> error("--es focus start|stop|state, not $command")
        }
    }

    private suspend fun export(app: NocturneApp): String {
        val dir = File(app.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, FullExport.fileName())
        val started = System.currentTimeMillis()
        val summary = file.outputStream().use { FullExport.write(app, it) }
        val took = System.currentTimeMillis() - started
        return "files/exports/${file.name}: ${file.length()} bytes, ${summary.total} rows in ${summary.rows.size} tables, $took ms\n" +
            summary.rows.entries.joinToString("\n") { (table, rows) -> "  $table $rows" }
    }

    private suspend fun gaps(app: NocturneApp): String {
        val config = app.deviceProfile.sleepConfig()
        val zone = ZoneId.systemDefault()
        fun time(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDateTime().toString().replace('T', ' ').take(16)
        fun gap(g: PhoneDownGap) = "${time(g.startTs)} to ${time(g.endTs).takeLast(5)}"
        val card = when (val c = app.reflections.preview(config)) {
            null -> "no card"
            is GapCard.Ask -> "would ask about ${gap(c.gap)}"
            is GapCard.Open -> "would show card ${c.reflection.id} again, ${time(c.reflection.gapStartTs!!)} to ${time(c.reflection.gapEndTs!!).takeLast(5)}"
        }
        val unlabelled = app.reflections.unlabelled(config)
        return "$card; ${unlabelled.size} unlabelled this week: " + unlabelled.take(8).joinToString("; ") { gap(it) }
    }

    private fun sleep(app: NocturneApp, intent: Intent): String {
        val night = LocalDate.parse(requireNotNull(intent.getStringExtra("night")) { "--es night YYYY-MM-DD is missing" })
        val zone = ZoneId.systemDefault()
        return when {
            intent.getBooleanExtra("remove", false) -> {
                app.removeSleepReport(night.toString())
                "removed $night"
            }
            intent.getBooleanExtra("noSleep", false) -> {
                app.saveNoSleepReport(night.toString(), night.atTime(12, 0).atZone(zone).offset.totalSeconds / 60)
                "no sleep on $night"
            }
            else -> {
                val onset = LocalDateTime.parse(requireNotNull(intent.getStringExtra("onset")) { "--es onset is missing" }).atZone(zone)
                val wake = LocalDateTime.parse(requireNotNull(intent.getStringExtra("wake")) { "--es wake is missing" }).atZone(zone)
                require(wake.isAfter(onset)) { "wake must be after onset" }
                app.saveSleepReport(night.toString(), onset.toInstant().toEpochMilli(), wake.toInstant().toEpochMilli(), onset.offset.totalSeconds / 60)
                "saved $night: $onset to $wake"
            }
        }
    }

    private fun reply(result: Result<String>) = setResult(code(result), text(result), null)

    private fun code(result: Result<String>) = if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED

    private fun text(result: Result<String>) = result.getOrElse { it.message ?: it.toString() }
}
