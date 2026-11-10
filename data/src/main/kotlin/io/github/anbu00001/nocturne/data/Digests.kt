package io.github.anbu00001.nocturne.data

import io.github.anbu00001.nocturne.core.detect.ChangeSentinel
import io.github.anbu00001.nocturne.core.digest.DigestNight
import io.github.anbu00001.nocturne.core.digest.LaptopSilence
import io.github.anbu00001.nocturne.core.digest.WeekDigest
import io.github.anbu00001.nocturne.core.glance.DefaultPackages
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import java.time.LocalDate

/** One app's foreground time over a query's span. */
data class AppTime(val packageName: String, val foregroundMs: Long)

/**
 * What the home-screen widget and the top of Patterns say: the latest finished night, the week beside the four before
 * it, and whether the laptop has gone quiet. Like [Sentinels], nothing is stored; it is read from what the last recompute
 * wrote, so it can never disagree with the night screens.
 */
class Digests(
    private val db: NocturneDatabase,
    /** Apps on screen without being a choice; calls stay in, a late call is a choice of a kind. */
    private val ignoredPackages: Set<String> = DefaultPackages.TRIVIAL,
    private val config: WeekDigest.Config = WeekDigest.Config(),
) {
    data class Glance(
        /** The latest night with a verdict (times or no sleep), or null before the first one. */
        val night: NightEntity?,
        /** The user's entry for [night], typed or confirmed. */
        val report: SleepReportEntity?,
        val week: WeekDigest?,
        /** The instant the laptop's recorded use reaches, when it has been quiet for days; see [LaptopSilence]. */
        val laptopQuietSince: Long?,
    )

    suspend fun glance(now: Long): Glance {
        val nights = db.sleep().nights()
        val latest = nights.lastOrNull { it.noSleep || it.estimatedSleepOnset != null }
        return Glance(
            night = latest,
            report = latest?.let { db.sleep().report(it.dateOfNight) },
            week = latest?.let { week(nights, LocalDate.parse(it.dateOfNight)) },
            laptopQuietSince = LaptopSilence.since(db.laptop().hosts().maxOfOrNull { it.coveredToTs }, now),
        )
    }

    private suspend fun week(nights: List<NightEntity>, endDate: LocalDate): WeekDigest {
        val from = endDate.minusDays(config.weekNights + config.baselineNights - 1L)
        val span = nights.filter { LocalDate.parse(it.dateOfNight) in from..endDate }.map { night ->
            val onset = night.estimatedSleepOnset
            val wake = night.estimatedWakeTime
            if (onset == null || wake == null) {
                DigestNight(LocalDate.parse(night.dateOfNight), null, null, noSleep = night.noSleep)
            } else {
                DigestNight(
                    date = LocalDate.parse(night.dateOfNight),
                    onsetMinute = ChangeSentinel.onsetMinutesAfterNoon(onset, night.utcOffsetMinutes),
                    sleepMinutes = (wake - onset).toDouble() / LocalClock.MINUTE_MS,
                    lateAppMinutes = db.sessions().appTimeBetween(onset - config.lateHours * LocalClock.HOUR_MS, onset)
                        .associate { it.packageName to it.foregroundMs.toDouble() / LocalClock.MINUTE_MS },
                )
            }
        }
        return WeekDigest.of(span, endDate, ignoredPackages, config)
    }
}

/**
 * One tap on "About right" (the widget, or Last night): the estimate on screen becomes the user's entry for that night,
 * marked as a confirmation. Typing two times was asked 60 nights running and answered twice; SleepTight (Choe et al.,
 * UbiComp 2015) found diary entry from a home-screen widget kept up where opening the app did not.
 */
class SleepConfirmations(private val db: NocturneDatabase) {

    /**
     * False when there is nothing to confirm: no such night, no verdict yet, or the user already entered or confirmed it
     * (their typed times are never overwritten by a tap). The caller re-derives the nights after a true.
     */
    suspend fun confirm(nightDate: String, now: Long): Boolean {
        val night = db.sleep().night(nightDate) ?: return false
        if (night.source != SleepSource.INFERRED || db.sleep().report(nightDate) != null) return false
        val onset = night.estimatedSleepOnset
        val wake = night.estimatedWakeTime
        val report = when {
            night.noSleep -> SleepReportEntity(nightDate, 0, 0, night.utcOffsetMinutes, now, noSleep = true, confirmed = true)
            onset != null && wake != null -> SleepReportEntity(nightDate, onset, wake, night.utcOffsetMinutes, now, confirmed = true)
            else -> return false
        }
        db.sleep().upsertReport(report)
        return true
    }
}
