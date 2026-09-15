package io.github.anbu00001.nocturne.core.metrics

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * IS, IV, L5, M10 and RA against nparACT 0.9.1 on synthetic gapless weeks that start at noon, as the analytics spec asks
 * (§5). The weeks and nparACT's results come from tools/actigraphy/nparact_golden.R. nparACT rounds to 2 decimals and
 * derives RA from its rounded L5 and M10, so each value is compared the way nparACT reports it.
 */
class NparactGoldenTest {

    @Test
    fun `a rhythmic week matches nparACT`() = check("rhythm")

    @Test
    fun `a week of noise matches nparACT`() = check("noise")

    private fun check(name: String) {
        val days = week(name)
        val want = resource("$name-week-nparact.txt").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        fun number(key: String) = want.getValue(key).toDouble()

        assertEquals(number("IS"), round2(assertIs<MetricResult.Score>(InterdailyStability.compute(days)).value), "IS")
        assertEquals(number("IV"), round2(assertIs<MetricResult.Score>(IntradailyVariability.compute(days)).value), "IV")
        val l5 = assertIs<MetricResult.Score>(LeastActive5.compute(days))
        val m10 = assertIs<MetricResult.Score>(MostActive10.compute(days))
        assertEquals(number("L5"), round2(l5.value), "L5")
        assertEquals(number("M10"), round2(m10.value), "M10")
        assertEquals(want.getValue("L5_starttime"), clock(l5.atMinute!!), "L5 start")
        assertEquals(want.getValue("M10_starttime"), clock(m10.atMinute!!), "M10 start")
        val roundedL5 = round2(l5.value)
        val roundedM10 = round2(m10.value)
        assertEquals(number("RA"), round2((roundedM10 - roundedL5) / (roundedM10 + roundedL5)), "RA")
    }

    private fun week(name: String): List<ActivityDay> {
        val values = resource("$name-week.txt").lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.toDouble() }
        require(values.size == 7 * SleepDay.EPOCHS) { "$name has ${values.size} minutes" }
        val start = LocalDate.parse("2026-09-07")
        return (0 until 7).map { d ->
            ActivityDay(start.plusDays(d.toLong()), DoubleArray(SleepDay.EPOCHS) { m -> values[d * SleepDay.EPOCHS + m] })
        }
    }

    private fun resource(path: String): String = checkNotNull(javaClass.getResource("/nparact/$path")) { "missing /nparact/$path" }.readText()

    /** R's round(x, 2) since R 4.0: the 2-decimal value nearest the double's exact value, half-even only on exact ties. */
    private fun round2(x: Double): Double = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()

    private fun clock(minute: Int) = "%02d:%02d:00".format(minute / 60, minute % 60)
}
