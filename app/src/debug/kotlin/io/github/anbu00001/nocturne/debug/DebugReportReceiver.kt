package io.github.anbu00001.nocturne.debug

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.checkin.CheckInNotification
import io.github.anbu00001.nocturne.data.CheckIns
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.reflect.GapCard
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGap
import io.github.anbu00001.nocturne.data.MindHistory
import io.github.anbu00001.nocturne.data.MusicHistory
import io.github.anbu00001.nocturne.ui.MindText
import io.github.anbu00001.nocturne.export.FullExport
import io.github.anbu00001.nocturne.tone.Tone
import io.github.anbu00001.nocturne.ui.GlanceText
import io.github.anbu00001.nocturne.ui.formatNight
import io.github.anbu00001.nocturne.widget.NocturneWidget
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
 *   ... --es night 2026-11-09 --ez confirm true  "About right" for that night, as the widget's tap does
 *   ... --es glance show                         every line the widget and Patterns' "This week" would show now
 *   ... --es mind show                           Patterns' Rhythm section and the check-in lines (no check-in is ever written from here)
 *   ... --es checkin notify|cancel|state            post the evening check-in notification now (no day is marked asked), take it
 *                                                    away, or print the switch, the day last asked and today's answer
 *   ... --es music show                          music before sleep for the last 14 nights, and Patterns' comparison
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
            intent.hasExtra("mind") -> async(app) { mind(app) }
            intent.hasExtra("music") -> async(app) { music(app) }
            intent.hasExtra("checkin") -> async(app) { checkin(app, intent) }
            intent.hasExtra("glance") -> async(app) { glance(app) }
            intent.getBooleanExtra("confirm", false) -> async(app) { confirm(app, intent) }
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

    /** The widget's lines, then Patterns' paragraph, as they would be drawn now; whether a widget is placed is said too. */
    private suspend fun glance(app: NocturneApp): String {
        val glance = app.digests.glance(System.currentTimeMillis())
        val night = glance.night
        val placed = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, NocturneWidget::class.java)).size
        val widget = listOfNotNull(
            night?.let { Tone.LastNight.nightTitle(formatNight(it.dateOfNight)) },
            night?.let { GlanceText.night(it, glance.report) } ?: Tone.Widget.EMPTY,
            night?.takeIf { GlanceText.canConfirm(it, glance.report) }?.let { "[${Tone.Sleep.ABOUT_RIGHT}] [${Tone.Sleep.NOT_RIGHT}]" },
            glance.week?.let(GlanceText::weekShort),
            glance.week?.let(GlanceText::beforeShort),
            glance.laptopQuietSince?.let(GlanceText::laptopQuiet),
        )
        val patterns = glance.week?.let { GlanceText.weekLines(it) + GlanceText.lateApps(it, app.labels::of) }.orEmpty()
        return "widgets placed: $placed\nWIDGET\n" + widget.joinToString("\n") { "  $it" } + "\nPATTERNS\n" + patterns.joinToString("\n") { "  $it" }
    }

    private suspend fun mind(app: NocturneApp): String {
        val now = System.currentTimeMillis()
        val report = MindHistory(app.database).report(now, ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60)
        return "RHYTHM\n" + MindText.lines(report).joinToString("\n") { "  ${it.text}" } + "\nCHECK-IN\n" + MindText.links(report).joinToString("\n") { "  ${it.text}" }
    }

    private suspend fun checkin(app: NocturneApp, intent: Intent): String = when (intent.getStringExtra("checkin")) {
        "notify" -> if (CheckInNotification.post(app)) "posted" else "not posted: notifications are not allowed"
        "cancel" -> { CheckInNotification.cancel(app); "cancelled" }
        else -> {
            val now = System.currentTimeMillis()
            val offset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60
            val prefs = app.getSharedPreferences(NocturneApp.PREFS, Context.MODE_PRIVATE)
            "enabled=${CheckInNotification.enabled(app)} lastAsked=${prefs.getString("checkInPostedDay", null)} " +
                "today=${CheckIns(app.database).today(now, offset)?.checkIn}"
        }
    }

    private suspend fun music(app: NocturneApp): String {
        val report = MusicHistory(app.database).report()
        val nights = report.byNight.entries.toList().takeLast(14).joinToString("\n") { (date, m) -> "  $date  " + GlanceText.music(m).joinToString(" ") }
        return "LAST NIGHTS\n$nights\nPATTERNS\n" + (GlanceText.musicDays(report) + GlanceText.musicSplit(report)).joinToString("\n") { "  $it" }
    }

    /** Waits for the nights to be re-derived, so the reply describes the night as it now stands. */
    private suspend fun confirm(app: NocturneApp, intent: Intent): String {
        val night = requireNotNull(intent.getStringExtra("night")) { "--es night YYYY-MM-DD is missing" }
        val before = app.database.sleep().report(night)
        app.confirmSleepEstimate(night).join()
        val after = app.database.sleep().report(night)
        val row = app.database.sleep().night(night)
        return when {
            before != null -> "$night already has an entry (confirmed ${before.confirmed}); nothing changed"
            after == null -> "$night has no estimate to confirm"
            else -> "confirmed $night: ${row?.let { GlanceText.night(it, after) }}, source ${row?.source}, confidence ${row?.confidence}"
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
