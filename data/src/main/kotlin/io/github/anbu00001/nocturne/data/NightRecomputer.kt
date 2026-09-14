package io.github.anbu00001.nocturne.data

import androidx.room.withTransaction
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.sleep.HabitualWindow
import io.github.anbu00001.nocturne.core.sleep.NightSession
import io.github.anbu00001.nocturne.core.sleep.NightSleep
import io.github.anbu00001.nocturne.core.sleep.OffsetSession
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.SleepEstimate
import io.github.anbu00001.nocturne.core.sleep.SleepNights
import io.github.anbu00001.nocturne.core.sleep.SleepReport
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/** Sessions and nights are rebuilt together in one transaction, so no reader sees one without the other. */
class DerivedTables(private val db: NocturneDatabase) {
    private val sessions = SessionRecomputer(db)
    private val nights = NightRecomputer(db)

    /** Returns the number of sessions written. */
    suspend fun recomputeFrom(changedFromTs: Long, classifier: ClassifierConfig, sleep: SleepConfig, fallbackZoneId: String): Int =
        db.withTransaction {
            val written = sessions.recomputeFrom(changedFromTs, classifier, fallbackZoneId)
            nights.recomputeFrom(changedFromTs, sleep)
            written
        }

    suspend fun recomputeAll(classifier: ClassifierConfig, sleep: SleepConfig, fallbackZoneId: String): Int =
        db.withTransaction {
            val written = sessions.recomputeAll(classifier, fallbackZoneId)
            nights.recomputeAll(sleep)
            written
        }

    /** After the user enters or removes sleep times, which can move every night through corrective offsets. */
    suspend fun recomputeNights(sleep: SleepConfig): Int = nights.recomputeAll(sleep)
}

/**
 * Rebuilds the nights table, and each session's evening-window and sleep-onset tags, from sessions, the
 * user's sleep reports and charging samples (spec §6.3). Like SessionRecomputer, incremental and full
 * runs share one path and write the same rows; NightRecomputerTest checks that.
 */
class NightRecomputer(private val db: NocturneDatabase) {

    suspend fun recomputeAll(config: SleepConfig): Int = recompute(null, config)

    /** Re-derives every night that sessions at or after [changedFromTs] could have altered. Returns nights written. */
    suspend fun recomputeFrom(changedFromTs: Long, config: SleepConfig): Int = recompute(changedFromTs, config)

    private suspend fun recompute(changedFromTs: Long?, config: SleepConfig): Int = db.withTransaction {
        val sessions = db.sessions()
        val sleep = db.sleep()
        val raw = db.rawEvents()
        val reports = sleep.reports().associate { row ->
            val date = LocalDate.parse(row.dateOfNight)
            date to SleepReport(date, row.onsetTs, row.wakeTs)
        }
        val stored = sleep.nights().associateBy { LocalDate.parse(it.dateOfNight) }

        // The night before a change searches into the day the change lands on, so start from that night.
        // A reported night among those re-derived can move the corrective offsets and so every night: redo all.
        val restartOffset = changedFromTs?.let { sessions.offsetAtOrBefore(sessions.lastStartBefore(it) ?: it) } ?: 0
        val from: LocalDate? = changedFromTs
            ?.let { ts -> SleepNights.nightOf(sessions.lastStartBefore(ts) ?: ts, restartOffset).minusDays(1) }
            ?.takeIf { first -> reports.keys.none { it >= first } }

        val context = if (from == null) emptyList() else stored.filterKeys { it < from }.toSortedMap().values.map { it.toNightSleep(reports) }
        val loadFromTs = if (from == null) Long.MIN_VALUE else SleepNights.groupStartUtc(from, restartOffset) - LocalClock.HOUR_MS
        val samples = sleep.powerSamplesFrom(if (from == null) Long.MIN_VALUE else loadFromTs - LocalClock.DAY_MS)
        val inputs = SleepNights.inputs(
            sessions = sessions.startingFrom(loadFromTs).map { OffsetSession(it.toNightSession(), it.utcOffsetMinutes) },
            charging = SleepNights.chargingIntervals(samples.map { it.timestamp to it.charging }),
            dataFromTs = raw.firstTimestamp() ?: Long.MAX_VALUE,
            dataToTs = maxOf(raw.lastTimestamp() ?: Long.MIN_VALUE, db.harvest().cursor() ?: Long.MIN_VALUE),
        ).filter { from == null || it.date >= from }
        val fresh = SleepNights.infer(context, inputs, reports, config).associateBy { it.date }
        val all = context + fresh.values.sortedBy { it.date }
        val contextByDate = context.associateBy { it.date }

        val dates = (sessions.nightDates().map(LocalDate::parse) + fresh.keys).toSortedSet()
        val windows = SleepNights.windowsFor(dates, all, config)
        val rows = ArrayList<NightEntity>()
        for (date in dates) {
            val window = windows.getValue(date)
            val previous = stored[date]
            val isFresh = from == null || date >= from
            // An earlier night only changes when the window for its week does.
            if (!isFresh && previous != null && previous.hasWindow(window)) continue
            val night = if (isFresh) fresh[date] else contextByDate[date]
            val key = date.toString()
            sessions.tagNight(key, window.window.startMinute, window.window.endMinute, night?.onsetTs)
            val eveningMinutes = (sessions.eveningScreenMs(key) / LocalClock.MINUTE_MS).toInt()
            rows += when {
                night != null -> night.toEntity(eveningMinutes)
                else -> NightEntity(key, null, null, 0f, SleepSource.INFERRED, eveningMinutes, 0, null, null, null, null, null)
            }.withWindow(window)
        }
        sleep.deleteNightsFrom(from?.toString() ?: "")
        sleep.upsertNights(rows)
        rows.size
    }
}

internal fun SessionEntity.toNightSession() = NightSession(startTs, endTs, maxOf(lastActivityTs, startTs), kind, unlocked, trigger)

private fun NightSleep.toEntity(eveningMinutes: Int) = NightEntity(
    dateOfNight = date.toString(),
    estimatedSleepOnset = onsetTs,
    estimatedWakeTime = wakeTs,
    confidence = confidence.toFloat(),
    source = source,
    eveningScreenMinutes = eveningMinutes,
    postOnsetInterruptions = interruptions,
    modelledSuppressionPct = null,
    suppressionLowPct = null,
    suppressionHighPct = null,
    modelledPhaseShiftMin = null,
    melanopicDoseLuxHours = null,
    inferredSleepOnset = inferred?.onsetTs,
    inferredWakeTime = inferred?.wakeTs,
    inferredConfidence = inferred?.confidence ?: 0.0,
    utcOffsetMinutes = offsetMinutes,
)

/** Rebuilds what later nights need from an earlier stored night: its raw inference and final times. */
private fun NightEntity.toNightSleep(reports: Map<LocalDate, SleepReport>): NightSleep {
    val date = LocalDate.parse(dateOfNight)
    val onset = inferredSleepOnset
    val wake = inferredWakeTime
    return NightSleep(
        date = date,
        offsetMinutes = utcOffsetMinutes,
        inferred = if (onset != null && wake != null) SleepEstimate(onset, wake, inferredConfidence, postOnsetInterruptions) else null,
        report = reports[date],
        onsetTs = estimatedSleepOnset,
        wakeTs = estimatedWakeTime,
        confidence = confidence.toDouble(),
        source = source,
        interruptions = postOnsetInterruptions,
    )
}

private fun NightEntity.withWindow(w: HabitualWindow) = copy(
    eveningWindowStartMinute = w.window.startMinute,
    eveningWindowEndMinute = w.window.endMinute,
    windowPersonalised = w.personalised,
    windowNights = w.nights,
)

private fun NightEntity.hasWindow(w: HabitualWindow) =
    eveningWindowStartMinute == w.window.startMinute && eveningWindowEndMinute == w.window.endMinute &&
        windowPersonalised == w.personalised && windowNights == w.nights
