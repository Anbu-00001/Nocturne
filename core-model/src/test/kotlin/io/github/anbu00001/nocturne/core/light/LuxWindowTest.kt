package io.github.anbu00001.nocturne.core.light

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuxWindowTest {

    @Test
    fun `a steady room that sends one event still gives a reading every window`() {
        val w = LuxWindow()
        w.start(0)
        assertTrue(w.onValue(100, 40.0).isEmpty())
        val closed = w.tick(95_000)
        assertEquals(listOf(0L, 30_000L, 60_000L), closed.map { it.startTs })
        assertTrue(closed.all { it.medianLux == 40.0 && it.durationMs == 30_000L }, "$closed")
        assertEquals(listOf(1, 0, 0), closed.map { it.events })
    }

    @Test
    fun `the median weighs values by how long they held, so a burst cannot outvote the window`() {
        val w = LuxWindow()
        w.start(0)
        w.onValue(0, 10.0)
        repeat(20) { w.onValue(20_000L + it * 10, 500.0) }
        w.onValue(20_200, 10.0)
        val reading = w.tick(30_000).single()
        assertEquals(10.0, reading.medianLux)
        assertEquals(22, reading.events)
    }

    @Test
    fun `screen-off keeps a cut-short window of at least 5 s and drops a shorter one`() {
        val kept = LuxWindow().apply { start(0); onValue(0, 3.0) }.stop(12_000)
        assertEquals(listOf(12_000L), kept.map { it.durationMs })
        assertEquals(3.0, kept.single().medianLux)
        val dropped = LuxWindow().apply { start(0); onValue(0, 3.0) }.stop(4_000)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `a window before the first event has no lux, and nothing is recorded while stopped`() {
        val w = LuxWindow()
        w.start(0)
        assertNull(w.tick(30_000).single().medianLux)
        assertTrue(w.stop(31_000).isEmpty())
        assertFalse(w.isRunning)
        assertTrue(w.onValue(40_000, 5.0).isEmpty())
        assertTrue(w.tick(100_000).isEmpty())
    }

    @Test
    fun `a new screen-on starts a fresh window without the previous value`() {
        val w = LuxWindow()
        w.start(0)
        w.onValue(0, 80.0)
        w.stop(10_000)
        w.start(50_000)
        val reading = w.tick(80_000).single()
        assertEquals(50_000L, reading.startTs)
        assertNull(reading.medianLux)
    }

    @Test
    fun `the weighted median picks the value that held for at least half the time`() {
        assertEquals(2.0, weightedMedian(listOf(1.0 to 4L, 2.0 to 5L, 9.0 to 1L)))
        assertEquals(1.0, weightedMedian(listOf(9.0 to 1L, 1.0 to 9L)))
        assertNull(weightedMedian(emptyList()))
    }
}
