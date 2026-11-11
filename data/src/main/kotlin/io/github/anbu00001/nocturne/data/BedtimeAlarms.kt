package io.github.anbu00001.nocturne.data

import io.github.anbu00001.nocturne.core.sleep.BedtimeAlarm
import io.github.anbu00001.nocturne.core.sleep.BedtimeAlarmSummary
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/**
 * Alarms set for the night ([BedtimeAlarm]), read from sessions the history already holds: no new permission, no new
 * table, and nothing here feeds the sleep estimate. [clockPackages] are the clock apps the classifier knows on this phone.
 */
class BedtimeAlarms(private val db: NocturneDatabase) {

    suspend fun forNight(clockPackages: List<String>, nightDate: String, offsetMinutes: Int): BedtimeAlarm? {
        val start = BedtimeAlarm.eveningStart(LocalDate.parse(nightDate).toEpochDay(), offsetMinutes)
        val dao = db.reflections()
        return BedtimeAlarm.find(dao.clockUses(clockPackages, start, start + LocalClock.DAY_MS), dao.alarmRings(start, start + LocalClock.DAY_MS), start)
    }

    /** Nights from [fromNight] that have an onset, and where that onset fell against an alarm set for the night. */
    suspend fun summary(clockPackages: List<String>, fromNight: LocalDate): BedtimeAlarmSummary {
        val nights = db.sleep().nights().filter { !it.noSleep && it.estimatedSleepOnset != null && LocalDate.parse(it.dateOfNight) >= fromNight }
        if (nights.isEmpty()) return BedtimeAlarmSummary(0, 0, 0, null)
        fun start(n: NightEntity) = BedtimeAlarm.eveningStart(LocalDate.parse(n.dateOfNight).toEpochDay(), n.utcOffsetMinutes)
        val from = nights.minOf(::start)
        val to = nights.maxOf(::start) + LocalClock.DAY_MS
        val dao = db.reflections()
        val uses = dao.clockUses(clockPackages, from, to)
        val rings = dao.alarmRings(from, to)
        val alarms = nights.mapNotNull { n -> BedtimeAlarm.find(uses, rings, start(n))?.let { it to n.estimatedSleepOnset } }
        return BedtimeAlarmSummary.of(nights.size, alarms)
    }
}
