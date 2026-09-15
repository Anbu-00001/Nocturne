package io.github.anbu00001.nocturne.core.metrics

import io.github.anbu00001.nocturne.core.metrics.MetricResult.Score
import io.github.anbu00001.nocturne.core.metrics.MetricResult.Withheld
import io.github.anbu00001.nocturne.core.sleep.IST
import io.github.anbu00001.nocturne.core.sleep.ist
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyCheckTest {
    private val first = LocalDate.parse("2026-09-01")
    private val dataFrom = ist("2026-09-01", "12:00")
    private val dataTo = ist("2026-09-09", "12:00")
    private val last = first.plusDays(7)

    /** Eight nights asleep 23:00 to 07:00. */
    private val nights = (0 until 8).map { i ->
        val date = first.plusDays(i.toLong())
        NightRecord(date, IST, ist(date.toString(), "23:00"), ist(date.plusDays(1).toString(), "07:00"), noSleep = false)
    }

    /** A screen span of [minutes] starting at [clock] on every calendar day the history touches. */
    private fun daily(clock: String, minutes: Int): List<LongRange> = (0..8).map { i ->
        val start = ist(first.plusDays(i.toLong()).toString(), clock)
        start until start + minutes * 60_000L
    }

    private fun hourly(hours: List<Int>) = hours.flatMap { h -> daily("%02d:00".format(h), 10) }

    private fun List<WindowValue>.of(metric: MetricKey) = single { it.endDate == last && it.windowDays == 7 && it.metric == metric }.result

    @Test
    fun `quiet screen hours inside the night follow sleep`() {
        // Ten minutes at the top of every hour from 07:00 to 22:00, so the screen is quiet from 22:10 to 07:00.
        val values = RegularityWindows.compute(nights, hourly((7..22).toList()), dataFrom, dataTo, listOf(last), windows = listOf(7))

        // Every 5 h window starting 22:10 to 02:00 is silent: L5 takes the middle of that plateau, 00:05.
        assertEquals(5, assertIs<Score>(values.of(MetricKey.L5)).atMinute)
        val asleep = assertIs<Score>(values.of(MetricKey.L5_ASLEEP))
        assertEquals(1.0, asleep.value, 1e-9)
        assertTrue(ProxyCheck.followsSleep(asleep.value))
        assertEquals(1.0, assertIs<Score>(values.of(MetricKey.IS_SLEEP)).value, 1e-9)
    }

    @Test
    fun `quiet screen hours spent awake are flagged`() {
        // In use through the day except while the phone sits in a locker 12:10 to 19:00, and a notification lights the
        // screen for a minute in every night hour.
        val day = hourly(listOf(7, 8, 9, 10, 11, 12, 19, 20, 21, 22))
        val night = listOf("23:30", "00:30", "01:30", "02:30", "03:30", "04:30", "05:30", "06:30").flatMap { daily(it, 1) }
        val values = RegularityWindows.compute(nights, day + night, dataFrom, dataTo, listOf(last), windows = listOf(7))

        assertEquals(13 * 60 + 5, assertIs<Score>(values.of(MetricKey.L5)).atMinute)
        val asleep = assertIs<Score>(values.of(MetricKey.L5_ASLEEP))
        assertEquals(0.0, asleep.value, 1e-9)
        assertFalse(ProxyCheck.followsSleep(asleep.value))
    }

    @Test
    fun `the check waits for nights with a verdict even when screen use is known`() {
        val unjudged = nights.mapIndexed { i, n -> if (i == 4) n.copy(onsetTs = null, wakeTs = null) else n }
        val values = RegularityWindows.compute(unjudged, hourly((7..22).toList()), dataFrom, dataTo, listOf(last), windows = listOf(7))

        assertIs<Score>(values.of(MetricKey.L5))
        assertEquals(Withheld(WithheldReason.TOO_FEW_NIGHTS, 6, 7), values.of(MetricKey.L5_ASLEEP))
        assertEquals(Withheld(WithheldReason.TOO_FEW_NIGHTS, 6, 7), values.of(MetricKey.IS_SLEEP))
    }

    @Test
    fun `ties go to the middle of the longest run, including one across the end of the day`() {
        val wrapping = BooleanArray(SleepDay.EPOCHS)
        for (i in 100..109) wrapping[i] = true
        for (i in (1430..1439) + (0..9)) wrapping[i] = true
        assertEquals(1439, RestActivity.middleOfLongestRun(wrapping))

        val equal = BooleanArray(SleepDay.EPOCHS)
        for (i in (200..203) + (600..603)) equal[i] = true
        assertEquals(201, RestActivity.middleOfLongestRun(equal))

        assertEquals(0, RestActivity.middleOfLongestRun(BooleanArray(SleepDay.EPOCHS) { true }))
    }
}
