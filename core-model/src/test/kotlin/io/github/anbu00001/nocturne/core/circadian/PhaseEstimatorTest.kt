package io.github.anbu00001.nocturne.core.circadian

import io.github.anbu00001.nocturne.core.light.LightReading
import io.github.anbu00001.nocturne.core.sleep.ist
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.core.time.ZoneChange
import io.github.anbu00001.nocturne.core.time.ZoneTimeline
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseEstimatorTest {
    private val zones = ZoneTimeline(listOf(ZoneChange(0, "Asia/Kolkata")))
    private val first = LocalDate.parse("2026-09-01")

    private fun day(i: Int) = first.plusDays(i.toLong()).toString()

    /** Asleep from [onset] for [hours] hours, starting on each of [days] days. */
    private fun nights(days: Int, onset: String = "00:00", hours: Int = 8) =
        (0 until days).map { ist(day(it), onset).let { start -> start until start + hours * LocalClock.HOUR_MS } }

    /** A 30 s light sample of [lux] every 30 s from [from] to [to] on each of [days] days (to before midnight). */
    private fun readings(days: Int, from: String, to: String, lux: Double) = (0 until days).flatMap { d ->
        (ist(day(d), from) until ist(day(d), to) step 30_000L).map { LightReading(it, 30_000, lux, null, null, null) }
    }

    private fun minuteOf(date: String, clock: String, start: Long) = ((ist(date, clock) - start) / LocalClock.MINUTE_MS).toInt()

    private fun clockOf(ts: Long) = Math.floorMod((ts / LocalClock.MINUTE_MS + 330).toInt(), LocalClock.MINUTES_PER_DAY)

    @Test
    fun `sleep is dark, readings reach the eyes, and waking hours without one use the prior`() {
        val start = ist(day(0), "00:00")
        val sleep = nights(1)
        val light = listOf(LightReading(ist(day(0), "20:00"), 60_000, 100.0, null, null, null))
        val middle = CircadianLight.build(start, 24 * 60, zones, sleep, light, LightScenario.MIDDLE)
        assertEquals(0.0, middle[minuteOf(day(0), "03:00", start)])
        assertEquals(70.0, middle[minuteOf(day(0), "20:00", start)], 1e-9) // 100 lux at the sensor, 0.7 of it at the eyes
        assertEquals(70.0, middle[minuteOf(day(0), "20:30", start)], 1e-9) // the room is assumed unchanged for 30 min
        assertEquals(30.0, middle[minuteOf(day(0), "20:32", start)], 1e-9) // then the evening prior's middle
        assertEquals(200.0, middle[minuteOf(day(0), "10:00", start)], 1e-9) // daylight hours with nothing measured

        val dimDay = CircadianLight.build(start, 24 * 60, zones, sleep, light, LightScenario.DIM_DAY_BRIGHT_EVENING)
        assertEquals(120.0, dimDay[minuteOf(day(0), "20:00", start)], 1e-9)
        assertEquals(50.0, dimDay[minuteOf(day(0), "10:00", start)], 1e-9)
        assertEquals(150.0, dimDay[minuteOf(day(0), "22:00", start)], 1e-9)
    }

    @Test
    fun `unmeasured hours take the prior's middle, or in one scenario the median of your readings`() {
        val start = ist(day(0), "00:00")
        val light = readings(1, "19:00", "20:00", 10.0)
        val middle = CircadianLight.build(start, 24 * 60, zones, nights(1), light, LightScenario.MIDDLE)
        assertEquals(7.0, middle[minuteOf(day(0), "19:30", start)], 1e-9)
        assertEquals(30.0, middle[minuteOf(day(0), "22:00", start)], 1e-9)
        val own = CircadianLight.build(start, 24 * 60, zones, nights(1), light, LightScenario.OWN_READINGS)
        assertEquals(7.0, own[minuteOf(day(0), "22:00", start)], 1e-9) // an hour of 10 lux readings after dark
        assertEquals(200.0, own[minuteOf(day(0), "10:00", start)], 1e-9) // none in daylight hours yet
        // Half an hour of readings is not enough to stand for the evening.
        val short = CircadianLight.build(start, 24 * 60, zones, nights(1), readings(1, "19:00", "19:30", 10.0), LightScenario.OWN_READINGS)
        assertEquals(30.0, short[minuteOf(day(0), "22:00", start)], 1e-9)
    }

    @Test
    fun `habitual sleep is the median onset and length, across midnight`() {
        val start = ist(day(0), "00:00")
        val sleep = listOf(
            ist(day(0), "23:00") until ist(day(1), "07:00"),
            ist(day(1), "01:00") until ist(day(1), "08:00"),
            ist(day(2), "02:00") until ist(day(2), "11:00"),
        )
        assertEquals(PhaseEstimator.HabitualSleep(60, 8 * 60), PhaseEstimator.habitualSleep(sleep, start, ist(day(7), "00:00"), zones))
        assertEquals(PhaseEstimator.HabitualSleep(0, 8 * 60), PhaseEstimator.habitualSleep(emptyList(), start, ist(day(7), "00:00"), zones))
    }

    @Test
    fun `a scenario whose clock does not fit the sleep is set aside, and agreement settles the night`() {
        val onset = ist(day(8), "03:00")
        fun at(clock: String, date: Int = 8) = ist(day(date), clock)
        val fits = NightPhase(
            first.plusDays(7),
            mapOf(LightScenario.OWN_READINGS to at("01:00"), LightScenario.DIM_DAY_BRIGHT_EVENING to at("00:30"), LightScenario.MIDDLE to at("19:00", 7)),
            onset,
        )
        assertEquals(8.0, fits.phaseAngleHours(LightScenario.MIDDLE)!!, 1e-9)
        assertEquals(setOf(LightScenario.OWN_READINGS, LightScenario.DIM_DAY_BRIGHT_EVENING), fits.plausible.keys)
        assertTrue(fits.settled)
        assertEquals(at("00:45"), fits.dlmoTs)

        val apart = fits.copy(dlmoByScenario = mapOf(LightScenario.OWN_READINGS to at("01:00"), LightScenario.DIM_DAY_BRIGHT_EVENING to at("22:30", 7)))
        assertFalse(apart.settled) // both plausible, but 2 h 30 min apart
        assertNull(apart.dlmoTs)
        val alone = fits.copy(dlmoByScenario = mapOf(LightScenario.OWN_READINGS to at("01:00"), LightScenario.MIDDLE to at("19:00", 7)))
        assertFalse(alone.settled) // one plausible scenario is not agreement
    }

    @Test
    fun `a bright evening every day puts melatonin onset later`() {
        val from = ist(day(0), "00:00")
        val to = ist(day(14), "00:00")
        val sleep = nights(15)
        val plain = PhaseEstimator.estimate(from, to, zones, sleep, emptyList())
        val bright = PhaseEstimator.estimate(from, to, zones, sleep, readings(14, "20:00", "23:59", 400.0))
        // A night counts once its CBT minimum, 7 h after DLMO, falls inside the history, so take one both runs reached.
        val night = plain.filter { LightScenario.MIDDLE in it.dlmoByScenario }.map { it.date }
            .intersect(bright.filter { LightScenario.MIDDLE in it.dlmoByScenario }.map { it.date }.toSet()).max()
        val plainMiddle = plain.single { it.date == night }.dlmoByScenario.getValue(LightScenario.MIDDLE)
        val brightMiddle = bright.single { it.date == night }.dlmoByScenario.getValue(LightScenario.MIDDLE)
        assertTrue(brightMiddle > plainMiddle + 20 * LocalClock.MINUTE_MS, "${clockOf(plainMiddle)} vs ${clockOf(brightMiddle)}")
        // Asleep midnight to 08:00: onset should come in the evening before, and fit that sleep.
        assertTrue(clockOf(plainMiddle) in 18 * 60 until 24 * 60, "DLMO at minute ${clockOf(plainMiddle)}")
        assertTrue(LightScenario.MIDDLE in plain.single { it.date == night }.plausible)
        assertEquals(ist(day(8), "00:00"), plain.single { it.date == first.plusDays(7) }.habitualOnsetTs)
    }

    /**
     * Five hours later to bed gives a later onset, but by less than five hours: both sleepers get the same daylight
     * hours, and light, not sleep, sets the model's clock (the late sleeper only misses the morning light). On the first
     * run it came out 2 h later.
     */
    @Test
    fun `a late sleeper's melatonin onset is later than an early sleeper's`() {
        val from = ist(day(0), "00:00")
        val to = ist(day(14), "00:00")
        val early = PhaseEstimator.estimate(from, to, zones, nights(15, onset = "23:00"), emptyList())
        val late = PhaseEstimator.estimate(from, to, zones, nights(15, onset = "04:00"), emptyList())
        fun middle(phases: List<NightPhase>) = phases.mapNotNull { p -> p.dlmoByScenario[LightScenario.MIDDLE]?.let { p.date to it } }.toMap()
        val night = middle(early).keys.intersect(middle(late).keys).max()
        val gap = middle(late).getValue(night) - middle(early).getValue(night)
        assertTrue(gap in LocalClock.HOUR_MS..5 * LocalClock.HOUR_MS, "gap ${gap / LocalClock.MINUTE_MS} min")
    }

    @Test
    fun `nothing is reported before a week of light`() {
        val from = ist(day(0), "00:00")
        assertEquals(emptyList(), PhaseEstimator.estimate(from, ist(day(7), "00:00"), zones, nights(8), emptyList()))
        assertTrue(PhaseEstimator.estimate(from, ist(day(9), "00:00"), zones, nights(10), emptyList()).all { it.date >= first.plusDays(6) })
    }
}
