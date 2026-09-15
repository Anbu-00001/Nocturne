package io.github.anbu00001.nocturne.core.light

import io.github.anbu00001.nocturne.core.light.MelatoninSuppression.ed50
import io.github.anbu00001.nocturne.core.light.MelatoninSuppression.percent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MelatoninSuppressionTest {

    @Test
    fun `ED50 reproduces the duration table from Gimenez 2022 within 5 percent`() {
        val table = mapOf(30.0 to 600.0, 60.0 to 350.0, 120.0 to 120.0, 180.0 to 43.0, 240.0 to 15.0)
        for ((minutes, lux) in table) {
            val modelled = ed50(minutes)
            assertTrue(abs(modelled / lux - 1) < 0.05, "$minutes min: $modelled lx, table $lux lx")
        }
    }

    @Test
    fun `ED50 at 90 min matches the paper's worked values for natural and dilated pupils`() {
        assertEquals(208.0, ed50(90.0), 208 * 0.02)
        assertEquals(72.0, ed50(90.0, dilated = true), 72 * 0.02)
    }

    @Test
    fun `suppression is exactly half at ED50`() {
        for (minutes in listOf(30.0, 75.0, 240.0)) assertEquals(50.0, percent(ed50(minutes), minutes), 1e-9)
    }

    @Test
    fun `suppression rises with light and with duration`() {
        val lux = listOf(0.01, 0.1, 1.0, 10.0, 100.0, 1_000.0, 10_000.0)
        val minutes = (30..240 step 30).map { it.toDouble() }
        for (m in minutes) lux.zipWithNext { a, b -> assertTrue(percent(b, m) > percent(a, m), "$a -> $b lx at $m min") }
        for (l in lux) minutes.zipWithNext { a, b -> assertTrue(percent(l, b) > percent(l, a), "$a -> $b min at $l lx") }
    }

    @Test
    fun `the fitted curve is shallow, so a tenth of ED50 still suppresses about a quarter`() {
        val tenth = percent(ed50(120.0) / 10, 120.0)
        assertTrue(tenth in 20.0..35.0, "$tenth")
    }

    @Test
    fun `no light suppresses nothing, and durations beyond the fitted 30 to 240 min are clamped and flagged`() {
        assertEquals(0.0, percent(0.0, 60.0))
        assertEquals(percent(50.0, 240.0), percent(50.0, 400.0))
        assertTrue(MelatoninSuppression.isExtrapolated(10.0))
        assertTrue(MelatoninSuppression.isExtrapolated(300.0))
        assertFalse(MelatoninSuppression.isExtrapolated(120.0))
    }

    @Test
    fun `personal sensitivity multiplies ED50`() {
        assertEquals(2.0, ed50(60.0, sensitivity = 2.0) / ed50(60.0), 1e-9)
    }

    @Test
    fun `other studies' half-maximum points land where the model puts them, without being averaged in`() {
        // Phillips et al., PNAS 2019: 24.6 photopic lux under 3968 K fluorescent light for 5 h. The melanopic
        // ratio of a 4000 K triphosphor lamp is assumed at 0.6; the paper's main text does not give it.
        val phillips = 24.6 * 0.6
        val longest = ed50(240.0)
        assertTrue(phillips / longest in 0.5..2.0, "Phillips $phillips lx vs model $longest lx at 4 h")
        // Zeitzer et al., J Physiol 2000: about 106 photopic lux over 6.5 h, same assumed ratio.
        assertTrue(106 * 0.6 in longest..ed50(30.0))
    }

    @Test
    fun `an unbroken block of light reproduces the single-exposure model exactly, with no band`() {
        val estimate = EveningExposure.estimate(List(120) { Band.exact(50.0) })
        val expected = percent(50.0, 120.0)
        assertEquals(Band(expected, expected, expected), estimate.percent)
        assertEquals(100.0, estimate.melanopicDoseLuxHours.mid, 1e-9)
        assertEquals(120, estimate.exposedMinutes)
        assertFalse(estimate.durationClamped)
    }

    @Test
    fun `gaps in exposure widen the band between the longest run and the whole span`() {
        val profile = List(60) { Band.exact(50.0) } + List(60) { Band.exact(0.0) } + List(60) { Band.exact(50.0) }
        val estimate = EveningExposure.estimate(profile)
        val exposedMinutesOnly = percent(50.0, 120.0)
        val longestRun = percent(50.0, 60.0)
        val wholeSpan = percent(50.0 * 120 / 180, 180.0)
        assertEquals(exposedMinutesOnly, estimate.percent.mid, 1e-9)
        assertEquals(minOf(exposedMinutesOnly, longestRun, wholeSpan), estimate.percent.low, 1e-9)
        assertEquals(maxOf(exposedMinutesOnly, longestRun, wholeSpan), estimate.percent.high, 1e-9)
    }

    @Test
    fun `an evening kept under the sleep target counts no exposure but still records its dose`() {
        val estimate = EveningExposure.estimate(List(180) { Band.exact(0.5) })
        assertEquals(Band.ZERO, estimate.percent)
        assertEquals(0, estimate.exposedMinutes)
        assertEquals(1.5, estimate.melanopicDoseLuxHours.mid, 1e-9)
    }

    @Test
    fun `each bound picks its own exposed minutes, so light only the high end assumes still counts`() {
        val estimate = EveningExposure.estimate(List(120) { Band(0.0, 0.5, 50.0) })
        assertEquals(0.0, estimate.percent.low)
        assertEquals(0.0, estimate.percent.mid)
        assertEquals(percent(50.0, 120.0), estimate.percent.high, 1e-9)
        assertEquals(0, estimate.exposedMinutes)
    }
}
