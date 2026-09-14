package io.github.anbu00001.nocturne.core.glance

import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.event.UsageEvent
import io.github.anbu00001.nocturne.core.time.LocalClock
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Developer tool: replays `adb shell dumpsys usagestats` through the classifier and writes a
 * summary (counts only, no app names) to build/dumpsys-replay.txt.
 *
 * Skipped unless NOCTURNE_DUMPSYS names a dump file; NOCTURNE_TZ is the phone's zone.
 * The dump has whole-second timestamps, so same-second events keep their file order.
 */
@EnabledIfEnvironmentVariable(named = "NOCTURNE_DUMPSYS", matches = ".+")
class DumpsysReplayTest {

    private val eventLine = Regex("""time="([0-9-]+ [0-9:]+)" type=([A-Z_]+) package=(\S+)(?: class=(\S+))?""")
    private val typesByName = mapOf(
        "ACTIVITY_RESUMED" to EventType.ACTIVITY_RESUMED,
        "ACTIVITY_PAUSED" to EventType.ACTIVITY_PAUSED,
        "ACTIVITY_STOPPED" to EventType.ACTIVITY_STOPPED,
        "USER_INTERACTION" to EventType.USER_INTERACTION,
        "NOTIFICATION_INTERRUPTION" to EventType.NOTIFICATION_INTERRUPTION,
        "SCREEN_INTERACTIVE" to EventType.SCREEN_INTERACTIVE,
        "SCREEN_NON_INTERACTIVE" to EventType.SCREEN_NON_INTERACTIVE,
        "KEYGUARD_SHOWN" to EventType.KEYGUARD_SHOWN,
        "KEYGUARD_HIDDEN" to EventType.KEYGUARD_HIDDEN,
        "DEVICE_SHUTDOWN" to EventType.DEVICE_SHUTDOWN,
        "DEVICE_STARTUP" to EventType.DEVICE_STARTUP,
    )

    @Test
    fun `replay a real dumpsys usagestats dump`() {
        val zone = ZoneId.of(System.getenv("NOCTURNE_TZ") ?: "UTC")
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val events = File(System.getenv("NOCTURNE_DUMPSYS")).useLines { lines ->
            lines.takeWhile { "In-memory daily stats" !in it } // later sections repeat aggregates, not events
                .mapNotNull { eventLine.find(it) }
                .mapNotNull { m ->
                    val type = typesByName[m.groupValues[2]] ?: return@mapNotNull null
                    val ts = LocalDateTime.parse(m.groupValues[1], format).atZone(zone).toInstant().toEpochMilli()
                    UsageEvent(ts, type, m.groupValues[3], m.groupValues[4].ifEmpty { null })
                }
                .toList()
        }
        val sessions = deriveSessions(events).sessions
        val offset = { ts: Long -> zone.rules.getOffset(java.time.Instant.ofEpochMilli(ts)).totalSeconds / 60 }

        val report = buildString {
            appendLine("events used: ${events.size}, sessions: ${sessions.size}")
            appendLine("counted glances: ${sessions.count { it.countsAsGlance }}")
            appendLine("unlocked: ${sessions.count { it.unlocked }}, end inferred: ${sessions.count { it.endInferred }}")
            appendLine()
            appendLine("kind               n   median s   max s")
            for ((kind, group) in sessions.groupBy { it.kind }.toSortedMap()) {
                val secs = group.map { it.durationMs / 1000.0 }.sorted()
                appendLine("%-17s %3d   %8.1f  %6.0f".format(kind, group.size, secs[secs.size / 2], secs.last()))
            }
            appendLine()
            appendLine("triggers: " + sessions.groupingBy { it.trigger }.eachCount())
            appendLine()
            appendLine("wakes by local hour:")
            val byHour = sessions.groupingBy { LocalClock.minuteOfDay(it.startTs, offset(it.startTs)) / 60 }.eachCount()
            for (h in 0 until 24) appendLine("%02d %s".format(h, "#".repeat(byHour[h] ?: 0)))
        }
        File("build/dumpsys-replay.txt").writeText(report)
        assertTrue(sessions.isNotEmpty(), "no sessions derived from ${events.size} events")
    }
}
