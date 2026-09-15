package io.github.anbu00001.nocturne.core.light

import io.github.anbu00001.nocturne.core.sleep.localMidnightUtc
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import kotlin.math.roundToInt

/** One stored light sample: what the sensor and the display showed from [startTs] until [endTs]. */
data class LightReading(
    val startTs: Long,
    val durationMs: Long,
    /** Median lux over the sample; null when the sensor sent nothing. */
    val lux: Double?,
    /** Settings.System.SCREEN_BRIGHTNESS as stored; null when unreadable. */
    val brightnessSetting: Int?,
    val darkUi: Boolean?,
    val warmFilter: Boolean?,
) {
    val endTs: Long get() = startTs + durationMs
}

/** A stretch with the screen on, from a derived session. */
data class ScreenSpan(val startTs: Long, val endTs: Long)

data class EveningInterval(val startTs: Long, val endTs: Long)

/** What the evening model assumes wherever nothing was measured. Each is a stated assumption, not a finding. */
data class UnmeasuredLight(
    /**
     * With the screen off the room is not measured. The low end assumes dark straight away; the middle keeps the
     * last room reading this long (a phone put down in a lit room), then assumes dark; the high end keeps the last
     * reading, or the evening prior when there is none, until the interval ends.
     */
    val screenOffCarryMs: Long = 30 * LocalClock.MINUTE_MS,
    /** A sample this close to a screen-on minute still describes it; the sampler writes every 30 s. */
    val sampleReachMs: Long = 90_000,
    /** Screen on with no sample: brightness anywhere in this share of the setting's range. */
    val brightnessShare: Band = Band(0.0, 0.3, 0.8),
)

data class EveningLightEstimate(
    val suppression: SuppressionEstimate,
    /** Minutes in which the screen was on at all. */
    val screenMinutes: Int,
    /** Of those, the minutes a light sample covered. */
    val measuredMinutes: Int,
)

internal data class MinuteLight(val bands: List<Band>, val screenMinutes: Int, val measuredMinutes: Int)

/**
 * Spec §6.1 and §6.2 over a real evening: a melanopic EDI band for every minute, from the screen sessions the
 * harvester derived and the samples the light service wrote, then Giménez suppression over the lot.
 */
object EveningLight {

    /**
     * From the evening window's start to sleep onset, or to the window's end when there is no onset, and never past
     * [dataToTs]: minutes nothing has recorded yet are assumed neither dark nor lit. Null when nothing is left.
     */
    fun interval(date: LocalDate, offsetMinutes: Int, window: EveningWindow, onsetTs: Long?, dataToTs: Long): EveningInterval? {
        val boundaryMinute = LocalClock.NIGHT_BOUNDARY_HOUR * 60
        val noon = localMidnightUtc(date, offsetMinutes) + boundaryMinute * LocalClock.MINUTE_MS
        val start = noon + minutesFrom(boundaryMinute, window.startMinute) * LocalClock.MINUTE_MS
        val length = minutesFrom(window.startMinute, window.endMinute).takeIf { it > 0 } ?: LocalClock.MINUTES_PER_DAY
        val windowEnd = start + length * LocalClock.MINUTE_MS
        val end = minOf(onsetTs ?: windowEnd, windowEnd, dataToTs)
        return if (end > start) EveningInterval(start, end) else null
    }

    /**
     * Light at the eyes for one moment: the screen from its brightness and mode, the room from [lux] or the prior.
     * [evening] picks warm evening room light over daylight-like room light.
     */
    fun atEyes(
        lux: Double?,
        brightnessSetting: Int?,
        darkUi: Boolean?,
        warmFilter: Boolean?,
        profile: DisplayProfile,
        assumptions: LightAssumptions = LightAssumptions(),
        unmeasured: UnmeasuredLight = UnmeasuredLight(),
        evening: Boolean = true,
    ): Band = screen(brightnessSetting, darkUi, warmFilter, profile, assumptions, unmeasured) +
        LightDose.ambientMelanopicEdi(lux, evening = evening, assumptions = assumptions)

    /** The screen's share alone, for when the room is not measured and a prior would only restate an assumption. */
    fun screenAtEyes(
        brightnessSetting: Int?,
        darkUi: Boolean?,
        warmFilter: Boolean?,
        profile: DisplayProfile,
        assumptions: LightAssumptions = LightAssumptions(),
        unmeasured: UnmeasuredLight = UnmeasuredLight(),
    ): Band = screen(brightnessSetting, darkUi, warmFilter, profile, assumptions, unmeasured)

    fun estimate(
        interval: EveningInterval,
        screen: List<ScreenSpan>,
        samples: List<LightReading>,
        profile: DisplayProfile,
        assumptions: LightAssumptions = LightAssumptions(),
        unmeasured: UnmeasuredLight = UnmeasuredLight(),
        sensitivity: Double = 1.0,
    ): EveningLightEstimate {
        val minutes = minutes(interval, screen, samples, profile, assumptions, unmeasured)
        return EveningLightEstimate(
            EveningExposure.estimate(minutes.bands, sensitivity = sensitivity),
            minutes.screenMinutes,
            minutes.measuredMinutes,
        )
    }

    internal fun minutes(
        interval: EveningInterval,
        screen: List<ScreenSpan>,
        samples: List<LightReading>,
        profile: DisplayProfile,
        assumptions: LightAssumptions,
        unmeasured: UnmeasuredLight,
    ): MinuteLight {
        val spans = screen.sortedBy { it.startTs }
        val sorted = samples.sortedBy { it.startTs }
        val prior = LightDose.ambientMelanopicEdi(null, evening = true, assumptions = assumptions)
        val bands = ArrayList<Band>()
        var screenMinutes = 0
        var measuredMinutes = 0
        var room: Band? = null
        var lastScreenOnTs = 0L
        var spanFrom = 0
        var sampleFrom = 0
        var t = interval.startTs
        while (t < interval.endTs) {
            val end = minOf(t + LocalClock.MINUTE_MS, interval.endTs)
            val length = end - t

            while (spanFrom < spans.size && spans[spanFrom].endTs <= t) spanFrom++
            var on = 0L
            var i = spanFrom
            while (i < spans.size && spans[i].startTs < end) {
                val o = overlap(spans[i].startTs, spans[i].endTs, t, end)
                if (o > 0) {
                    on += o
                    lastScreenOnTs = maxOf(lastScreenOnTs, minOf(spans[i].endTs, end))
                }
                i++
            }
            on = minOf(on, length)

            var band = Band.ZERO
            if (on > 0) {
                screenMinutes++
                while (sampleFrom < sorted.size && sorted[sampleFrom].endTs <= t - unmeasured.sampleReachMs) sampleFrom++
                val sample = closest(sorted, sampleFrom, t, end, unmeasured.sampleReachMs)
                if (sample != null) measuredMinutes++
                val lux = sample?.lux
                val lit = atEyes(lux, sample?.brightnessSetting, sample?.darkUi, sample?.warmFilter, profile, assumptions, unmeasured)
                band += lit.times(on.toDouble() / length)
                if (lux != null) room = LightDose.ambientMelanopicEdi(lux, evening = true, assumptions = assumptions)
            }
            val off = length - on
            if (off > 0) {
                val last = room
                val carriedMid = if (last != null && t - lastScreenOnTs <= unmeasured.screenOffCarryMs) last.mid else 0.0
                band += Band(0.0, carriedMid, (last ?: prior).high).times(off.toDouble() / length)
            }
            bands += band.times(length.toDouble() / LocalClock.MINUTE_MS)
            t = end
        }
        return MinuteLight(bands, screenMinutes, measuredMinutes)
    }

    private fun screen(
        setting: Int?,
        darkUi: Boolean?,
        warmFilter: Boolean?,
        profile: DisplayProfile,
        assumptions: LightAssumptions,
        unmeasured: UnmeasuredLight,
    ): Band {
        if (setting != null) return modes(profile.luminanceNits(setting), darkUi, warmFilter, profile, assumptions)
        val range = profile.brightnessSettingMax - profile.brightnessSettingMin
        fun at(share: Double) =
            modes(profile.luminanceNits(profile.brightnessSettingMin + (range * share).roundToInt()), darkUi, warmFilter, profile, assumptions)
        val share = unmeasured.brightnessShare
        return Band(at(share.low).low, at(share.mid).mid, at(share.high).high)
    }

    /** A display mode the phone would not report widens the band to cover both. */
    private fun modes(nits: Double, darkUi: Boolean?, warmFilter: Boolean?, profile: DisplayProfile, assumptions: LightAssumptions): Band {
        val darks = darkUi?.let { listOf(it) } ?: listOf(true, false)
        val warms = warmFilter?.let { listOf(it) } ?: listOf(true, false)
        val bands = darks.flatMap { dark -> warms.map { warm -> LightDose.screenMelanopicEdi(nits, profile, dark, warm, assumptions) } }
        return Band(bands.minOf { it.low }, bands.sumOf { it.mid } / bands.size, bands.maxOf { it.high })
    }

    /** The sample overlapping the minute most, else the nearest within [reachMs]. */
    private fun closest(samples: List<LightReading>, from: Int, t: Long, end: Long, reachMs: Long): LightReading? {
        var best: LightReading? = null
        var bestOverlap = -1L
        var bestGap = Long.MAX_VALUE
        var i = from
        while (i < samples.size && samples[i].startTs < end + reachMs) {
            val s = samples[i]
            if (s.endTs > t - reachMs) {
                val o = overlap(s.startTs, s.endTs, t, end)
                val gap = if (o > 0) 0L else maxOf(s.startTs - end, t - s.endTs)
                if (o > bestOverlap || (o == bestOverlap && gap < bestGap)) {
                    best = s
                    bestOverlap = o
                    bestGap = gap
                }
            }
            i++
        }
        return best
    }

    private fun minutesFrom(fromMinute: Int, toMinute: Int) = Math.floorMod(toMinute - fromMinute, LocalClock.MINUTES_PER_DAY)

    private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long) = maxOf(0L, minOf(aEnd, bEnd) - maxOf(aStart, bStart))
}
