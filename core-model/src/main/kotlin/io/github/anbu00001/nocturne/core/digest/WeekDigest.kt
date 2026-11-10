package io.github.anbu00001.nocturne.core.digest

import java.time.LocalDate
import kotlin.math.sqrt

/**
 * One night as the digest sees it. [onsetMinute] is minutes after noon on the night's own date, the scale the timing
 * metrics use, so 23:50 and 00:10 are 20 minutes apart. [lateAppMinutes] is each app's foreground minutes in the
 * [WeekDigest.Config.lateHours] before sleep onset; empty when the night has no onset.
 */
data class DigestNight(
    val date: LocalDate,
    val onsetMinute: Double?,
    val sleepMinutes: Double?,
    val noSleep: Boolean = false,
    val lateAppMinutes: Map<String, Double> = emptyMap(),
) {
    val timed: Boolean get() = onsetMinute != null && sleepMinutes != null
}

/** A stretch of nights in numbers. Medians, because a single 06:48 onset moves a mean of seven by an hour. */
data class PeriodSummary(
    /** Nights with an onset and a wake; the only ones the figures below rest on. */
    val nights: Int,
    val medianOnsetMinute: Double,
    /** Sample standard deviation of onset in minutes, or null under [WeekDigest.Config.minSpreadNights] nights. */
    val onsetSpreadMinutes: Double?,
    val medianSleepMinutes: Double,
    val shortNights: Int,
    val longNights: Int,
    val noSleepNights: Int,
)

/**
 * Nights on which one app was open late beside the nights on which it was not. An association in this person's own
 * nights and nothing more: a late night leaves more room for the app as surely as the app can keep someone up
 * (Bauducco et al., Sleep Med Rev 2024, argue both directions), so the copy never says which caused which.
 */
data class LateAppSplit(
    val packageName: String,
    val withNights: Int,
    val withMedianOnsetMinute: Double,
    val withMedianSleepMinutes: Double,
    val withoutNights: Int,
    val withoutMedianOnsetMinute: Double,
    val withoutMedianSleepMinutes: Double,
)

/**
 * The last seven nights beside the four weeks before them, for one glance (a home-screen widget) and for the top of
 * Patterns. Every figure is the person's own history; nothing is compared with a norm, because the published reference
 * bands come from cohorts unlike one adult with a moving schedule.
 *
 * Why a week: Gouveia et al. (UbiComp 2015) found that most looks at a tracker last about five seconds and that text which
 * changes keeps people looking; Epstein et al. (UbiComp 2016) advise aggregating more when someone has been away. One
 * line that is different each week does both, and needs nothing typed.
 */
data class WeekDigest(
    val endDate: LocalDate,
    /** Null when fewer than [Config.minWeekNights] of the seven nights have times. */
    val week: PeriodSummary?,
    /** Null when fewer than [Config.minBaselineNights] of the 28 nights before have times. */
    val baseline: PeriodSummary?,
    /**
     * The [Config.maxLateApps] apps with the most late minutes that can be compared, most minutes first. Chosen by minutes
     * and never by how far apart their nights are: picking the app with the biggest gap out of a dozen would find a gap in
     * noise. So an app that makes no difference is shown beside one that does.
     */
    val lateApps: List<LateAppSplit>,
) {
    data class Config(
        val weekNights: Int = 7,
        val baselineNights: Int = 28,
        val minWeekNights: Int = 4,
        val minBaselineNights: Int = 7,
        val minSpreadNights: Int = 4,
        /** Under 5 h and from 9 h: the runs seen in this history (25 to 29 Oct, 7 to 9 Nov 2026) sit clear of both. */
        val shortBelowMinutes: Double = 5 * 60.0,
        val longFromMinutes: Double = 9 * 60.0,
        /** The app split looks at the hours before onset, the stretch where Christensen et al. (PLoS ONE 2016) found screen time related to sleep. */
        val lateHours: Int = 3,
        /** An app counts as open late from this many minutes, so a notification tapped away does not. */
        val lateAppMinutes: Double = 10.0,
        /** Each side of the split needs this many nights; SleepCoacher (Daskalova et al., UIST 2016) asks 3 to 5 per phase. */
        val minSplitNights: Int = 5,
        val maxLateApps: Int = 2,
    )

    companion object {
        /**
         * [nights] in any order; those after [endDate] or before the baseline are ignored. [ignoredPackages] are apps that are
         * on screen without being a choice (the launcher, the system, the clock).
         */
        fun of(nights: List<DigestNight>, endDate: LocalDate, ignoredPackages: Set<String> = emptySet(), config: Config = Config()): WeekDigest {
            val weekFrom = endDate.minusDays(config.weekNights - 1L)
            val baselineFrom = weekFrom.minusDays(config.baselineNights.toLong())
            val week = nights.filter { it.date in weekFrom..endDate }
            val baseline = nights.filter { it.date >= baselineFrom && it.date < weekFrom }
            return WeekDigest(
                endDate = endDate,
                week = summary(week, config).takeIf { it != null && it.nights >= config.minWeekNights },
                baseline = summary(baseline, config).takeIf { it != null && it.nights >= config.minBaselineNights },
                lateApps = lateApps(week + baseline, ignoredPackages, config),
            )
        }

        private fun summary(nights: List<DigestNight>, config: Config): PeriodSummary? {
            val timed = nights.filter { it.timed }
            if (timed.isEmpty()) return null
            val onsets = timed.map { it.onsetMinute!! }
            val sleeps = timed.map { it.sleepMinutes!! }
            return PeriodSummary(
                nights = timed.size,
                medianOnsetMinute = median(onsets),
                onsetSpreadMinutes = if (timed.size >= config.minSpreadNights) sampleSd(onsets) else null,
                medianSleepMinutes = median(sleeps),
                shortNights = sleeps.count { it < config.shortBelowMinutes },
                longNights = sleeps.count { it >= config.longFromMinutes },
                noSleepNights = nights.count { it.noSleep },
            )
        }

        /** The apps with the most late minutes over the span, for which both kinds of night are common enough to compare. */
        private fun lateApps(nights: List<DigestNight>, ignored: Set<String>, config: Config): List<LateAppSplit> {
            val timed = nights.filter { it.timed }
            val totals = HashMap<String, Double>()
            for (night in timed) for ((app, minutes) in night.lateAppMinutes) if (app !in ignored) totals.merge(app, minutes, Double::plus)
            // Most minutes first; the name breaks a tie so the same history always gives the same sentence.
            val splits = ArrayList<LateAppSplit>()
            for (app in totals.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key }) {
                val (with, without) = timed.partition { (it.lateAppMinutes[app] ?: 0.0) >= config.lateAppMinutes }
                if (with.size < config.minSplitNights) continue
                if (without.size < config.minSplitNights) continue
                splits += LateAppSplit(
                    packageName = app,
                    withNights = with.size,
                    withMedianOnsetMinute = median(with.map { it.onsetMinute!! }),
                    withMedianSleepMinutes = median(with.map { it.sleepMinutes!! }),
                    withoutNights = without.size,
                    withoutMedianOnsetMinute = median(without.map { it.onsetMinute!! }),
                    withoutMedianSleepMinutes = median(without.map { it.sleepMinutes!! }),
                )
                if (splits.size == config.maxLateApps) break
            }
            return splits
        }

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        private fun sampleSd(values: List<Double>): Double {
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        }
    }
}

/**
 * Whether the laptop has gone quiet. Its use reaches the phone only while three programs run on the laptop; when they
 * do not, nothing fails, the evening figures just stop including the laptop. Twice that went unseen for two weeks
 * (21 Sept to 3 Oct and 3 to 16 Oct 2026), so the phone says it where it is looked at.
 */
object LaptopSilence {
    /** A laptop that is off for a weekend is ordinary; past this the missing days start to bend a week's figures. */
    const val QUIET_AFTER_MS = 3 * 24 * 3_600_000L

    /**
     * The instant the laptop's recorded use reaches, when that is [QUIET_AFTER_MS] or more before [now]; null while it is
     * fresh. [coveredToTs] is the latest of the paired laptops' coverage, or null when no laptop ever sent anything, which is
     * a setup that was never made and not a silence.
     */
    fun since(coveredToTs: Long?, now: Long): Long? = coveredToTs?.takeIf { now - it >= QUIET_AFTER_MS }
}
