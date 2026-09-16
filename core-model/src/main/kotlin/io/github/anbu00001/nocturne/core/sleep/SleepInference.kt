package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Bump when sleep inference changes; the app then re-derives every night (spec §5). */
const val SLEEP_MODEL_VERSION = 3

/** One screen session as sleep inference sees it. */
data class NightSession(
    val startTs: Long,
    val endTs: Long,
    /** Last event showing someone using the phone; equal to [startTs] when nothing did. */
    val lastActivityTs: Long,
    val kind: SessionKind,
    val unlocked: Boolean,
    val trigger: WakeTrigger,
)

/** Everything harvested for one night (noon to noon, named by its evening). */
data class NightInput(
    val date: LocalDate,
    /** UTC offset at the start of the night. */
    val offsetMinutes: Int,
    /** Sessions from the evening of [date] into the next afternoon; may overlap the neighbouring nights'. */
    val sessions: List<NightSession>,
    /** Intervals the phone was known to be charging. Empty when nothing was recorded. */
    val charging: List<LongRange> = emptyList(),
    /** First and last instants the harvested history covers. A night outside them gets no estimate, not a guess. */
    val dataFromTs: Long = Long.MIN_VALUE,
    val dataToTs: Long = Long.MAX_VALUE,
)

/** How much a session says about the user being awake. */
enum class Evidence {
    /** The phone lit itself (a notification, an alarm notice) and nobody touched it. */
    NONE,

    /** A person was there, briefly: a lock-screen check, a quick unlock, dismissing an alarm. */
    WEAK,

    /** A minute or more of unlocked use. */
    STRONG,
}

data class SleepConfig(
    /** The device's screen-off timeout. On the A18 it is 30 min, so a screen can stay lit long after the last touch. */
    val screenOffTimeoutMs: Long = 30_000,
    val timeoutToleranceMs: Long = 90_000,
    /** A new wake this soon after a timeout means the user was still there when the screen went dark. */
    val quickRewakeMs: Long = 2 * LocalClock.MINUTE_MS,
    /** Later activity than this after the wake shows a person acted on a wake the phone caused. */
    val userActionMs: Long = 2_000,
    val strongMinActiveMs: Long = LocalClock.MINUTE_MS,
    /** Spec §6.3 plausible onset window, local time. */
    val onsetEarliestMinute: Int = 20 * 60,
    val onsetLatestMinute: Int = 6 * 60,
    /**
     * Onsets after [onsetLatestMinute] and up to this are weighed too, but only with a wake the phone saw: the sleep
     * after a night spent awake starts in the morning (the A18, 14 Sept: in use until 07:30, quiet until 11:51). A
     * morning onset "still asleep" at the window's edge is not accepted, so a quiet afternoon at the end of the
     * history cannot pass for sleep.
     */
    val lateOnsetLatestMinute: Int = 12 * 60,
    /** Each night is scored from 18:00 on its evening to 16:00 the next day. */
    val windowStartHour: Int = 18,
    val windowEndHour: Int = 16,
    val minSleepMs: Long = 2 * LocalClock.HOUR_MS,
    val maxSleepMs: Long = 14 * LocalClock.HOUR_MS,
    /** Events per hour while asleep. Near zero, as in SensibleSleep (Cuttone et al., PLOS ONE 2017). */
    val asleepStrongPerHour: Double = 0.02,
    val asleepWeakPerHour: Double = 0.15,
    /** Awake rates are fitted per night; this keeps a very quiet evening from looking like sleep by default. */
    val awakeFloorPerHour: Double = 0.5,
    val typicalDurationMin: Double = 7.5 * 60,
    val typicalDurationSdMin: Double = 2.0 * 60,
    /**
     * Prior probability that a night holds no sleep at all. All-nighters are common over a student's years (60% of
     * students report at least one; Thacher, Behav Sleep Med 2008) but rare on any one night.
     */
    val noSleepPrior: Double = 0.03,
    /**
     * The other explanation for a quiet stretch is the user awake away from the phone, taken as exponentially
     * distributed with this mean. An assumption, not a finding: several quiet hours while awake are uncommon.
     */
    val awayMeanHours: Double = 2.0,
    /** phone-sleep-tracker's approach: median and MAD over the trailing two weeks. */
    val priorNights: Int = 14,
    val minPriorNights: Int = 7,
    val minOnsetSdMin: Double = 45.0,
    val minDurationSdMin: Double = 60.0,
    /**
     * Around the user's own onset and duration the priors are Student-t with these degrees of freedom. A week of
     * nights pins the centre well but says little about the tails, so an unusual night is unlikely, not ruled out.
     */
    val priorDegreesOfFreedom: Double = 4.0,
    /** An alternative this far from the best onset or wake counts as a competing explanation. */
    val alternativeSeparationMs: Long = 45 * LocalClock.MINUTE_MS,
    val marginScale: Double = 4.0,
    /** Nights at or above this confidence feed priors and the habitual window. */
    val confidentAt: Double = 0.4,
    /** Corrective offsets from the user's own reports (Abdullah et al., UbiComp 2016) need this many. */
    val minCorrectionNights: Int = 3,
    val maxCorrectionMs: Long = 90 * LocalClock.MINUTE_MS,
    /** Spec §6.3: at least 7 nights, recomputed weekly, from the last 4 weeks. */
    val minWindowNights: Int = 7,
    val windowLookbackDays: Int = 28,
)

data class SleepEstimate(
    val onsetTs: Long,
    val wakeTs: Long,
    /** 0..1. A heuristic, not a validated probability; already scaled by the chance that the night held sleep at all. */
    val confidence: Double,
    /** User-initiated sessions between onset and wake. */
    val interruptions: Int,
    /** False when the onset or wake is the edge of the search window, not an observed session. */
    val onsetAnchored: Boolean = true,
    val wakeAnchored: Boolean = true,
    /** The best explanation at least [SleepConfig.alternativeSeparationMs] away, for a person to choose between. */
    val rivalOnsetTs: Long? = null,
    val rivalWakeTs: Long? = null,
    /**
     * Log odds that the night held no sleep, against the best sleep explanation. Above zero the night looks
     * sleepless, and [onsetTs] to [wakeTs] is only the quiet stretch that lost. Negative infinity when not assessed.
     */
    val noSleepLogOdds: Double = Double.NEGATIVE_INFINITY,
) {
    val noSleep: Boolean get() = noSleepLogOdds > 0

    /** The weight of the no-sleep explanation, 0..1. */
    val noSleepProbability: Double get() = 1 / (1 + exp(-noSleepLogOdds))
}

/** The user's recent sleep, as a soft prior for the next night. */
data class PersonalPrior(
    val onsetMinutesSinceNoon: Double,
    val onsetSdMin: Double,
    val durationMin: Double,
    val durationSdMin: Double,
    val nights: Int,
)

internal data class Mark(val startTs: Long, val activeEndTs: Long, val evidence: Evidence)

/**
 * Turns sessions into activity marks. A session that ended by screen timeout is cut back to the timeout
 * before its end, unless the user woke the phone again straight away (seen on the A18: a browser page
 * timing out after 30 min and being switched back on within 4 s).
 */
internal fun marksOf(sessions: List<NightSession>, config: SleepConfig): List<Mark> {
    val sorted = sessions.sortedBy { it.startTs }
    return sorted.mapIndexedNotNull { i, s ->
        val activeEnd = activeEndOf(s, sorted.getOrNull(i + 1)?.startTs ?: Long.MAX_VALUE, config)
        val acted = s.unlocked || s.trigger == WakeTrigger.UNKNOWN || s.lastActivityTs - s.startTs > config.userActionMs
        if (!acted) return@mapIndexedNotNull null
        val evidence = if (s.unlocked && activeEnd - s.startTs >= config.strongMinActiveMs) Evidence.STRONG else Evidence.WEAK
        Mark(s.startTs, maxOf(activeEnd, s.startTs), evidence)
    }
}

/** When [s] was last in use: its end, or, if it ended by screen timeout and the phone stayed dark past [nextStartTs]'s quick re-wake, its last activity. */
internal fun activeEndOf(s: NightSession, nextStartTs: Long, config: SleepConfig): Long {
    val idleMs = s.endTs - s.lastActivityTs
    val tolerance = minOf(config.timeoutToleranceMs, config.screenOffTimeoutMs / 4)
    val timedOut = config.screenOffTimeoutMs > 0 && idleMs >= config.screenOffTimeoutMs - tolerance
    return if (timedOut && nextStartTs - s.endTs > config.quickRewakeMs) maxOf(s.lastActivityTs, s.endTs - config.screenOffTimeoutMs) else s.endTs
}

/**
 * Spec §6.3, improvised after testing the spec's rule on real nights. "Last screen-off before a gap
 * over 4 h" fails on this user's data: nights hold 3 h blocks, and a 1 s check at 04:16 splits an
 * 8 h night in two.
 *
 * Instead each night is a change-point problem, as in SensibleSleep: user-initiated sessions arrive as
 * Poisson processes (strong and weak evidence separately) at a low rate while asleep and at the night's
 * own fitted rate while awake. Onset candidates are the ends of real activity, wake candidates the
 * starts of later sessions, and the pair with the highest likelihood (plus soft priors on duration and,
 * after a week, on the user's usual onset) wins. A night-time check costs about as much as half an hour
 * of quiet, so brief checks stay inside the night and a three-hour awake spell does not.
 *
 * The winning quiet stretch is then weighed against a night with no sleep in it (see [noSleepLogOdds]).
 */
class SleepInference(private val config: SleepConfig = SleepConfig()) {

    private class Candidate(
        val onset: Long,
        val wake: Long,
        val onsetAnchored: Boolean,
        val wakeAnchored: Boolean,
        val score: Double,
        val inside: Int,
    )

    fun infer(night: NightInput, prior: PersonalPrior?): SleepEstimate? {
        val midnight = localMidnightUtc(night.date, night.offsetMinutes)
        val onsetFrom = midnight + config.onsetEarliestMinute * LocalClock.MINUTE_MS
        val usualOnsetTo = midnight + LocalClock.DAY_MS + config.onsetLatestMinute * LocalClock.MINUTE_MS
        val lateOnsetTo = midnight + LocalClock.DAY_MS + config.lateOnsetLatestMinute * LocalClock.MINUTE_MS

        // No estimate for a night the history does not reach back to, or one still under way: until the
        // history passes the latest usual onset, the user may simply still be up.
        if (night.dataFromTs > onsetFrom || night.dataToTs < usualOnsetTo) return null
        return search(night, prior, onsetFrom, usualOnsetTo, lateOnsetTo)
    }

    /**
     * The best sleep with its onset between [onsetFrom] and [onsetTo], or null when no stretch qualifies. An onset
     * after [usualOnsetTo] needs a wake the phone saw.
     */
    private fun search(night: NightInput, prior: PersonalPrior?, onsetFrom: Long, usualOnsetTo: Long, onsetTo: Long): SleepEstimate? {
        val midnight = localMidnightUtc(night.date, night.offsetMinutes)
        val windowStart = midnight + config.windowStartHour * LocalClock.HOUR_MS
        val windowEnd = midnight + LocalClock.DAY_MS + config.windowEndHour * LocalClock.HOUR_MS
        val searchEnd = minOf(windowEnd, night.dataToTs)
        val marks = marksOf(night.sessions, config).filter { it.startTs in windowStart until searchEnd }
        if (marks.isEmpty()) return null
        val starts = LongArray(marks.size) { marks[it].startTs }
        val strongBefore = IntArray(marks.size + 1)
        val weakBefore = IntArray(marks.size + 1)
        marks.forEachIndexed { i, m ->
            strongBefore[i + 1] = strongBefore[i] + if (m.evidence == Evidence.STRONG) 1 else 0
            weakBefore[i + 1] = weakBefore[i] + if (m.evidence == Evidence.WEAK) 1 else 0
        }
        val windowHours = (searchEnd - windowStart).toDouble() / LocalClock.HOUR_MS

        val candidates = ArrayList<Candidate>()
        val onsets = marks.map { it.activeEndTs }.filter { it in onsetFrom..minOf(onsetTo, searchEnd) }.distinct().map { it to true } +
            (onsetFrom to false)
        for ((onset, onsetAnchored) in onsets) {
            val latestWake = minOf(windowEnd, onset + config.maxSleepMs)
            // Only claim "still asleep at the edge" if the history actually reaches that edge, and never for a morning onset.
            val openEnded = if (night.dataToTs >= latestWake && onset <= usualOnsetTo) listOf(latestWake to false) else emptyList()
            val wakes = marks.map { it.startTs }
                .filter { it >= onset + config.minSleepMs && it <= latestWake }
                .distinct()
                .map { it to true } + openEnded
            for ((wake, wakeAnchored) in wakes) {
                if (wake - onset < config.minSleepMs) continue
                val from = lowerBound(starts, onset)
                val to = lowerBound(starts, wake)
                val strongIn = strongBefore[to] - strongBefore[from]
                val weakIn = weakBefore[to] - weakBefore[from]
                val sleepHours = (wake - onset).toDouble() / LocalClock.HOUR_MS
                val awakeHours = windowHours - sleepHours
                val strongOut = strongBefore[marks.size] - strongIn
                val weakOut = weakBefore[marks.size] - weakIn

                var score = poisson(strongIn, config.asleepStrongPerHour, sleepHours) +
                    poisson(weakIn, config.asleepWeakPerHour, sleepHours) +
                    poisson(strongOut, max(strongOut / awakeHours, config.awakeFloorPerHour), awakeHours) +
                    poisson(weakOut, max(weakOut / awakeHours, config.awakeFloorPerHour), awakeHours)
                score += durationPriorShape(sleepHours * 60, prior)
                if (prior != null) score += onsetPriorShape(onset, night.offsetMinutes, prior)
                candidates += Candidate(onset, wake, onsetAnchored, wakeAnchored, score, strongIn + weakIn)
            }
        }
        val best = candidates.maxByOrNull { it.score } ?: return null
        val rival = candidates
            .filter { abs(it.onset - best.onset) > config.alternativeSeparationMs || abs(it.wake - best.wake) > config.alternativeSeparationMs }
            .maxByOrNull { it.score }

        val hours = (best.wake - best.onset).toDouble() / LocalClock.HOUR_MS
        val noSleepLogOdds = noSleepLogOdds(
            hours = hours,
            onset = best.onset,
            offsetMinutes = night.offsetMinutes,
            prior = prior,
            onsetRangeHours = (onsetTo - onsetFrom).toDouble() / LocalClock.HOUR_MS,
            windowHours = windowHours,
        )
        val margin = if (rival == null) 1.0 else 1 - exp(-(best.score - rival.score) / config.marginScale)
        val anchoring = (if (best.onsetAnchored) 1.0 else 0.5) * (if (best.wakeAnchored) 1.0 else 0.5)
        val plausible = if (hours in 4.0..11.0) 1.0 else 0.6
        val consistency = prior?.let {
            val z = (minutesSinceNoon(best.onset, night.offsetMinutes) - it.onsetMinutesSinceNoon) / it.onsetSdMin
            0.5 + 0.5 * exp(-z * z / 2)
        } ?: 0.8
        val charging = chargingShare(night.charging, best.onset, best.wake)?.let { 0.85 + 0.15 * it } ?: 1.0
        val sleptProbability = 1 / (1 + exp(noSleepLogOdds))
        val confidence = (margin * anchoring * plausible * consistency * charging * sleptProbability).coerceIn(0.0, 1.0)

        return SleepEstimate(
            best.onset, best.wake, confidence, best.inside, best.onsetAnchored, best.wakeAnchored, rival?.onset, rival?.wake,
            noSleepLogOdds,
        )
    }

    /**
     * Was the best quiet stretch sleep, or the user awake away from the phone (at a laptop, say) on a night with no
     * sleep? The phone saw the same quiet either way, so its likelihood cancels and only the priors decide: sleep
     * of this length starting then, against an away stretch of this length anywhere in the window. The duration
     * spread is never narrower than the population's, so a genuinely short night is not taken for no sleep.
     */
    private fun noSleepLogOdds(
        hours: Double,
        onset: Long,
        offsetMinutes: Int,
        prior: PersonalPrior?,
        onsetRangeHours: Double,
        windowHours: Double,
    ): Double {
        val durationMeanHours = (prior?.durationMin ?: config.typicalDurationMin) / 60
        val durationSdHours = maxOf(prior?.durationSdMin ?: 0.0, config.typicalDurationSdMin) / 60
        val sleep = ln(1 - config.noSleepPrior) +
            normalLogDensity(hours, durationMeanHours, durationSdHours) +
            (prior?.let { onsetLogDensity(onset, offsetMinutes, it) } ?: -ln(onsetRangeHours))
        val away = ln(config.noSleepPrior) - ln(config.awayMeanHours) - hours / config.awayMeanHours - ln(windowHours)
        return away - sleep
    }

    /** Gaussian around the population's typical duration, or Student-t around the user's own. */
    private fun durationPriorShape(minutes: Double, prior: PersonalPrior?): Double =
        if (prior == null) {
            -square((minutes - config.typicalDurationMin) / config.typicalDurationSdMin) / 2
        } else {
            studentShape((minutes - prior.durationMin) / prior.durationSdMin)
        }

    /** Student-t log density of the onset, per hour, around the user's usual onset. */
    private fun onsetLogDensity(onset: Long, offsetMinutes: Int, prior: PersonalPrior): Double {
        val nu = config.priorDegreesOfFreedom
        return lnGamma((nu + 1) / 2) - lnGamma(nu / 2) - 0.5 * ln(nu * PI) - ln(prior.onsetSdMin / 60) +
            onsetPriorShape(onset, offsetMinutes, prior)
    }

    private fun onsetPriorShape(onset: Long, offsetMinutes: Int, prior: PersonalPrior): Double =
        studentShape((minutesSinceNoon(onset, offsetMinutes) - prior.onsetMinutesSinceNoon) / prior.onsetSdMin)

    private fun studentShape(z: Double): Double {
        val nu = config.priorDegreesOfFreedom
        return -(nu + 1) / 2 * ln(1 + z * z / nu)
    }

    private fun normalLogDensity(x: Double, mean: Double, sd: Double) = -square((x - mean) / sd) / 2 - ln(sd * sqrt(2 * PI))

    private fun poisson(n: Int, ratePerHour: Double, hours: Double) = n * ln(ratePerHour) - ratePerHour * hours

    private fun chargingShare(charging: List<LongRange>, from: Long, to: Long): Double? {
        if (charging.isEmpty()) return null
        val covered = charging.sumOf { r -> max(0L, minOf(r.last, to) - maxOf(r.first, from)) }
        return covered.toDouble() / (to - from)
    }

    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

internal fun square(x: Double) = x * x

/** ln Γ(x) for x > 0, by the Lanczos approximation (g = 7, 9 terms). */
internal fun lnGamma(x: Double): Double {
    if (x < 0.5) return ln(PI / sin(PI * x)) - lnGamma(1 - x)
    val z = x - 1
    var sum = LANCZOS[0]
    for (i in 1 until LANCZOS.size) sum += LANCZOS[i] / (z + i)
    val t = z + 7.5
    return 0.5 * ln(2 * PI) + (z + 0.5) * ln(t) - t + ln(sum)
}

private val LANCZOS = doubleArrayOf(
    0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059,
    12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
)

internal fun localMidnightUtc(date: LocalDate, offsetMinutes: Int): Long =
    date.toEpochDay() * LocalClock.DAY_MS - offsetMinutes * LocalClock.MINUTE_MS

/** Minutes after local noon, so evening and early-morning onsets sit on one unbroken scale. */
internal fun minutesSinceNoon(ts: Long, offsetMinutes: Int): Double =
    Math.floorMod(LocalClock.minuteOfDay(ts, offsetMinutes) - 12 * 60, LocalClock.MINUTES_PER_DAY).toDouble()
