package io.github.anbu00001.nocturne.core.light

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaptopLightTest {
    private val assumptions = LightAssumptions()
    private val unmeasured = UnmeasuredLight()

    /** The laptop Phase 4 was built on: a 16" 345 x 215 mm panel sold at 250 nits. */
    private val panel = DisplayProfile.panel(minNits = 2.0, peakNits = 250.0, widthMm = 345, heightMm = 215)

    @Test
    fun `a laptop screen is modelled at its own distance, over dark and light content alike`() {
        val lit = EveningLight.laptopAtEyes(backlight = 1.0, warmFilter = false, profile = panel)
        val d = assumptions.laptopViewingDistanceM
        val area = 0.345 * 0.215
        assertEquals(250.0 * assumptions.darkUiContentLevel.low * area / (d.high * d.high) * assumptions.displayMder.low, lit.low, 1e-9)
        assertEquals(250.0 * assumptions.lightUiContentLevel.high * area / (d.low * d.low) * assumptions.displayMder.high, lit.high, 1e-9)
        // Full white at full backlight lands near the ~80 lx illuminance of evening computer work (Schöllhorn et al., Commun Biol 2023).
        assertTrue(lit.high in 60.0..150.0, "$lit")
        // The dim backlight this laptop ran at in the evening (6%) keeps the middle near 1.5 lx.
        val dim = EveningLight.laptopAtEyes(backlight = 0.06, warmFilter = false, profile = panel)
        assertTrue(dim.mid in 1.0..2.5, "$dim")
        // A night filter lowers it; an unrecorded one widens the band over both.
        val warm = EveningLight.laptopAtEyes(backlight = 0.06, warmFilter = true, profile = panel)
        val unknown = EveningLight.laptopAtEyes(backlight = 0.06, warmFilter = null, profile = panel)
        assertTrue(warm.mid < dim.mid)
        assertTrue(unknown.low <= warm.low && unknown.high >= dim.high)
        // An unrecorded backlight spans the unmeasured share of the range.
        val noBacklight = EveningLight.laptopAtEyes(backlight = null, warmFilter = false, profile = panel)
        assertEquals(EveningLight.laptopAtEyes(unmeasured.brightnessShare.high, false, panel).high, noBacklight.high, 1e-9)
    }

    @Test
    fun `time at a laptop adds its light even with the phone's screen off, and keeps the room lit in the middle estimate`() {
        val start = ist("2026-09-14", "21:00")
        val interval = EveningInterval(start, start + 120 * MINUTE)
        val phone = listOf(ScreenSpan(start, start + 5 * MINUTE))
        val samples = (0 until 10).map { LightReading(start + it * 30_000L, 30_000, 100.0, 1200, darkUi = true, warmFilter = true) }
        val laptop = listOf(LaptopScreen(start + 5 * MINUTE, start + 65 * MINUTE, backlight = 0.5, warmFilter = false, profile = panel))

        val without = EveningLight.minutes(interval, phone, samples, DisplayProfile.OPPO_A18, assumptions, unmeasured)
        val with = EveningLight.minutes(interval, phone, samples, DisplayProfile.OPPO_A18, assumptions, unmeasured, laptop)
        assertEquals(0, without.laptopMinutes)
        assertEquals(60, with.laptopMinutes)
        assertEquals(5, with.screenMinutes)

        val screen = EveningLight.laptopAtEyes(0.5, false, panel)
        val room = LightDose.ambientMelanopicEdi(100.0, evening = true)
        // 50 min in: the phone's carry of the room has run out, the laptop's has not.
        assertEquals(0.0, without.bands[50].mid)
        assertEquals(room.mid + screen.mid, with.bands[50].mid, 1e-9)
        assertEquals(screen.low, with.bands[50].low, 1e-9)
        // 30 min after the laptop: dark again in the middle.
        assertEquals(0.0, with.bands[96].mid)
        // Minutes before the laptop are untouched.
        assertEquals(without.bands[2], with.bands[2])
    }

    private fun ist(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    private companion object {
        const val MINUTE = 60_000L
    }
}
