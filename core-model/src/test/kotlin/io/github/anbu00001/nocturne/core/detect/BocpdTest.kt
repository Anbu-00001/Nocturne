package io.github.anbu00001.nocturne.core.detect

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BocpdTest {

    private fun series(seed: Long, vararg parts: Triple<Int, Double, Double>): DoubleArray {
        val random = Random(seed)
        return parts.flatMap { (n, mean, sd) -> List(n) { mean + sd * random.nextGaussian() } }.toDoubleArray()
    }

    @Test
    fun `a two-hour shift in onset is found within a week, at the night it began`() {
        // 60 nights around 00:20, then 00:20 plus 2 h.
        val values = series(5, Triple(60, 740.0, 40.0), Triple(25, 860.0, 40.0))
        val result = Bocpd.run(values, NormalGammaPrior.weak(740.0, 60.0))
        val week = assertNotNull(result.recentChange(window = 42, minSince = 7, afterValues = 67))
        assertTrue(week.probability > 0.9, "$week")
        assertTrue(week.startIndex in 58..62, "$week")
        val end = assertNotNull(result.recentChange(window = 42, minSince = 7))
        assertTrue(end.probability > 0.95 && end.startIndex in 58..62, "$end")
        assertEquals(values.size - end.startIndex, end.valuesSince)
    }

    @Test
    fun `steady nights seldom cross the level at which a shift is reported`() {
        // With the prior's spread matched to the nights', as ChangeSentinel sets it from the first two weeks.
        var nights = 0
        var over = 0
        var overRefit = 0
        for (seed in 1L..8L) {
            val values = series(seed, Triple(160, 740.0, 45.0))
            val result = Bocpd.run(values, NormalGammaPrior.weak(740.0, 45.0))
            for (t in 30..values.size) {
                val p = assertNotNull(result.recentChange(window = 42, minSince = 7, afterValues = t)).probability
                nights++
                if (p >= ChangeSentinel.SHOW_AT) over++
                if (p > ChangeSentinel.REFIT_AT) overRefit++
            }
        }
        // Measured with the reference on 40 series: 0.23% and 0.06% of nights.
        assertTrue(over <= nights / 100, "$over of $nights nights at or over ${ChangeSentinel.SHOW_AT}")
        assertTrue(overRefit <= nights / 200, "$overRefit of $nights nights over ${ChangeSentinel.REFIT_AT}")
    }

    @Test
    fun `run-length posteriors are distributions and a short history holds no recent change`() {
        val values = series(9, Triple(20, 60.0, 5.0))
        val result = Bocpd.run(values, NormalGammaPrior.weak(60.0, 8.0))
        for (t in 0..values.size) {
            val p = result.runLengths(t)
            assertEquals(t + 1, p.size)
            assertEquals(1.0, p.sum(), 1e-12)
            assertTrue(p.all { it in 0.0..1.0 })
        }
        assertNull(result.recentChange(window = 42, minSince = 7, afterValues = 7))
        assertNotNull(result.recentChange(window = 42, minSince = 7, afterValues = 8))
    }

    @Test
    fun `a value far outside every run does not break the recursion`() {
        val values = doubleArrayOf(0.0, 0.1, -0.1, 0.05, 1e6, 0.0)
        val result = Bocpd.run(values, NormalGammaPrior(0.0, 1.0, 0.1, 1e-6))
        assertTrue((0..values.size).all { t -> result.runLengths(t).all(Double::isFinite) })
    }
}
