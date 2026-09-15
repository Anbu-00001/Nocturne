package io.github.anbu00001.nocturne.core.circadian

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §6.4 and §10, blocking for Phase 3: the Kotlin ports against Arcascope `circadian` 1.0.3 on the same light input.
 * The spec asks for 1e-4 per state variable; the ports copy the package's arithmetic, so this holds them to 1e-9.
 * Golden sets come from `tools/circadian/hannay19_golden.py` (pinned environment in requirements.txt):
 * - office: 07:00 to 23:00 at 300 lux with an hour of 5000 lux at noon, a week at 6-minute steps
 * - late: asleep 04:00 to 12:00, 200 lux until 18:00, a 30 lux room until 02:00, a phone in the dark at 5 lux, a week
 * - late-minute: the late schedule for 2 days at 1-minute steps, the resolution the app will integrate at
 * The real week of phone light the spec also asks for is added once a full week of light samples exists.
 */
class CircadianGoldenTest {

    @Test
    fun `Hannay19 matches circadian on an office week`() = check(Hannay19(), light = "office", states = "office")

    @Test
    fun `Hannay19 matches circadian on a late sleeper's dim week`() = check(Hannay19(), light = "late", states = "late")

    @Test
    fun `Hannay19 matches circadian at 1-minute steps`() = check(Hannay19(), light = "late-minute", states = "late-minute")

    @Test
    fun `Forger99 matches circadian on an office week`() = check(Forger99(), light = "office", states = "office-forger99")

    @Test
    fun `Forger99 matches circadian on a late sleeper's dim week`() = check(Forger99(), light = "late", states = "late-forger99")

    @Test
    fun `Forger99 matches circadian at 1-minute steps`() = check(Forger99(), light = "late-minute", states = "late-minute-forger99")

    private fun check(model: CircadianModel, light: String, states: String) {
        val input = rows("$light-light.csv")
        val hours = DoubleArray(input.size) { input[it][0] }
        val lux = DoubleArray(input.size) { input[it][1] }
        val trajectory = Circadian.integrate(model, hours, lux)
        val index = hours.withIndex().associate { (i, h) -> h to i }

        val expected = rows("$states.csv")
        assertTrue(expected.size > 20, "$states has ${expected.size} rows")
        for (row in expected) {
            val i = index.getValue(row[0])
            for (s in 0 until 3) assertEquals(row[1 + s], trajectory.states[i][s], 1e-9, "$states state $s at ${row[0]} h")
        }
        val dlmos = rows("$states.dlmo.csv").map { it[0] }.toDoubleArray()
        assertContentEquals(dlmos, trajectory.dlmoHours(model), "$states DLMO hours")
    }

    private fun rows(name: String): List<DoubleArray> {
        val text = checkNotNull(javaClass.getResource("/circadian/$name")) { "missing /circadian/$name" }.readText()
        return text.lines().drop(1).filter { it.isNotBlank() }.map { line -> line.split(',').map(String::toDouble).toDoubleArray() }
    }
}
