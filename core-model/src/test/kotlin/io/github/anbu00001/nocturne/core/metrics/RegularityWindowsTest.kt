package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.metrics.MetricResult.Score
import io.github.anbu00001.nocturne.core.metrics.MetricResult.Withheld
import io.github.anbu00001.nocturne.core.sleep.IST
import io.github.anbu00001.nocturne.core.sleep.ist
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RegularityWindowsTest {
    private val first = LocalDate.parse("2026-09-01")

    /** [count] nights asleep 23:00 to 07:00, with the screen on 20:00 to 22:00 each evening. */
    private fun regular(count: Int): Pair<List<NightRecord>, List<LongRange>> {
        val nights = (0 until count).map { i ->
            val date = first.plusDays(i.toLong())
            NightRecord(date, IST, ist(date.toString(), "23:00"), ist(date.plusDays(1).toString(), "07:00"), noSleep = false)
        }
        val screen = (0 until count).map { i ->
            val date = first.plusDays(i.toLong()).toString()
            ist(date, "20:00") until ist(date, "22:00")
        }
        return nights to screen
    }

    private fun List<WindowValue>.of(end: LocalDate, days: Int, metric: MetricKey) =
        single { it.endDate == end && it.windowDays == days && it.metric == metric }.result

    @Test
    fun `a regular fortnight scores regular, and short windows say what is missing`() {
        val (nights, screen) = regular(14)
        val dataFrom = ist("2026-09-01", "12:00")
        val dataTo = ist("2026-09-15", "12:00")
        val last = first.plusDays(13)
        val values = RegularityWindows.compute(nights, screen, dataFrom, dataTo, listOf(first.plusDays(4), last))

        assertEquals(100.0, assertIs<Score>(values.of(last, 7, MetricKey.SRI)).value, 1e-9)
        assertEquals(0.0, assertIs<Score>(values.of(last, 7, MetricKey.ONSET_SD)).value, 1e-9)
        assertEquals(1.0, assertIs<Score>(values.of(last, 14, MetricKey.IS)).value, 1e-9)
        // Any 10 h window holding the 2 h evening block has a mean of 0.2.
        assertEquals(0.2, assertIs<Score>(values.of(last, 7, MetricKey.M10)).value, 1e-9)
        // The 28-night window ending on the 14th night holds only 14 nights: still enough for every metric.
        assertIs<Score>(values.of(last, 28, MetricKey.SRI))
        // Five nights in: nothing to show yet, and each metric says how many nights it has.
        assertEquals(Withheld(WithheldReason.TOO_FEW_NIGHTS, 5, 7), values.of(first.plusDays(4), 7, MetricKey.SRI))
        assertEquals(Withheld(WithheldReason.TOO_FEW_NIGHTS, 5, 7), values.of(first.plusDays(4), 7, MetricKey.IS))
        assertEquals(2 * 3 * MetricKey.entries.size, values.size)
    }

    @Test
    fun `a sleepless night counts as awake and lowers regularity, an unjudged one is unknown`() {
        val (nights, screen) = regular(8)
        val dataFrom = ist("2026-09-01", "12:00")
        val dataTo = ist("2026-09-09", "12:00")
        val last = first.plusDays(7)
        val sleepless = nights.mapIndexed { i, n -> if (i == 3) n.copy(onsetTs = null, wakeTs = null, noSleep = true) else n }
        val sri = assertIs<Score>(RegularityWindows.compute(sleepless, screen, dataFrom, dataTo, listOf(last)).of(last, 7, MetricKey.SRI))
        assertTrue(sri.value < 100.0, "${sri.value}")

        val unjudged = nights.mapIndexed { i, n -> if (i == 3) n.copy(onsetTs = null, wakeTs = null) else n }
        assertEquals(
            Withheld(WithheldReason.TOO_FEW_NIGHTS, 6, 7),
            RegularityWindows.compute(unjudged, screen, dataFrom, dataTo, listOf(last)).of(last, 7, MetricKey.SRI),
        )
    }

    @Test
    fun `values for a window do not depend on nights after it`() {
        val (nights, screen) = regular(14)
        val dataFrom = ist("2026-09-01", "12:00")
        val end = first.plusDays(8)
        val early = RegularityWindows.compute(nights.take(10), screen.take(10), dataFrom, ist("2026-09-11", "12:00"), listOf(end))
        val later = RegularityWindows.compute(nights, screen, dataFrom, ist("2026-09-15", "12:00"), listOf(end))
        assertEquals(early, later)
    }
}
