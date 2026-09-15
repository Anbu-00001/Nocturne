package io.github.anbu00001.nocturne.core.light

import io.github.anbu00001.nocturne.core.time.EveningWindow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EveningLightTest {
    private val a18 = DisplayProfile.OPPO_A18
    private val assumptions = LightAssumptions()
    private val unmeasured = UnmeasuredLight()

    @Test
    fun `the light interval runs from the evening window's start to sleep onset, across midnight`() {
        val date = LocalDate.parse("2026-09-14")
        val window = EveningWindow(21 * 60, 7 * 60)
        assertEquals(
            EveningInterval(ist("2026-09-14", "21:00"), ist("2026-09-15", "01:30")),
            EveningLight.interval(date, IST, window, onsetTs = ist("2026-09-15", "01:30"), dataToTs = Long.MAX_VALUE),
        )
        assertEquals(ist("2026-09-15", "07:00"), EveningLight.interval(date, IST, window, null, Long.MAX_VALUE)?.endTs)
        assertEquals(ist("2026-09-14", "23:10"), EveningLight.interval(date, IST, window, null, ist("2026-09-14", "23:10"))?.endTs)
        // A late sleeper's window starts after midnight and still belongs to the same night.
        assertEquals(ist("2026-09-15", "00:09"), EveningLight.interval(date, IST, EveningWindow(9, 12 * 60 + 23), null, Long.MAX_VALUE)?.startTs)
        assertNull(EveningLight.interval(date, IST, window, onsetTs = ist("2026-09-14", "20:30"), dataToTs = Long.MAX_VALUE))
    }

    @Test
    fun `a steadily measured unbroken evening reproduces the paper's value for that block`() {
        val interval = EveningInterval(ist("2026-09-14", "21:00"), ist("2026-09-14", "23:00"))
        val samples = generateSequence(interval.startTs) { it + 30_000 }.takeWhile { it < interval.endTs }
            .map { LightReading(it, 30_000, lux = 60.0, brightnessSetting = 1200, darkUi = false, warmFilter = false) }
            .toList()
        val estimate = EveningLight.estimate(interval, listOf(ScreenSpan(interval.startTs, interval.endTs)), samples, a18)
        val perMinute = EveningLight.atEyes(60.0, 1200, darkUi = false, warmFilter = false, profile = a18)
        assertEquals(MelatoninSuppression.percent(perMinute.mid, 120.0), estimate.suppression.percent.mid, 1e-9)
        assertEquals(120, estimate.screenMinutes)
        assertEquals(120, estimate.measuredMinutes)
    }

    @Test
    fun `a display mode the phone would not report widens the band over both modes`() {
        val unknown = EveningLight.atEyes(null, 2000, darkUi = null, warmFilter = null, profile = a18)
        for (dark in listOf(true, false)) {
            for (warm in listOf(true, false)) {
                val known = EveningLight.atEyes(null, 2000, dark, warm, a18)
                assertTrue(unknown.low <= known.low + 1e-12 && unknown.high >= known.high - 1e-12, "$unknown against $known")
            }
        }
    }

    @Test
    fun `with the screen off the low end assumes dark, the middle keeps the room for 30 min, the high end throughout`() {
        val start = ist("2026-09-14", "21:00")
        val samples = (0 until 20).map { LightReading(start + it * 30_000L, 30_000, 100.0, 1200, darkUi = true, warmFilter = true) }
        val minutes = EveningLight.minutes(
            EveningInterval(start, start + 90 * MINUTE),
            listOf(ScreenSpan(start, start + 10 * MINUTE)),
            samples,
            a18,
            assumptions,
            unmeasured,
        )
        val room = LightDose.ambientMelanopicEdi(100.0, evening = true)
        val shortlyAfter = minutes.bands[20]
        assertEquals(0.0, shortlyAfter.low)
        assertEquals(room.mid, shortlyAfter.mid, 1e-9)
        assertEquals(room.high, shortlyAfter.high, 1e-9)
        val muchLater = minutes.bands[60]
        assertEquals(0.0, muchLater.mid)
        assertEquals(room.high, muchLater.high, 1e-9)
        assertEquals(10, minutes.screenMinutes)
        assertEquals(10, minutes.measuredMinutes)
    }

    @Test
    fun `screen time without a sample counts as unmeasured and keeps a wide band`() {
        val start = ist("2026-09-14", "22:00")
        val minutes = EveningLight.minutes(
            EveningInterval(start, start + 5 * MINUTE),
            listOf(ScreenSpan(start, start + 5 * MINUTE)),
            emptyList(),
            a18,
            assumptions,
            unmeasured,
        )
        assertEquals(5, minutes.screenMinutes)
        assertEquals(0, minutes.measuredMinutes)
        val band = minutes.bands.first()
        assertTrue(band.high / band.low > 20, "$band")
    }

    @Test
    fun `room light that only the high end assumes stayed on still raises the high bound`() {
        val start = ist("2026-09-14", "21:00")
        val samples = (0 until 20).map { LightReading(start + it * 30_000L, 30_000, 100.0, 1200, darkUi = true, warmFilter = true) }
        val estimate = EveningLight.estimate(
            EveningInterval(start, start + 190 * MINUTE),
            listOf(ScreenSpan(start, start + 10 * MINUTE)),
            samples,
            a18,
        )
        val percent = estimate.suppression.percent
        assertTrue(percent.high > percent.mid + 1, "$percent")
        assertTrue(percent.low <= percent.mid, "$percent")
    }

    private fun ist(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    private companion object {
        const val IST = 330
        const val MINUTE = 60_000L
    }
}
