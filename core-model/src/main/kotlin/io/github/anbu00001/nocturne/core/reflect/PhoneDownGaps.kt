package io.github.anbu00001.nocturne.core.reflect

import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.sleep.NightSession
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.sleep.activeEndOf
import io.github.anbu00001.nocturne.core.time.LocalClock

/** A stretch with the phone put down (spec §7): from the last use of the phone to the next. */
data class PhoneDownGap(val startTs: Long, val endTs: Long) {
    val durationMs: Long get() = endTs - startTs

    fun overlaps(range: LongRange): Boolean = !range.isEmpty() && startTs <= range.last && range.first < endTs
}

object PhoneDownGaps {
    /** Shorter stretches are the ordinary pauses between checks; a focus block is 25 min. */
    const val MIN_GAP_MS = 60 * LocalClock.MINUTE_MS

    /**
     * The phone is in use during an unlock or a call. A lock-screen glance, an alarm or a wake the phone caused leaves it
     * down: reading a notification without unlocking does not end a stretch of work or rest. A session left to time out
     * was last used at its last activity, as sleep inference reads it.
     *
     * Leaves out gaps that overlap any of [quiet] (sleep and evening windows), the time before the first use, which the
     * history may not cover, and the time after the last use, which has not ended.
     */
    fun find(
        sessions: List<NightSession>,
        quiet: List<LongRange>,
        config: SleepConfig,
        minGapMs: Long = MIN_GAP_MS,
    ): List<PhoneDownGap> {
        val sorted = sessions.sortedBy { it.startTs }
        val gaps = ArrayList<PhoneDownGap>()
        var lastUseEnd: Long? = null
        for ((i, s) in sorted.withIndex()) {
            if (!s.unlocked && s.trigger != WakeTrigger.CALL) continue
            val from = lastUseEnd
            if (from != null && s.startTs - from >= minGapMs) {
                val gap = PhoneDownGap(from, s.startTs)
                if (quiet.none(gap::overlaps)) gaps += gap
            }
            val end = activeEndOf(s, sorted.getOrNull(i + 1)?.startTs ?: Long.MAX_VALUE, config)
            lastUseEnd = maxOf(from ?: end, end)
        }
        return gaps
    }
}
