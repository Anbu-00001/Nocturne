package io.github.anbu00001.nocturne.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A time zone observed to be in force from [sinceTs] (epoch millis) onwards. */
data class ZoneChange(val sinceTs: Long, val zoneId: String)

/**
 * Resolves the UTC offset that applied at any instant, from a log of zone changes.
 *
 * The database stores UTC plus the offset captured for the event's own instant (spec §5), so an
 * "after 23:00" query stays correct across DST transitions and travel. Using the offset at read
 * time instead would silently shift every event on the far side of a transition.
 */
class ZoneTimeline(changes: List<ZoneChange>) {
    private val sorted = changes.sortedBy { it.sinceTs }
    private val zones = sorted.map { ZoneId.of(it.zoneId) }

    init {
        require(sorted.isNotEmpty()) { "ZoneTimeline needs at least one zone" }
    }

    /** Instants before the first recorded change fall back to the earliest known zone. */
    fun zoneAt(ts: Long): ZoneId {
        val idx = sorted.indexOfLast { it.sinceTs <= ts }
        return zones[if (idx < 0) 0 else idx]
    }

    fun offsetMinutesAt(ts: Long): Int = zoneAt(ts).rules.getOffset(Instant.ofEpochMilli(ts)).totalSeconds / 60
}

object LocalClock {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
    const val MINUTES_PER_DAY = 24 * 60

    /** Local hour at which one night hands over to the next. */
    const val NIGHT_BOUNDARY_HOUR = 12

    fun localMillis(ts: Long, offsetMinutes: Int): Long = ts + offsetMinutes * MINUTE_MS

    fun minuteOfDay(ts: Long, offsetMinutes: Int): Int =
        (Math.floorMod(localMillis(ts, offsetMinutes), DAY_MS) / MINUTE_MS).toInt()

    /** The night an instant belongs to, named by the evening it starts on: 01:30 on the 15th is the night of the 14th. */
    fun nightOf(ts: Long, offsetMinutes: Int): LocalDate =
        LocalDate.ofEpochDay(Math.floorDiv(localMillis(ts, offsetMinutes) - NIGHT_BOUNDARY_HOUR * HOUR_MS, DAY_MS))
}
