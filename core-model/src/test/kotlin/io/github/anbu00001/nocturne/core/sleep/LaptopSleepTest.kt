package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LaptopSleepTest {
    private val inference = SleepInference()

    @Test
    fun `a quiet phone while someone is at the laptop is not sleep`() {
        // 14 Sept on the A18: the phone went quiet at 21:30 while the evening went on at the laptop.
        val sessions = busy(ist("2026-09-14", "19:00"), ist("2026-09-14", "21:30")) +
            busy(ist("2026-09-15", "07:00"), ist("2026-09-15", "10:00"))
        val phoneOnly = assertNotNull(inference.infer(night("2026-09-14", sessions), prior = null))
        assertEquals(ist("2026-09-14", "21:31:30"), phoneOnly.onsetTs)

        val laptop = listOf(ist("2026-09-14", "21:25")..ist("2026-09-15", "01:30"))
        val withLaptop = assertNotNull(inference.infer(night("2026-09-14", sessions).copy(laptop = laptop), prior = null))
        assertEquals(ist("2026-09-15", "01:30"), withLaptop.onsetTs)
        assertEquals(ist("2026-09-15", "07:00"), withLaptop.wakeTs)
    }

    @Test
    fun `waking to the laptop before the phone ends the night there`() {
        val sessions = busy(ist("2026-09-14", "21:00"), ist("2026-09-14", "23:50")) +
            busy(ist("2026-09-15", "10:00"), ist("2026-09-15", "13:00"))
        val laptop = listOf(ist("2026-09-15", "07:40")..ist("2026-09-15", "09:55"))
        val s = assertNotNull(inference.infer(night("2026-09-14", sessions).copy(laptop = laptop), prior = null))
        assertEquals(ist("2026-09-15", "07:40"), s.wakeTs)
    }

    @Test
    fun `a few minutes at the laptop in the night cost about a phone check and do not split the night`() {
        val sessions = busy(ist("2026-09-14", "21:00"), ist("2026-09-14", "23:58")) +
            busy(ist("2026-09-15", "08:00"), ist("2026-09-15", "11:00"))
        val plain = assertNotNull(inference.infer(night("2026-09-14", sessions), prior = null))
        val laptop = listOf(ist("2026-09-15", "03:00")..ist("2026-09-15", "03:04"))
        val s = assertNotNull(inference.infer(night("2026-09-14", sessions).copy(laptop = laptop), prior = null))
        assertEquals(plain.onsetTs, s.onsetTs)
        assertEquals(plain.wakeTs, s.wakeTs)
        // The nights table counts the phone's interruptions only.
        assertEquals(plain.interruptions, s.interruptions)
    }

    @Test
    fun `nights without laptop use score exactly as before`() {
        val sessions = busy(ist("2026-09-14", "21:00"), ist("2026-09-14", "23:30")) +
            session(ist("2026-09-15", "03:10"), 40) +
            busy(ist("2026-09-15", "07:30"), ist("2026-09-15", "11:30"))
        val far = listOf(ist("2026-09-16", "21:00")..ist("2026-09-16", "23:00"))
        assertEquals(
            inference.infer(night("2026-09-14", sessions), prior = null),
            inference.infer(night("2026-09-14", sessions).copy(laptop = far), prior = null),
        )
    }

    @Test
    fun `laptop use is weighed in pieces of ten minutes that overlap a candidate sleep`() {
        val m = LocalClock.MINUTE_MS
        val chunks = LaptopChunks.of(listOf(0L..25 * m, 40 * m..40 * m), fromTs = 0, toTs = 60 * m, chunkMs = 10 * m)
        assertEquals(listOf(0L..25 * m, 40 * m..40 * m), chunks.ranges)
        assertEquals(2, chunks.overlapping(5 * m, 12 * m))
        assertEquals(1, chunks.overlapping(10 * m, 10 * m + 1))
        assertEquals(0, chunks.overlapping(25 * m, 39 * m))
        assertEquals(1, chunks.overlapping(39 * m, 41 * m))
        assertEquals(4, chunks.overlapping(0, 60 * m))
        // Clipped to the search window.
        assertEquals(listOf(20 * m..25 * m), LaptopChunks.of(listOf(0L..25 * m), fromTs = 20 * m, toTs = 30 * m, chunkMs = 10 * m).ranges)
    }
}
