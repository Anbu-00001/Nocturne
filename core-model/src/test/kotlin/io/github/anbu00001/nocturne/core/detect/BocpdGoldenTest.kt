package io.github.anbu00001.nocturne.core.detect

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every run-length posterior against bayesian_changepoint_detection 0.2.dev1 (tools/bocpd/bocpd_golden.py). */
class BocpdGoldenTest {

    @Test
    fun `a change in onset matches the reference after every value`() = check("onset-step")

    @Test
    fun `a steady SRI matches the reference after every value`() = check("sri-flat")

    @Test
    fun `a sharp prior and an outlier match the reference after every value`() = check("sharp-prior")

    @Test
    fun `the Student-t density is SciPy's`() {
        assertEquals(-1.4328444112317154, Bocpd.studentTLogPdf(0.5, df = 3.0, loc = 0.2, scale = 1.5), 1e-12)
        assertEquals(-5.947551769300061, Bocpd.studentTLogPdf(812.0, df = 2.0, loc = 740.0, scale = 60.0), 1e-12)
    }

    private fun check(name: String) {
        val lines = checkNotNull(javaClass.getResource("/bocpd/$name.txt")) { "missing /bocpd/$name.txt" }.readText().lines()
        fun field(key: String) = lines.first { it.startsWith("$key ") }.substringAfter(' ').split(' ').map(String::toDouble)
        val (mu, kappa, alpha, beta) = field("prior")
        val hazard = field("hazard").single()
        val values = field("values").toDoubleArray()
        val result = Bocpd.run(values, NormalGammaPrior(mu, kappa, alpha, beta), hazard)
        assertEquals(values.size, result.size)
        var worst = 0.0
        for (t in 0..values.size) {
            val want = field("R$t")
            val got = result.runLengths(t)
            assertEquals(want.size, got.size, "column $t")
            for (r in want.indices) worst = maxOf(worst, abs(want[r] - got[r]))
        }
        assertTrue(worst < 1e-9, "$name: largest difference $worst")
    }
}
