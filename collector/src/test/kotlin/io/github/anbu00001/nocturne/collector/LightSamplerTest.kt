package io.github.anbu00001.nocturne.collector

import io.github.anbu00001.nocturne.data.LightSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightSamplerTest {

    private val written = ArrayList<LightSampleEntity>()
    private var display = DisplaySnapshot(brightnessSetting = 140, darkUi = true, warmFilter = true)
    private val sampler = LightSampler({ display }, brightnessMax = 4095, write = { written += it })

    @Test
    fun screenOnWritesOneSamplePerWindowWithTheDisplayStateAndScreenOffKeepsTheTail() {
        sampler.screenOn(1_000)
        sampler.onLux(1_200, 12f)
        sampler.tick(31_000)
        sampler.tick(61_000)
        sampler.onLux(70_000, 0f)
        sampler.screenOff(80_000)

        assertEquals(listOf(1_000L, 31_000L, 61_000L), written.map { it.timestamp })
        assertEquals(listOf(30_000L, 30_000L, 19_000L), written.map { it.durationMs })
        // The last window held 12 lux for 9 s and 0 lux for 10 s.
        assertEquals(listOf(12f, 12f, 0f), written.map { it.ambientLux })
        assertTrue(written.all { it.brightnessSetting == 140 && it.darkUi == true && it.warmFilter == true && it.screenOn })
        assertEquals(140f / 4095, written.first().screenBrightness!!, 1e-6f)
        assertFalse(sampler.isSampling)
    }

    @Test
    fun unreadableDisplayStateIsStoredAsUnknownNotGuessed() {
        display = DisplaySnapshot(null, null, null)
        sampler.screenOn(0)
        sampler.tick(30_000)
        val row = written.single()
        assertNull(row.brightnessSetting)
        assertNull(row.screenBrightness)
        assertNull(row.darkUi)
        assertNull(row.warmFilter)
        assertNull(row.ambientLux)
    }

    @Test
    fun aSecondScreenOnWhileSamplingKeepsTheCurrentWindow() {
        sampler.screenOn(0)
        sampler.onLux(0, 5f)
        sampler.screenOn(20_000)
        sampler.tick(30_000)
        assertEquals(0L, written.single().timestamp)
        assertEquals(30_000L, written.single().durationMs)
    }
}
