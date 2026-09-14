package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.event.UsageEvent
import io.github.anbu00001.nocturne.core.glance.deriveSessions
import io.github.anbu00001.nocturne.core.time.LocalClock
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Developer tool: replays a Nocturne raw-event CSV (Settings, Export) through the classifier and sleep
 * inference and writes one line per night to build/sleep-replay.txt. The export is personal data: keep
 * it out of the repository.
 *
 * NOCTURNE_EVENTS_CSV names the export; NOCTURNE_SCREEN_TIMEOUT_MS is the phone's screen-off timeout
 * (default 30 min). NOCTURNE_SLEEP_LABELS optionally names a `night,onset,wake` file in local time
 * (`2026-09-06,00:10,08:30`) for the spec §10 check, at least 80% of nights within 30 min. Labelled
 * nights are also scored leave-one-out, with every other label entered as a report, which is how the
 * corrective offsets would do on a night nobody entered.
 */
@EnabledIfEnvironmentVariable(named = "NOCTURNE_EVENTS_CSV", matches = ".+")
class SleepReplayTest {

    @Test
    fun `replay a raw-event export through sleep inference`() {
        val rows = File(System.getenv("NOCTURNE_EVENTS_CSV")).readLines().drop(1).filter { it.isNotBlank() }.map(::csvFields)
        val events = rows.map { UsageEvent(it[1].toLong(), it[3].toInt(), it[4], it[5].ifEmpty { null }) }
        val offsetAt = HashMap<Long, Int>().apply { rows.forEach { put(it[1].toLong(), it[2].toInt()) } }
        val sessions = deriveSessions(events).sessions
        val config = SleepConfig(
            screenOffTimeoutMs = System.getenv("NOCTURNE_SCREEN_TIMEOUT_MS")?.toLong() ?: (30 * LocalClock.MINUTE_MS),
        )
        val inputs = SleepNights.inputs(
            sessions.map {
                OffsetSession(NightSession(it.startTs, it.endTs, it.lastActivityTs, it.kind, it.unlocked, it.trigger), offsetAt.getValue(it.startTs))
            },
            dataFromTs = events.first().timestamp,
            dataToTs = events.last().timestamp,
        )
        val nights = SleepNights.infer(emptyList(), inputs, emptyMap(), config)
        val labels = System.getenv("NOCTURNE_SLEEP_LABELS")?.let { readLabels(File(it), inputs) } ?: emptyMap()

        val report = buildString {
            appendLine("sessions ${sessions.size}, counted glances ${sessions.count { it.countsAsGlance }}")
            appendLine("triggers ${sessions.groupingBy { it.trigger }.eachCount()}")
            appendLine()
            appendLine("night       onset  wake   hours  conf  intr  runner-up     label        diff min")
            for (n in nights) {
                val onset = n.onsetTs?.let { clock(it, n.offsetMinutes) } ?: "  -  "
                val wake = n.wakeTs?.let { clock(it, n.offsetMinutes) } ?: "  -  "
                val hours = if (n.onsetTs != null && n.wakeTs != null) (n.wakeTs - n.onsetTs) / 3_600_000.0 else 0.0
                val runnerUp = n.inferred?.let { i ->
                    val rivalOnset = i.rivalOnsetTs
                    val rivalWake = i.rivalWakeTs
                    if (rivalOnset == null || rivalWake == null) "" else "${clock(rivalOnset, n.offsetMinutes)}-${clock(rivalWake, n.offsetMinutes)}"
                }.orEmpty()
                val label = labels[n.date]
                val labelText = label?.let { "${clock(it.onsetTs, n.offsetMinutes)}-${clock(it.wakeTs, n.offsetMinutes)}" } ?: ""
                val diff = if (label != null && n.onsetTs != null && n.wakeTs != null) {
                    "%+5d %+5d".format((n.onsetTs - label.onsetTs) / 60_000, (n.wakeTs - label.wakeTs) / 60_000)
                } else {
                    ""
                }
                appendLine("%s  %s  %s  %5.1f  %.2f  %3d  %-12s  %-12s %s".format(n.date, onset, wake, hours, n.confidence, n.interruptions, runnerUp, labelText, diff))
            }
            if (labels.isNotEmpty()) {
                appendLine()
                fun hitRate(pairs: List<Pair<Long?, Long>>) = pairs.count { (inferred, truth) ->
                    inferred != null && abs(inferred - truth) <= 30 * LocalClock.MINUTE_MS
                }
                val plain = labels.map { (date, label) -> nights.firstOrNull { it.date == date }?.onsetTs to label.onsetTs }
                val plainWake = labels.map { (date, label) -> nights.firstOrNull { it.date == date }?.wakeTs to label.wakeTs }
                appendLine("no reports:     onset within 30 min ${hitRate(plain)}/${labels.size}, wake ${hitRate(plainWake)}/${labels.size}")
                val heldOut = labels.keys.map { held ->
                    val others = labels.filterKeys { it != held }
                    SleepNights.infer(emptyList(), inputs, others, config).first { it.date == held } to labels.getValue(held)
                }
                appendLine(
                    "leave-one-out:  onset within 30 min ${hitRate(heldOut.map { (n, l) -> n.onsetTs to l.onsetTs })}/${labels.size}, " +
                        "wake ${hitRate(heldOut.map { (n, l) -> n.wakeTs to l.wakeTs })}/${labels.size}",
                )
            }
        }
        File("build/sleep-replay.txt").writeText(report)
        assertTrue(nights.isNotEmpty(), "no nights from ${sessions.size} sessions")
    }

    private fun clock(ts: Long, offsetMinutes: Int): String {
        val minute = LocalClock.minuteOfDay(ts, offsetMinutes)
        return "%02d:%02d".format(minute / 60, minute % 60)
    }

    /** Onsets at or after noon are on the night's own date, earlier ones on the next; wakes are on the next date. */
    private fun readLabels(file: File, inputs: List<NightInput>): Map<LocalDate, SleepReport> {
        val offsets = inputs.associate { it.date to it.offsetMinutes }
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (dateText, onsetText, wakeText) = line.split(",").map { it.trim() }
            val date = LocalDate.parse(dateText)
            val offset = offsets[date] ?: 0
            fun at(day: LocalDate, time: LocalTime) = localMidnightUtc(day, offset) + time.toSecondOfDay() * 1000L
            val onsetTime = LocalTime.parse(onsetText)
            val onset = at(if (onsetTime.hour >= 12) date else date.plusDays(1), onsetTime)
            date to SleepReport(date, onset, at(date.plusDays(1), LocalTime.parse(wakeText)))
        }
    }

    private fun csvFields(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    fields += field.toString()
                    field.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        fields += field.toString()
        return fields
    }
}
