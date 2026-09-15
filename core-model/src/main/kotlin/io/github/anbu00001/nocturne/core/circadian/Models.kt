package io.github.anbu00001.nocturne.core.circadian

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hannay, Booth and Forger (J Biol Rhythms 2019), single population: the amplitude [R] and phase [PSI] of a population of
 * clock neurons, and [N] the share of light-responsive photoreceptors used up. The model the spec picks because it
 * departs from van der Pol models below 100 lux, where evening phone light sits. Parameters are the paper's fit as the
 * package ships them.
 */
data class Hannay19(
    val tau: Double = 23.84,
    val k: Double = 0.06358,
    val gamma: Double = 0.024,
    val beta1: Double = -0.09318,
    val a1: Double = 0.3855,
    val a2: Double = 0.1977,
    val betaL1: Double = -0.0026,
    val betaL2: Double = -0.957756,
    val sigma: Double = 0.0400692,
    val g: Double = 33.75,
    val alpha0: Double = 0.05,
    val delta: Double = 0.0075,
    val p: Double = 1.5,
    val i0: Double = 9325.0,
    override val cbtToDlmoHours: Double = 7.0,
) : CircadianModel {

    override val defaultInitialState: DoubleArray get() = doubleArrayOf(0.82041911, 1.71383697, 0.52318122)

    override fun derivative(state: DoubleArray, lux: Double, out: DoubleArray) {
        val r = state[R]
        val psi = state[PSI]
        val n = state[N]
        // Written in the package's operation order so rounding matches the oracle.
        val alpha = alpha0 * lux.pow(p) / (lux.pow(p) + i0)
        val bhat = g * (1.0 - n) * alpha
        val a1Amp = a1 * 0.5 * bhat * (1.0 - r.pow(4.0)) * cos(psi + betaL1)
        val a2Amp = a2 * 0.5 * bhat * r * (1.0 - r.pow(8.0)) * cos(2.0 * psi + betaL2)
        val lightAmp = a1Amp + a2Amp
        val a1Phase = a1 * bhat * 0.5 * (r.pow(3.0) + 1.0 / r) * sin(psi + betaL1)
        val a2Phase = a2 * bhat * 0.5 * (1.0 + r.pow(8.0)) * sin(2.0 * psi + betaL2)
        val lightPhase = sigma * bhat - a1Phase - a2Phase
        out[R] = -1.0 * gamma * r + k * cos(beta1) / 2.0 * r * (1.0 - r.pow(4.0)) + lightAmp
        out[PSI] = 2 * PI / tau + k / 2.0 * sin(beta1) * (1 + r.pow(4.0)) + lightPhase
        out[N] = 60.0 * (alpha * (1.0 - n) - delta * n)
    }

    /** CBT minimum where -cos(Psi) peaks, that is where the phase passes π. */
    override fun cbtSignal(state: DoubleArray): Double = -cos(state[PSI])

    fun amplitude(state: DoubleArray): Double = state[R]

    /** Phase in (-π, π]. */
    fun phase(state: DoubleArray): Double = atan2(sin(state[PSI]), cos(state[PSI]))

    companion object {
        const val R = 0
        const val PSI = 1
        const val N = 2
    }
}

/**
 * Forger, Jewett and Kronauer (J Biol Rhythms 1999), "a simpler model of the human circadian pacemaker": a van der Pol
 * oscillator ([X], [XC]) driven through photoreceptor state [N]. Kept as the comparison model (spec §6.4).
 */
data class Forger99(
    val taux: Double = 24.2,
    val mu: Double = 0.23,
    val g: Double = 33.75,
    val alpha0: Double = 0.05,
    val beta: Double = 0.0075,
    val p: Double = 0.50,
    val i0: Double = 9500.0,
    val k: Double = 0.55,
    override val cbtToDlmoHours: Double = 7.0,
) : CircadianModel {

    override val defaultInitialState: DoubleArray get() = doubleArrayOf(-0.0843259, -1.09607546, 0.45584306)

    override fun derivative(state: DoubleArray, lux: Double, out: DoubleArray) {
        val x = state[X]
        val xc = state[XC]
        val n = state[N]
        val alpha = alpha0 * (lux / i0).pow(p)
        val bhat = g * (1.0 - n) * alpha * (1 - 0.4 * x) * (1 - 0.4 * xc)
        val muTerm = mu * (xc - 4.0 / 3.0 * xc.pow(3.0))
        val tauxTerm = (24.0 / (0.99669 * taux)).pow(2.0) + k * bhat
        out[X] = PI / 12.0 * (xc + bhat)
        out[XC] = PI / 12.0 * (muTerm - x * tauxTerm)
        out[N] = 60.0 * (alpha * (1.0 - n) - beta * n)
    }

    /** CBT minimum where -x peaks. */
    override fun cbtSignal(state: DoubleArray): Double = -state[X]

    fun amplitude(state: DoubleArray): Double = sqrt(state[X] * state[X] + state[XC] * state[XC])

    fun phase(state: DoubleArray): Double = atan2(-state[XC], state[X])

    companion object {
        const val X = 0
        const val XC = 1
        const val N = 2
    }
}
