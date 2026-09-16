package io.github.anbu00001.nocturne.core.focus

import io.github.anbu00001.nocturne.core.time.LocalClock

enum class TimerKind { FOCUS, BREAK }

/** A focus block or break in progress. Survives the process: the app keeps it in preferences and re-arms its alarm. */
data class RunningTimer(val kind: TimerKind, val startTs: Long, val plannedMinutes: Int) {
    val endTs: Long get() = startTs + plannedMinutes * LocalClock.MINUTE_MS

    fun remainingMs(now: Long): Long = (endTs - now).coerceAtLeast(0)

    fun finished(now: Long): Boolean = now >= endTs
}

/** Spec §7, "Pomodoro / focus timer": standard 25/5, configurable. */
object FocusTimer {
    const val DEFAULT_FOCUS_MINUTES = 25
    const val DEFAULT_BREAK_MINUTES = 5
    val FOCUS_MINUTES = listOf(15, 25, 45, 60)
    val BREAK_MINUTES = listOf(5, 10, 15)

    /** Unlocks that begin inside the block. The unlock it was started from began before it and is not counted. */
    fun interruptions(unlockStarts: List<Long>, startTs: Long, endTs: Long): Int = unlockStarts.count { it in startTs until endTs }
}
