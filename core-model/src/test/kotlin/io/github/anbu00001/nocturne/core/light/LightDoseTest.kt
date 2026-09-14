package io.github.anbu00001.nocturne.core.light

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightDoseTest {
    private val a18 = DisplayProfile.OPPO_A18

    @Test
    fun `the A18 brightness setting maps linearly onto its 2 to 490 nit range`() {
        assertEquals(2.0, a18.luminanceNits(0), 1e-9)
        assertEquals(490.0, a18.luminanceNits(4095), 1e-9)
        assertEquals(245.94, a18.luminanceNits(2047), 0.01)
        assertEquals(490.0, a18.luminanceNits(9_999), 1e-9)
    }

    @Test
    fun `treating the stored setting as perceptual would halve mid-range luminance, so it is taken as linear`() {
        val perceptual = a18.copy(settingIsLinear = false)
        assertTrue(perceptual.luminanceNits(2047) < a18.luminanceNits(2047) / 2)
    }

    @Test
    fun `A18 panel area from its diagonal and resolution`() {
        assertEquals(0.010338, a18.screenAreaM2, 0.000005)
    }

    @Test
    fun `screen light falls with the square of viewing distance`() {
        fun at(distance: Double) = LightDose.screenMelanopicEdi(
            200.0, a18, darkUi = false, warmFilter = false,
            assumptions = LightAssumptions(viewingDistanceM = Band.exact(distance)),
        ).mid
        assertEquals(4.0, at(0.25) / at(0.5), 1e-9)
    }

    @Test
    fun `a warm night filter roughly halves the screen's melanopic light`() {
        val normal = LightDose.screenMelanopicEdi(200.0, a18, darkUi = false, warmFilter = false)
        val warm = LightDose.screenMelanopicEdi(200.0, a18, darkUi = false, warmFilter = true)
        assertTrue(warm.mid / normal.mid in 0.4..0.7)
    }

    @Test
    fun `a bright light-mode screen at 35 cm exceeds the evening target, this phone's actual night setup stays under the sleep target`() {
        val bright = LightDose.screenMelanopicEdi(a18.luminanceNits(3276), a18, darkUi = false, warmFilter = false)
        assertTrue(bright.mid > MelanopicTargets.EVENING_MAX_LUX, "$bright")
        // Read from the phone at 01:30: auto brightness at 140 of 4095, dark mode on, 2700 K eye comfort on.
        val night = LightDose.screenMelanopicEdi(a18.luminanceNits(140), a18, darkUi = true, warmFilter = true)
        assertTrue(night.high < MelanopicTargets.SLEEP_MAX_LUX, "$night")
    }

    @Test
    fun `room light uses the sensor as a weak prior, and a missing reading falls back to the evening prior`() {
        val assumptions = LightAssumptions()
        val missing = LightDose.ambientMelanopicEdi(null, evening = true)
        assertEquals(assumptions.eveningAmbientPriorLux.mid * assumptions.eveningAmbientMder.mid, missing.mid, 1e-9)
        val measured = LightDose.ambientMelanopicEdi(100.0, evening = true)
        assertEquals(100 * 0.7 * 0.48, measured.mid, 1e-9)
        assertTrue(measured.high / measured.low > 5, "the band should stay wide: $measured")
    }
}
