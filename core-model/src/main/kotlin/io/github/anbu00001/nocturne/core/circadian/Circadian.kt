package io.github.anbu00001.nocturne.core.circadian

import kotlin.math.ceil

/**
 * A light-driven circadian pacemaker model with three states (spec §6.4). Implementations follow Arcascope's `circadian`
 * package, version 1.0.3, the test oracle: same equations, same parameters, same arithmetic order, so golden
 * trajectories from `tools/circadian/hannay19_golden.py` match to rounding.
 */
interface CircadianModel {
    /** The package's default: the state at midnight after entrainment to 16 h of light and 8 h of dark. */
    val defaultInitialState: DoubleArray

    /** Hours from the core body temperature minimum back to dim light melatonin onset. */
    val cbtToDlmoHours: Double

    /** The derivative of [state] under [lux] photopic lux, written into [out]. */
    fun derivative(state: DoubleArray, lux: Double, out: DoubleArray)

    /** The value whose peaks mark the core body temperature minimum. */
    fun cbtSignal(state: DoubleArray): Double
}

/** States at each time point. [hours] must be on a fixed step for markers to mean what they say. */
class Trajectory(val hours: DoubleArray, val states: Array<DoubleArray>) {
    init {
        require(hours.size == states.size)
    }

    /**
     * Core body temperature minima, as the package finds them: peaks of the model's CBT signal no closer than 13 h,
     * counted in steps of the first step's length and rounded up.
     */
    fun cbtMinimaHours(model: CircadianModel): DoubleArray {
        if (hours.size < 3) return DoubleArray(0)
        val distance = ceil(MIN_MARKER_DISTANCE_HOURS / (hours[1] - hours[0])).toInt()
        val signal = DoubleArray(states.size) { model.cbtSignal(states[it]) }
        return Peaks.find(signal, distance).map { hours[it] }.toDoubleArray()
    }

    /** DLMO = CBTmin - 7 h, for every CBT minimum. Published accuracy is about ±1 h against lab DLMO. */
    fun dlmoHours(model: CircadianModel): DoubleArray = cbtMinimaHours(model).map { it - model.cbtToDlmoHours }.toDoubleArray()

    private companion object {
        const val MIN_MARKER_DISTANCE_HOURS = 13.0
    }
}

object Circadian {

    /**
     * Fixed-step fourth-order Runge-Kutta, as `CircadianModel.integrate` in circadian 1.0.3: the step from hours[i-1] to
     * hours[i] holds the light at hours[i], the end of the step, constant. The first state is [initial].
     */
    fun integrate(model: CircadianModel, hours: DoubleArray, lux: DoubleArray, initial: DoubleArray = model.defaultInitialState): Trajectory {
        require(hours.size == lux.size) { "${hours.size} times but ${lux.size} light values" }
        require(lux.all { it >= 0.0 && it.isFinite() }) { "light must be finite and non-negative" }
        val n = initial.size
        val states = Array(hours.size) { DoubleArray(n) }
        if (hours.isEmpty()) return Trajectory(hours, states)
        initial.copyInto(states[0])
        val k1 = DoubleArray(n)
        val k2 = DoubleArray(n)
        val k3 = DoubleArray(n)
        val k4 = DoubleArray(n)
        val probe = DoubleArray(n)
        for (i in 1 until hours.size) {
            val state = states[i - 1]
            val dt = hours[i] - hours[i - 1]
            val light = lux[i]
            model.derivative(state, light, k1)
            for (j in 0 until n) probe[j] = state[j] + k1[j] * dt / 2.0
            model.derivative(probe, light, k2)
            for (j in 0 until n) probe[j] = state[j] + k2[j] * dt / 2.0
            model.derivative(probe, light, k3)
            for (j in 0 until n) probe[j] = state[j] + k3[j] * dt
            model.derivative(probe, light, k4)
            val next = states[i]
            for (j in 0 until n) next[j] = state[j] + (dt / 6.0) * (k1[j] + 2.0 * k2[j] + 2.0 * k3[j] + k4[j])
        }
        return Trajectory(hours, states)
    }
}

/** `scipy.signal.find_peaks(x, distance=d)` as SciPy 1.18 computes it, which the package uses for CBT minima. */
internal object Peaks {

    fun find(x: DoubleArray, distance: Int = 1): List<Int> {
        val peaks = ArrayList<Int>()
        // A peak has a smaller sample on both sides; a flat top counts once, at its middle (rounded down). The first and
        // last samples are never peaks.
        var i = 1
        val last = x.size - 1
        while (i < last) {
            if (x[i - 1] < x[i]) {
                var ahead = i + 1
                while (ahead < last && x[ahead] == x[i]) ahead++
                if (x[ahead] < x[i]) {
                    peaks += (i + ahead - 1) / 2
                    i = ahead
                }
            }
            i++
        }
        if (distance <= 1 || peaks.size < 2) return peaks
        // Highest first, each kept peak removes every other peak fewer than [distance] samples away. Equal heights go
        // to the later peak first, as numpy's argsort orders a short array.
        val keep = BooleanArray(peaks.size) { true }
        val byHeight = peaks.indices.sortedBy { x[peaks[it]] }
        for (r in byHeight.indices.reversed()) {
            val j = byHeight[r]
            if (!keep[j]) continue
            var k = j - 1
            while (k >= 0 && peaks[j] - peaks[k] < distance) keep[k--] = false
            k = j + 1
            while (k < peaks.size && peaks[k] - peaks[j] < distance) keep[k++] = false
        }
        return peaks.filterIndexed { index, _ -> keep[index] }
    }
}
