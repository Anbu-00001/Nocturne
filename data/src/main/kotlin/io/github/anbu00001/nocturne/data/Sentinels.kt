package io.github.anbu00001.nocturne.data

import io.github.anbu00001.nocturne.core.detect.ChangeSentinel
import io.github.anbu00001.nocturne.core.detect.DatedValue
import io.github.anbu00001.nocturne.core.detect.SentinelResult
import io.github.anbu00001.nocturne.core.detect.SentinelSeries
import io.github.anbu00001.nocturne.core.metrics.MetricKey
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import java.time.LocalDate

/**
 * Phase 3.5: the drift sentinel over the stored nights and regularity windows. Nothing is stored: the whole history takes
 * milliseconds, and the answer follows every recompute because it is read from what the recompute wrote.
 */
class Sentinels(private val db: NocturneDatabase, private val sleep: SleepConfig = SleepConfig()) {

    data class Report(val onset: SentinelResult, val sri: SentinelResult)

    suspend fun assess(): Report {
        val onsets = db.sleep().nights().mapNotNull { it.sentinelOnset(sleep.confidentAt) }
        val sri = db.metrics().allWindows()
            .filter { it.windowDays == 7 && it.metric == MetricKey.SRI.name }
            .mapNotNull { row -> row.value?.let { DatedValue(LocalDate.parse(row.endDate), it) } }
        return Report(
            onset = ChangeSentinel.assess(SentinelSeries.SLEEP_ONSET, onsets),
            sri = ChangeSentinel.assess(SentinelSeries.SRI_WEEKLY, ChangeSentinel.weekly(sri)),
        )
    }
}

/** A night's onset for the sentinel: your own times, or an estimate confident enough to feed priors and the window. */
internal fun NightEntity.sentinelOnset(confidentAt: Double): DatedValue? {
    val onset = estimatedSleepOnset ?: return null
    if (noSleep || (source != SleepSource.USER_REPORTED && confidence < confidentAt)) return null
    return DatedValue(LocalDate.parse(dateOfNight), ChangeSentinel.onsetMinutesAfterNoon(onset, utcOffsetMinutes))
}
