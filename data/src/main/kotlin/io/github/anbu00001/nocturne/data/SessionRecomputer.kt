package io.github.anbu00001.nocturne.data

import androidx.room.withTransaction
import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.event.UsageEvent
import io.github.anbu00001.nocturne.core.glance.ClassifiedSession
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.glance.SessionDeriver
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.core.time.ZoneChange
import io.github.anbu00001.nocturne.core.time.ZoneTimeline

/**
 * Rebuilds the derived sessions table from raw_events (spec §5). Incremental and full rebuilds
 * run the same code, so recomputeAll() is exact by construction.
 */
class SessionRecomputer(private val db: NocturneDatabase) {

    suspend fun recomputeAll(config: ClassifierConfig, window: EveningWindow, fallbackZoneId: String): Int =
        recomputeFrom(0, config, window, fallbackZoneId)

    /**
     * Re-derives every session that raw events at or after [changedFromTs] could have altered.
     * Returns the number of sessions written.
     */
    suspend fun recomputeFrom(
        changedFromTs: Long,
        config: ClassifierConfig,
        window: EveningWindow,
        fallbackZoneId: String,
    ): Int = db.withTransaction {
        val sessions = db.sessions()
        val raw = db.rawEvents()

        // The session in progress at changedFromTs may have gained events, so restart from its wake.
        val restartTs = sessions.lastStartBefore(changedFromTs) ?: changedFromTs
        // Feed from the last keyguard or boot transition before that so lock state is known, and at
        // least a minute earlier so a notification or alarm that caused the wake is seen too.
        val feedFromTs = minOf(raw.lastOfTypesBefore(STATE_EVENT_TYPES, restartTs) ?: restartTs, restartTs - LEAD_MS)

        val stored = db.harvest().zones().map { ZoneChange(it.sinceTs, it.zoneId) }
        val zones = ZoneTimeline(stored.ifEmpty { listOf(ZoneChange(0, fallbackZoneId)) })

        sessions.deleteFrom(restartTs)
        val deriver = SessionDeriver(config)
        val pendingSessions = ArrayList<SessionEntity>()
        val pendingApps = ArrayList<SessionAppEntity>()
        var written = 0

        suspend fun flush() {
            sessions.insertAll(pendingSessions)
            sessions.insertApps(pendingApps)
            written += pendingSessions.size
            pendingSessions.clear()
            pendingApps.clear()
        }

        var afterTs = feedFromTs - 1
        var afterId = Long.MAX_VALUE
        while (true) {
            val page = raw.pageAfter(afterTs, afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                val closed = deriver.feed(row.toUsageEvent()) ?: continue
                if (closed.startTs < restartTs) continue
                val offset = zones.offsetMinutesAt(closed.startTs)
                pendingSessions += closed.toEntity(offset, window)
                closed.foregroundMs.mapTo(pendingApps) { (pkg, ms) -> SessionAppEntity(closed.startTs, pkg, ms) }
            }
            afterTs = page.last().timestamp
            afterId = page.last().id
            if (pendingSessions.size >= FLUSH_SIZE) flush()
        }
        flush()
        written
    }

    private companion object {
        const val PAGE_SIZE = 5_000
        const val FLUSH_SIZE = 2_000
        const val LEAD_MS = 60_000L
        val STATE_EVENT_TYPES = listOf(
            EventType.KEYGUARD_SHOWN,
            EventType.KEYGUARD_HIDDEN,
            EventType.DEVICE_STARTUP,
            EventType.DEVICE_SHUTDOWN,
        )
    }
}

fun RawEventEntity.toUsageEvent() = UsageEvent(timestamp, eventType, packageName, className.ifEmpty { null })

fun UsageEvent.toEntity(utcOffsetMinutes: Int) = RawEventEntity(
    timestamp = timestamp,
    utcOffsetMinutes = utcOffsetMinutes,
    eventType = type,
    packageName = packageName,
    className = className.orEmpty(),
)

internal fun ClassifiedSession.toEntity(utcOffsetMinutes: Int, window: EveningWindow) = SessionEntity(
    startTs = startTs,
    endTs = endTs,
    kind = kind,
    unlocked = unlocked,
    trigger = trigger,
    countsAsGlance = countsAsGlance,
    dominantPackage = dominantPackage,
    appCount = appCount,
    endInferred = endInferred,
    utcOffsetMinutes = utcOffsetMinutes,
    nightDate = LocalClock.nightOf(startTs, utcOffsetMinutes).toString(),
    inEveningWindow = window.contains(startTs, utcOffsetMinutes),
    sleepOnsetOffsetMin = null,
)
