package io.github.anbu00001.nocturne.core.detect

import io.github.anbu00001.nocturne.core.sleep.lnGamma
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A Normal-Inverse-Gamma prior over a Gaussian's unknown mean and variance, the conjugate prior Adams and MacKay use.
 * Its predictive for the next value is a Student-t with 2α degrees of freedom, location μ and scale √(β(κ+1)/(ακ)).
 */
data class NormalGammaPrior(val mu: Double, val kappa: Double, val alpha: Double, val beta: Double) {
    init {
        require(kappa > 0 && alpha > 0 && beta > 0) { "κ, α and β must be positive" }
    }

    companion object {
        /**
         * Centred on [centre] with a predictive spread of [scale] before any value is seen: κ = 1 and α = 1 (a Student-t
         * with 2 degrees of freedom, so an unusual value is unlikely, not impossible) and β = scale² / 2.
         */
        fun weak(centre: Double, scale: Double): NormalGammaPrior {
            require(scale > 0)
            return NormalGammaPrior(centre, 1.0, 1.0, scale * scale / 2)
        }
    }
}

/**
 * The run-length posterior after each value: [runLengths] (t) holds P(the last r values form the current run | the first t
 * values) for r = 0..t. r = t means no change since the first value.
 */
class BocpdResult internal constructor(private val columns: List<DoubleArray>) {
    /** Values seen. */
    val size: Int get() = columns.size - 1

    fun runLengths(afterValues: Int): DoubleArray = columns[afterValues].copyOf()

    /**
     * The chance that the current run began within the last [window] values but at least [minSince] values ago, after
     * the first [afterValues] values: the mass on run lengths [minSince]..[window]. [startIndex] is the most likely start
     * in that range, as an index into the values, and [valuesSince] how many values have come since. Null when the history
     * is too short to hold such a run.
     */
    fun recentChange(window: Int, minSince: Int, afterValues: Int = size): ChangeSignal? {
        require(minSince >= 1 && window >= minSince)
        val p = columns[afterValues]
        // A run as long as the history is no change at all.
        val longest = minOf(window, afterValues - 1)
        if (longest < minSince) return null
        var mass = 0.0
        var best = minSince
        for (r in minSince..longest) {
            mass += p[r]
            if (p[r] > p[best]) best = r
        }
        return ChangeSignal(mass.coerceIn(0.0, 1.0), afterValues - best, best)
    }
}

data class ChangeSignal(val probability: Double, val startIndex: Int, val valuesSince: Int)

/**
 * Bayesian online changepoint detection (Adams and MacKay 2007, arXiv:0710.3742), the analytics spec's Tier 2 sentinel,
 * with a constant hazard and a Gaussian of unknown mean and variance. Ported from `bayesian_changepoint_detection` 0.2.dev1
 * (`online_changepoint_detection` with `StudentT` and `constant_hazard`), step for step, and checked against its output
 * (BocpdGoldenTest). The recursion runs in log space, so a value far out in every run's tail cannot underflow the whole
 * column to zero; the reference's column normalisation is the same operation.
 *
 * No pruning: a nightly series holds a few thousand values over years, and the exact recursion over n values costs n²/2
 * steps, a few milliseconds. A missing night is simply not a value, so the hazard is per value rather than per calendar night.
 */
object Bocpd {

    /** The analytics spec's hazard: one expected change per 90 nights, a quarter. */
    const val DEFAULT_HAZARD = 1.0 / 90

    fun run(values: DoubleArray, prior: NormalGammaPrior, hazard: Double = DEFAULT_HAZARD): BocpdResult {
        require(hazard > 0 && hazard < 1)
        require(values.all { it.isFinite() }) { "values must be finite" }
        val logHazard = ln(hazard)
        val logSurvive = ln(1 - hazard)
        val columns = ArrayList<DoubleArray>(values.size + 1)
        columns += doubleArrayOf(1.0)

        var logR = doubleArrayOf(0.0)
        var mu = doubleArrayOf(prior.mu)
        var kappa = doubleArrayOf(prior.kappa)
        var alpha = doubleArrayOf(prior.alpha)
        var beta = doubleArrayOf(prior.beta)

        for (x in values) {
            val n = logR.size
            val joint = DoubleArray(n) { i ->
                val scale = sqrt(beta[i] * (kappa[i] + 1) / (alpha[i] * kappa[i]))
                logR[i] + studentTLogPdf(x, 2 * alpha[i], mu[i], scale)
            }
            val next = DoubleArray(n + 1)
            for (i in 0 until n) next[i + 1] = joint[i] + logSurvive
            next[0] = logSumExp(joint) + logHazard
            val norm = logSumExp(next)
            for (i in next.indices) next[i] -= norm
            logR = next
            columns += DoubleArray(next.size) { exp(next[it]) }

            // Every run's parameters take the value, from the old parameters; a new run starts from the prior.
            val nextMu = prepend(prior.mu, DoubleArray(n) { (kappa[it] * mu[it] + x) / (kappa[it] + 1) })
            val nextBeta = prepend(prior.beta, DoubleArray(n) { beta[it] + kappa[it] * (x - mu[it]) * (x - mu[it]) / (2 * (kappa[it] + 1)) })
            mu = nextMu
            beta = nextBeta
            kappa = prepend(prior.kappa, DoubleArray(n) { kappa[it] + 1 })
            alpha = prepend(prior.alpha, DoubleArray(n) { alpha[it] + 0.5 })
        }
        return BocpdResult(columns)
    }

    /** SciPy's `stats.t.pdf(x, df, loc, scale)`, as a log density. */
    internal fun studentTLogPdf(x: Double, df: Double, loc: Double, scale: Double): Double {
        val z = (x - loc) / scale
        return lnGamma((df + 1) / 2) - lnGamma(df / 2) - 0.5 * ln(df * PI) - ln(scale) - (df + 1) / 2 * ln(1 + z * z / df)
    }

    private fun prepend(first: Double, rest: DoubleArray): DoubleArray {
        val out = DoubleArray(rest.size + 1)
        out[0] = first
        rest.copyInto(out, 1)
        return out
    }

    private fun logSumExp(xs: DoubleArray): Double {
        var top = Double.NEGATIVE_INFINITY
        for (x in xs) top = max(top, x)
        if (top == Double.NEGATIVE_INFINITY) return top
        var sum = 0.0
        for (x in xs) sum += exp(x - top)
        return top + ln(sum)
    }
}
