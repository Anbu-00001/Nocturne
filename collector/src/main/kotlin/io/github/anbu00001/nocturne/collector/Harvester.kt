package io.github.anbu00001.nocturne.collector

import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.core.time.ZoneChange
import io.github.anbu00001.nocturne.core.time.ZoneTimeline
import io.github.anbu00001.nocturne.data.HarvestOutcome
import io.github.anbu00001.nocturne.data.HarvestRunEntity
import io.github.anbu00001.nocturne.data.NocturneDatabase
import io.github.anbu00001.nocturne.data.SessionRecomputer
import io.github.anbu00001.nocturne.data.ZoneChangeEntity
import io.github.anbu00001.nocturne.data.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Copies the OS usage-event log into raw_events before it rolls off (spec §4.1).
 *
 * Safe to run at any time, any number of times, from the worker and the UI at once: inserts are
 * idempotent, the cursor only moves after a complete run, and sessions are re-derived over the
 * whole queried window, so rows left behind by a run that died halfway still get classified.
 */
class Harvester(
    private val db: NocturneDatabase,
    private val source: UsageEventSource,
    private val recomputer: SessionRecomputer,
    private val configFor: suspend (keyguardEventsSeen: Boolean) -> ClassifierConfig,
    private val now: () -> Long = System::currentTimeMillis,
    private val currentZoneId: () -> String = { ZoneId.systemDefault().id },
) {
    data class Result(val outcome: HarvestOutcome, val eventsSeen: Int = 0, val eventsInserted: Int = 0, val sessionsWritten: Int = 0)

    private val mutex = Mutex()

    suspend fun harvest(): Result = mutex.withLock {
        val startedAt = now()
        recordZone(startedAt, currentZoneId())
        if (!source.hasUsageAccess()) return log(startedAt, Result(HarvestOutcome.NO_ACCESS))
        if (!source.isUserUnlocked()) return log(startedAt, Result(HarvestOutcome.USER_LOCKED))

        val cursor = db.harvest().cursor()
        val queryFrom = if (cursor == null) startedAt - BACKFILL_MS else cursor - OVERLAP_MS
        val queryTo = startedAt
        try {
            val zones = ZoneTimeline(db.harvest().zones().map { ZoneChange(it.sinceTs, it.zoneId) })
            var seen = 0
            var inserted = 0
            var chunkFrom = queryFrom
            while (chunkFrom < queryTo) {
                val chunkTo = minOf(chunkFrom + CHUNK_MS, queryTo)
                val events = source.query(chunkFrom, chunkTo)
                seen += events.size
                inserted += db.rawEvents()
                    .insertAll(events.map { it.toEntity(zones.offsetMinutesAt(it.timestamp)) })
                    .count { it != -1L }
                chunkFrom = chunkTo
            }
            val written = if (seen > 0) {
                val keyguardSeen = db.rawEvents().anySince(EventType.KEYGUARD_HIDDEN, startedAt - KEYGUARD_LOOKBACK_MS)
                recomputer.recomputeFrom(queryFrom, configFor(keyguardSeen), EveningWindow.PROVISIONAL, currentZoneId())
            } else {
                0
            }
            log(startedAt, Result(HarvestOutcome.OK, seen, inserted, written), queryFrom, queryTo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(startedAt, Result(HarvestOutcome.FAILED), queryFrom, queryTo, e.toString())
        }
    }

    /** Rebuilds every session from raw_events (after a classifier change, say). Serialised with harvests. */
    suspend fun recomputeAll(): Int = mutex.withLock {
        val keyguardSeen = db.rawEvents().anySince(EventType.KEYGUARD_HIDDEN, now() - KEYGUARD_LOOKBACK_MS)
        recomputer.recomputeAll(configFor(keyguardSeen), EveningWindow.PROVISIONAL, currentZoneId())
    }

    /** Called with the exact time from ACTION_TIMEZONE_CHANGED; each harvest also checks, in case it was missed. */
    suspend fun recordZone(at: Long, zoneId: String) {
        val latest = db.harvest().latestZone()
        if (latest?.zoneId == zoneId) return
        // The first zone ever seen also covers the backfilled history before it.
        db.harvest().insertZone(ZoneChangeEntity(sinceTs = if (latest == null) 0 else at, zoneId = zoneId))
    }

    private suspend fun log(
        startedAt: Long,
        result: Result,
        queryFrom: Long? = null,
        queryTo: Long? = null,
        error: String? = null,
    ): Result {
        db.harvest().insertRun(
            HarvestRunEntity(
                startedAt = startedAt,
                finishedAt = now(),
                outcome = result.outcome,
                queryFrom = queryFrom,
                queryTo = queryTo,
                eventsSeen = result.eventsSeen,
                eventsInserted = result.eventsInserted,
                error = error,
            ),
        )
        return result
    }

    companion object {
        /** AOSP UsageStatsDatabase prunes daily files older than 10 days; take all of it on first run. */
        const val BACKFILL_MS = 10 * LocalClock.DAY_MS
        /** Re-read a little behind the cursor; duplicates are ignored, late-written events are not missed. */
        const val OVERLAP_MS = 5 * LocalClock.MINUTE_MS
        const val CHUNK_MS = 6 * LocalClock.HOUR_MS
        const val KEYGUARD_LOOKBACK_MS = 14 * LocalClock.DAY_MS
    }
}
