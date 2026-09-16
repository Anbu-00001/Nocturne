package io.github.anbu00001.nocturne.core.focus

import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusTimerTest {
    private val start = 1_000_000L

    @Test
    fun `a block counts the unlocks that begin inside it`() {
        val end = start + 25 * LocalClock.MINUTE_MS
        val unlocks = listOf(start - 1, start, start + 60_000, end - 1, end)
        assertEquals(3, FocusTimer.interruptions(unlocks, start, end))
        assertEquals(0, FocusTimer.interruptions(emptyList(), start, end))
    }

    @Test
    fun `a running timer knows its end and what is left`() {
        val timer = RunningTimer(TimerKind.FOCUS, start, 25)
        assertEquals(start + 25 * LocalClock.MINUTE_MS, timer.endTs)
        assertEquals(60_000, timer.remainingMs(timer.endTs - 60_000))
        assertEquals(0, timer.remainingMs(timer.endTs + 5_000))
        assertFalse(timer.finished(timer.endTs - 1))
        assertTrue(timer.finished(timer.endTs))
    }
}
