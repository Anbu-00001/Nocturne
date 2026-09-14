package io.github.anbu00001.nocturne.core.light

import kotlin.math.log10
import kotlin.math.pow

/**
 * Modelled melatonin suppression (spec §6.2), after Giménez et al., J Pineal Res 2022, 72:e12786, Eq. 7:
 *
 *     suppression = 100 / (1 + (c / log10(mEDI · 10⁶))^d)
 *     c = b + βₑ · Δt[min] + βₚ · dilated
 *
 * This is not the spec's `100 / (1 + (ED50 / mEDI)^k)`. The paper fits the logistic to log illuminance,
 * which makes the curve far shallower (around ED50 it behaves like a Hill slope of about 0.4), so
 * suppression keeps rising over several orders of magnitude of light.
 *
 * ED50 = 10^(c − 6). Table 3 prints βₑ = −0.008 (SE 0.001), rounded. The paper's own worked values,
 * 208 lx at 90 min with natural pupils and 72 lx dilated, need βₑ ≈ −0.0076, and that value also
 * reproduces the spec's table: 600 / 350 / 120 / 43 / 15 lx at 0.5 / 1 / 2 / 3 / 4 h.
 *
 * Cross-checks, kept apart rather than averaged:
 * - Phillips et al., PNAS 2019: group ED50 24.6 photopic lux under 3968 K fluorescent light over 5 h
 *   (individuals 6.0 to 349.8 lux). Its per-hour ED50s rise (13.47 / 19.38 / 38.89 lux for hours 1 to 3)
 *   because each is the suppression of melatonin AUC within that hour after DLMO, not a cumulative
 *   exposure duration. They answer a different question from Giménez's falling ED50s.
 * - Zeitzer et al., J Physiol 2000: half-maximum about 106 photopic lux, 6.5 h exposure.
 *
 * Durations outside the 30 to 240 min the data covered are clamped and flagged, never extrapolated.
 * Natural pupils are assumed; nobody dilates their pupils to read a phone in bed.
 */
object MelatoninSuppression {
    const val B = 9.002
    const val D = 7.496
    const val DURATION_COEFFICIENT_PER_MIN = -0.0076
    const val DILATION_COEFFICIENT = -0.462
    const val MIN_DURATION_MIN = 30.0
    const val MAX_DURATION_MIN = 240.0

    /** [sensitivity] multiplies ED50 (spec §6.2 personal calibration); above 1 is less sensitive. */
    fun percent(mEdi: Double, durationMin: Double, dilated: Boolean = false, sensitivity: Double = 1.0): Double {
        require(sensitivity > 0)
        if (mEdi <= 0) return 0.0
        val x = log10(mEdi * 1e6)
        if (x <= 0) return 0.0
        return 100.0 / (1.0 + (halfPoint(durationMin, dilated, sensitivity) / x).pow(D))
    }

    fun ed50(durationMin: Double, dilated: Boolean = false, sensitivity: Double = 1.0): Double =
        10.0.pow(halfPoint(durationMin, dilated, sensitivity) - 6)

    fun isExtrapolated(durationMin: Double) = durationMin < MIN_DURATION_MIN || durationMin > MAX_DURATION_MIN

    private fun halfPoint(durationMin: Double, dilated: Boolean, sensitivity: Double): Double =
        B + DURATION_COEFFICIENT_PER_MIN * durationMin.coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN) +
            (if (dilated) DILATION_COEFFICIENT else 0.0) + log10(sensitivity)
}

data class SuppressionEstimate(
    /** Modelled, never measured. Always shown as the band. */
    val percent: Band,
    val melanopicDoseLuxHours: Band,
    val exposedMinutes: Int,
    /** The exposure ran shorter than 30 min or longer than 4 h, beyond the data Giménez fitted. */
    val durationClamped: Boolean,
)

/**
 * Real evening phone use is bursty (spec §6.2: integrate over the actual profile). Giménez modelled
 * continuous exposure, and how intermittent light adds up for melatonin is not settled (Najjar and
 * Zeitzer, JCI 2016, found flashes shift phase but did not suppress melatonin in an interval-dependent
 * way). So the gaps become part of the band rather than a hidden choice:
 *
 * - mid: duration = minutes above [floorLux], intensity = their mean (dose-conserving)
 * - one bound: only the longest unbroken run counts, at its own mean
 * - other bound: first to last exposed minute counts as one exposure, gaps included at zero light
 *
 * A constant unbroken block gives the same number from all three, so the paper's table is reproduced exactly.
 */
object EveningExposure {

    /** [minutes] holds one mEDI band per consecutive minute of the evening. */
    fun estimate(minutes: List<Band>, floorLux: Double = MelanopicTargets.SLEEP_MAX_LUX, sensitivity: Double = 1.0): SuppressionEstimate {
        val dose = Band(minutes.sumOf { it.low }, minutes.sumOf { it.mid }, minutes.sumOf { it.high }).times(1.0 / 60)
        val exposed = minutes.indices.filter { minutes[it].mid >= floorLux }
        if (exposed.isEmpty()) return SuppressionEstimate(Band.ZERO, dose, 0, durationClamped = false)

        val run = longestRun(exposed)
        val span = exposed.first()..exposed.last()
        val variants = listOf(exposed, run.toList(), span.toList())

        fun suppression(indices: List<Int>, component: (Band) -> Double): Double {
            val duration = indices.size.toDouble()
            val mean = indices.sumOf { component(minutes[it]) } / duration
            return MelatoninSuppression.percent(mean, duration, sensitivity = sensitivity)
        }

        val percent = Band(
            low = variants.minOf { suppression(it) { b -> b.low } },
            mid = suppression(exposed) { it.mid },
            high = variants.maxOf { suppression(it) { b -> b.high } },
        )
        return SuppressionEstimate(
            percent = percent,
            melanopicDoseLuxHours = dose,
            exposedMinutes = exposed.size,
            durationClamped = variants.any { MelatoninSuppression.isExtrapolated(it.size.toDouble()) },
        )
    }

    private fun longestRun(sortedIndices: List<Int>): IntRange {
        var best = sortedIndices.first()..sortedIndices.first()
        var start = sortedIndices.first()
        for (i in 1 until sortedIndices.size) {
            if (sortedIndices[i] != sortedIndices[i - 1] + 1) start = sortedIndices[i]
            if (sortedIndices[i] - start > best.last - best.first) best = start..sortedIndices[i]
        }
        return best
    }
}
