package io.github.anbu00001.nocturne.data

import io.github.anbu00001.nocturne.core.metrics.FreeNights
import io.github.anbu00001.nocturne.core.metrics.MetricResult
import io.github.anbu00001.nocturne.core.metrics.NightRecord
import io.github.anbu00001.nocturne.core.metrics.RegularityWindows
import io.github.anbu00001.nocturne.core.metrics.WindowValue
import io.github.anbu00001.nocturne.core.sleep.SleepNights
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/**
 * Tier 1 regularity metrics (NOCTURNE_ANALYTICS.md §4), rebuilt in the same transaction as the nights they come from
 * rather than by a weekly worker: they cost milliseconds, and a second background job is one more thing for ColorOS
 * to kill. Rows are keyed by the night a window ends on, so recomputing from a night rewrites exactly the windows
 * that can include it.
 */
class WindowMetricsRecomputer(private val db: NocturneDatabase, private val freeNights: FreeNights = FreeNights()) {

    /** Rewrites every window ending on [fromDate] or later (all windows when null). Returns rows written. */
    suspend fun recompute(fromDate: LocalDate?, runId: Long): Int {
        val metrics = db.metrics()
        val nights = db.sleep().nights().map { it.toRecord() }
        metrics.deleteWindowsFrom(fromDate?.toString() ?: "")
        if (nights.isEmpty()) return 0
        val first = fromDate ?: nights.first().date
        val endDates = nights.map { it.date }.filter { it >= first }
        if (endDates.isEmpty()) return 0

        // Only the days the rewritten windows can reach need their screen sessions.
        val reach = first.minusDays(RegularityWindows.WINDOW_DAYS.max().toLong() + 1)
        val loadFromTs = SleepNights.groupStartUtc(reach, nights.first().offsetMinutes) - LocalClock.DAY_MS
        val screen = db.sessions().startingFrom(loadFromTs).map { it.startTs until it.endTs }
        val raw = db.rawEvents()
        val dataFromTs = raw.firstTimestamp() ?: Long.MAX_VALUE
        val dataToTs = maxOf(raw.lastTimestamp() ?: Long.MIN_VALUE, db.harvest().cursor() ?: Long.MIN_VALUE)

        val rows = RegularityWindows.compute(nights, screen, dataFromTs, dataToTs, endDates, freeNights).map { it.toEntity(runId) }
        metrics.upsertWindows(rows)
        return rows.size
    }
}

/** Finds or starts the model run for the configuration in force (analytics §6.2). */
class ModelRuns(private val db: NocturneDatabase, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun current(modelVersion: String, configText: String): Long {
        val dao = db.metrics()
        val latest = dao.latestRun()
        val at = now()
        if (latest != null && latest.modelVersion == modelVersion && latest.configText == configText) {
            dao.touchRun(latest.id, at)
            return latest.id
        }
        val trigger = when {
            latest == null -> "FIRST"
            latest.modelVersion != modelVersion -> "MODEL_CHANGE"
            else -> "CONFIG_CHANGE"
        }
        return dao.insertRun(ModelRunEntity(startedAt = at, lastUsedAt = at, modelVersion = modelVersion, trigger = trigger, configText = configText))
    }
}

private fun NightEntity.toRecord() = NightRecord(LocalDate.parse(dateOfNight), utcOffsetMinutes, estimatedSleepOnset, estimatedWakeTime, noSleep)

private fun WindowValue.toEntity(runId: Long): WindowMetricEntity = when (val r = result) {
    is MetricResult.Score -> WindowMetricEntity(endDate.toString(), windowDays, metric.name, r.value, r.atMinute, r.nights, r.coverage, null, 0, 0, runId)
    is MetricResult.Withheld -> WindowMetricEntity(endDate.toString(), windowDays, metric.name, null, null, 0, 0.0, r.reason.name, r.have, r.need, runId)
}
