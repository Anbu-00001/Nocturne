package io.github.anbu00001.nocturne.core.light

import kotlin.math.sqrt

/**
 * A modelled value with its uncertainty (spec §6.1: emit a band, never a bare point).
 * [mid] is the central assumption, not a statistical mean.
 */
data class Band(val low: Double, val mid: Double, val high: Double) {
    init {
        require(low <= mid && mid <= high) { "unordered band $low / $mid / $high" }
    }

    operator fun plus(other: Band) = Band(low + other.low, mid + other.mid, high + other.high)

    fun times(k: Double): Band {
        require(k >= 0)
        return Band(low * k, mid * k, high * k)
    }

    companion object {
        fun exact(value: Double) = Band(value, value, value)
        val ZERO = exact(0.0)
    }
}

/** Brown et al., PLOS Biology 2022: melanopic EDI targets at eye level. */
object MelanopicTargets {
    const val DAYTIME_MIN_LUX = 250.0
    /** From 3 h before bedtime. */
    const val EVENING_MAX_LUX = 10.0
    const val SLEEP_MAX_LUX = 1.0
}

/**
 * What the model needs to know about one phone's panel.
 *
 * The brightness setting is treated as linear in backlight: since Android 9 the system slider converts
 * its perceptual position with BrightnessUtils.convertGammaToLinear before writing, so the stored value
 * is already linear. Applying the spec's γ = 2.2 on top would count the curve twice. [settingIsLinear]
 * exists for ROMs found to store the slider position instead.
 */
data class DisplayProfile(
    val minNits: Double,
    val peakNits: Double,
    val screenAreaM2: Double,
    val brightnessSettingMin: Int,
    val brightnessSettingMax: Int,
    val settingIsLinear: Boolean = true,
) {
    init {
        require(peakNits > minNits && minNits >= 0 && screenAreaM2 > 0 && brightnessSettingMax > brightnessSettingMin)
    }

    fun luminanceNits(brightnessSetting: Int): Double =
        luminanceAtShare((brightnessSetting - brightnessSettingMin).toDouble() / (brightnessSettingMax - brightnessSettingMin))

    /** Luminance at [share] of the brightness range, 0 to 1. */
    fun luminanceAtShare(share: Double): Double {
        val t = share.coerceIn(0.0, 1.0)
        val linear = if (settingIsLinear) t else Math.pow(t, PERCEPTUAL_GAMMA)
        return minNits + (peakNits - minNits) * linear
    }

    companion object {
        const val PERCEPTUAL_GAMMA = 2.2

        /**
         * A computer's panel, whose brightness arrives as a share of the backlight's range rather than a setting. Linux
         * backlight drivers set a PWM duty cycle, which is close to linear in luminance.
         */
        fun panel(minNits: Double, peakNits: Double, widthMm: Int, heightMm: Int) = DisplayProfile(
            minNits = minNits,
            peakNits = peakNits,
            screenAreaM2 = widthMm * heightMm / 1_000_000.0,
            brightnessSettingMin = 0,
            brightnessSettingMax = 1,
        )

        /** Active area of a rectangular panel from its diagonal and pixel dimensions. */
        fun areaM2(diagonalInches: Double, widthPx: Int, heightPx: Int): Double {
            val diagonalM = diagonalInches * 0.0254
            val diagonalPx = sqrt(widthPx.toDouble() * widthPx + heightPx.toDouble() * heightPx)
            return (diagonalM * widthPx / diagonalPx) * (diagonalM * heightPx / diagonalPx)
        }

        /**
         * Oppo A18 (CPH2591), from `dumpsys display` on the device (2026-09-15): backlight maps linearly to
         * 2..490 nits, the brightness setting runs 0..4095 (the slider stops at 3276 without high brightness
         * mode), 6.56" 720 x 1612 panel. Read from the device's own display config, not measured with a meter.
         */
        val OPPO_A18 = DisplayProfile(
            minNits = 2.0,
            peakNits = 490.0,
            screenAreaM2 = areaM2(6.56, 720, 1612),
            brightnessSettingMin = 0,
            brightnessSettingMax = 4095,
        )

        /** Spec §6.1 defaults for an uncalibrated phone: 500 nits peak, 0..255 setting. */
        val GENERIC = DisplayProfile(
            minNits = 2.0,
            peakNits = 500.0,
            screenAreaM2 = areaM2(6.5, 1080, 2400),
            brightnessSettingMin = 1,
            brightnessSettingMax = 255,
        )
    }
}

/**
 * Every assumption behind the light estimate, each as a range. None of these is measured on the
 * user; they are the reason the output is a band.
 */
data class LightAssumptions(
    /** Spec §6.1: measured smartphone viewing distance is about 30 to 37 cm. */
    val viewingDistanceM: Band = Band(0.25, 0.35, 0.45),
    /**
     * Eyes to a laptop screen (Phase 4). Measured computer viewing distances average 56 to 62 cm (SD 8 to 10), and
     * laptops sit nearer than desktop monitors; the ergonomic range is 50 to 70 cm.
     */
    val laptopViewingDistanceM: Band = Band(0.4, 0.55, 0.7),
    /**
     * Average fraction of full-white luminance the content shows. An assumption, not measured:
     * a dark interface is mostly black, a light one mostly white.
     */
    val darkUiContentLevel: Band = Band(0.05, 0.2, 0.5),
    val lightUiContentLevel: Band = Band(0.4, 0.7, 0.9),
    /** Melanopic daylight efficacy ratio of a phone panel at its normal white point (spec: about 0.75 to 0.9). */
    val displayMder: Band = Band(0.75, 0.88, 1.0),
    /** With a warm night filter; LED sources at 2700 K sit near 0.48. */
    val warmFilterMder: Band = Band(0.4, 0.48, 0.55),
    /** Warm indoor light in the evening (2700 to 3000 K). */
    val eveningAmbientMder: Band = Band(0.4, 0.48, 0.6),
    val daytimeAmbientMder: Band = Band(0.8, 0.9, 1.0),
    /**
     * The light sensor faces out of the phone's front, not out of the user's eyes, and is often covered
     * (spec §11 pitfall 4). A weak prior with a wide band.
     */
    val sensorToEyeFactor: Band = Band(0.3, 0.7, 1.2),
    /** Used when no lux reading exists: a lit room in the evening, wide on purpose. */
    val eveningAmbientPriorLux: Band = Band(1.0, 30.0, 150.0),
)

/** Spec §6.1: corneal melanopic EDI from the screen and from room light, modelled separately. */
object LightDose {

    /**
     * mEDI from the screen: E = L · level · A / d² for a small emitter viewed face-on (the solid-angle
     * factor k_geom is 1 at these distances), times the display's MDER. A laptop passes its own [distanceM]. Its
     * panel is large for that: against the exact value for a disc of equal area, A / d² overstates by about 8% at
     * 55 cm and 15% at 40 cm, well inside the band.
     */
    fun screenMelanopicEdi(
        luminanceNits: Double,
        profile: DisplayProfile,
        darkUi: Boolean,
        warmFilter: Boolean,
        assumptions: LightAssumptions = LightAssumptions(),
        distanceM: Band = assumptions.viewingDistanceM,
    ): Band {
        val level = if (darkUi) assumptions.darkUiContentLevel else assumptions.lightUiContentLevel
        val mder = if (warmFilter) assumptions.warmFilterMder else assumptions.displayMder
        val d = distanceM
        fun edi(distance: Double, contentLevel: Double, efficacy: Double) =
            luminanceNits * contentLevel * profile.screenAreaM2 / (distance * distance) * efficacy
        return Band(
            low = edi(d.high, level.low, mder.low),
            mid = edi(d.mid, level.mid, mder.mid),
            high = edi(d.low, level.high, mder.high),
        )
    }

    /** mEDI from room light; [lux] null means no reading, which falls back to the evening prior. */
    fun ambientMelanopicEdi(
        lux: Double?,
        evening: Boolean,
        assumptions: LightAssumptions = LightAssumptions(),
    ): Band {
        val mder = if (evening) assumptions.eveningAmbientMder else assumptions.daytimeAmbientMder
        val scene = if (lux == null) assumptions.eveningAmbientPriorLux else assumptions.sensorToEyeFactor.times(lux)
        return Band(scene.low * mder.low, scene.mid * mder.mid, scene.high * mder.high)
    }
}
