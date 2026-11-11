package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.time.LocalClock

/**
 * An alarm set for the night: the clock app opened in the evening (18:00 to 03:00 local), and its first alarm ringing
 * [MIN_LEAD_MS] or more later. The clock's own list of alarms is closed to other apps on ColorOS, and the system's "next
 * alarm" is taken by any app that keeps itself alive with an alarm-clock alarm (the owner's ZAlarm does, every few
 * minutes), so the time is read afterwards from what the phone already records: the evening's last clock use, and the
 * first alarm that woke the screen after it.
 *
 * It is shown beside the estimate from screen inactivity and never moves it. In the history up to 11 Nov 2026 it held on
 * 26 of 64 nights; on 16 of those the screen went quiet within a minute of the clock, and on the rest sleep came from half
 * an hour to ten hours later, so setting an alarm is a sign of the night ahead, not the moment of sleep.
 */
data class BedtimeAlarm(val clockAt: Long, val firstRingAt: Long) {
    val leadMs: Long get() = firstRingAt - clockAt

    /** Where the screen-inactivity onset falls against the clock: within the half hour after it, later, or earlier. */
    fun placement(onset: Long?): Placement? = when {
        onset == null -> null
        onset < clockAt - EARLY_SLACK_MS -> Placement.BEFORE
        onset <= clockAt + AGREE_MS -> Placement.WITH
        else -> Placement.AFTER
    }

    enum class Placement { BEFORE, WITH, AFTER }

    companion object {
        const val MIN_LEAD_MS = 5 * LocalClock.HOUR_MS
        const val AGREE_MS = 30 * LocalClock.MINUTE_MS

        /** A clock use a few minutes after the screen went quiet is the same moment, not an onset before it. */
        const val EARLY_SLACK_MS = 5 * LocalClock.MINUTE_MS
        const val EVENING_START_HOUR = 18
        const val EVENING_HOURS = 9

        /** Night [eveningStart] is 18:00 local of the night's date, as an instant. */
        fun find(clockUses: List<Long>, rings: List<Long>, eveningStart: Long): BedtimeAlarm? {
            val clock = clockUses.filter { it >= eveningStart && it < eveningStart + EVENING_HOURS * LocalClock.HOUR_MS }.maxOrNull() ?: return null
            val ring = rings.filter { it > clock && it < eveningStart + LocalClock.DAY_MS - 6 * LocalClock.HOUR_MS }.minOrNull() ?: return null
            return BedtimeAlarm(clock, ring).takeIf { it.leadMs >= MIN_LEAD_MS }
        }

        fun eveningStart(nightEpochDay: Long, offsetMinutes: Int): Long =
            nightEpochDay * LocalClock.DAY_MS + EVENING_START_HOUR * LocalClock.HOUR_MS - offsetMinutes * LocalClock.MINUTE_MS
    }
}

/** Over a run of nights with an onset: how many had an alarm set for the night, and where sleep fell against it. */
data class BedtimeAlarmSummary(val nights: Int, val withAlarm: Int, val together: Int, val medianLaterMinutes: Double?) {
    companion object {
        fun of(nights: Int, alarms: List<Pair<BedtimeAlarm, Long?>>): BedtimeAlarmSummary {
            val placed = alarms.map { (alarm, onset) -> alarm.placement(onset) to (onset?.minus(alarm.clockAt)) }
            val later = placed.filter { it.first == BedtimeAlarm.Placement.AFTER }.mapNotNull { it.second?.toDouble()?.div(LocalClock.MINUTE_MS) }.sorted()
            val median = if (later.isEmpty()) null else if (later.size % 2 == 1) later[later.size / 2] else (later[later.size / 2 - 1] + later[later.size / 2]) / 2
            return BedtimeAlarmSummary(nights, alarms.size, placed.count { it.first == BedtimeAlarm.Placement.WITH }, median)
        }
    }
}
