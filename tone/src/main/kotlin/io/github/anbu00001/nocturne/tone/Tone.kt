package io.github.anbu00001.nocturne.tone

import io.github.anbu00001.nocturne.core.glance.SessionKind

/**
 * Every user-facing sentence in Nocturne, in one place so it can be audited as a unit
 * (spec §1.2, correction 1).
 *
 * The data is merciless; the copy is not. Numbers describe the behaviour and never the person:
 * no adjectives about the user, no exclamation marks, no emoji. ToneAuditTest enforces the
 * mechanical part of that rule.
 */
object Tone {
    const val APP_NAME = "Nocturne"

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            ms < 1_000 -> "under 1s"
            ms < 60_000 -> "${ms / 1000}s"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    fun kindLabel(kind: SessionKind): String = when (kind) {
        SessionKind.GLANCE_NO_UNLOCK -> "Lock-screen glance"
        SessionKind.GLANCE_UNLOCKED -> "Unlocked glance"
        SessionKind.LOCKED_EXTENDED -> "Screen on, locked"
        SessionKind.SHORT -> "Short session"
        SessionKind.EXTENDED -> "Extended session"
    }

    object Nav {
        const val LAST_NIGHT = "Last night"
        const val PATTERNS = "Patterns"
        const val SETTINGS = "Settings"
    }

    object Onboarding {
        const val TITLE = "Before anything is recorded"
        const val STEP_USAGE = "Usage access"
        const val USAGE_ACCESS_BODY =
            "Nocturne reads the screen-on, unlock and app events Android already logs, and copies them " +
                "into a database on this phone before Android deletes them after about 10 days. " +
                "Nothing leaves the phone: the app has no internet permission."
        const val USAGE_ACCESS_BUTTON = "Open usage access settings"
        const val USAGE_ACCESS_GRANTED = "Usage access granted."
        const val STEP_BATTERY = "Background running"
        const val BATTERY_BODY =
            "Android and ColorOS stop background work to save battery. Nocturne wakes for a moment every " +
                "15 minutes. If it is kept from running for more than about 10 days, the oldest of those days " +
                "cannot be recovered."
        const val BATTERY_BUTTON = "Allow background running"
        const val BATTERY_GRANTED = "Battery optimisation is off for Nocturne."
        const val VENDOR_TITLE = "ColorOS settings"
        val VENDOR_STEPS = listOf(
            "App info for Nocturne, Battery usage: allow background activity and auto launch.",
            "Recent apps: open the menu on Nocturne's card and lock it.",
        )
        const val VENDOR_LINK_LABEL = "Steps for other phones at dontkillmyapp.com"
        const val VENDOR_LINK = "https://dontkillmyapp.com/oppo"
        const val OPEN_APP_INFO = "Open app info"
        const val CONTINUE = "Continue"
    }

    object LastNight {
        fun nightTitle(date: String) = "Night of $date"
        const val EMPTY = "No harvested sessions yet. The first harvest runs as soon as usage access is granted."
        fun glances(counted: Int, withoutUnlock: Int) = "$counted glances, $withoutUnlock without unlocking"
        const val PROVISIONAL_WINDOW_LABEL = "21:00 to 07:00"
        fun screenTime(duration: String, window: String) = "$duration with the screen on, $window"
        fun sessions(count: Int) = "$count screen sessions"
        fun glanceMedian(median: String, nights: Int) = "Median over the previous $nights nights: $median glances"
        const val MEDIAN_PENDING = "A median appears once 7 nights are recorded."
        const val WINDOW_PROVISIONAL =
            "The 21:00 to 07:00 window is a placeholder until there are 7 nights to estimate sleep onset from."
        const val LEGEND = "Bars are screen sessions. Ticks above them are glances. The shaded band is the evening window."
        const val EARLIER = "Earlier"
        const val LATER = "Later"

        fun sessionLine(time: String, kind: String, duration: String, app: String?, trigger: String?) = buildString {
            append("$time  $kind, $duration")
            if (app != null) append(", mostly $app")
            if (trigger != null) append(", woken by $trigger")
        }
    }

    object Patterns {
        const val GLANCES_PER_NIGHT = "Glances per night"
        const val EVENING_MINUTES = "Minutes on screen, 21:00 to 07:00"
        const val EMPTY =
            "Patterns fill in as nights are harvested. The first run copies up to 10 days of history Android already holds."
        fun range(nights: Int, first: String, last: String) = "$nights nights, $first to $last"
    }

    object Settings {
        const val PERMISSIONS = "Permissions"
        const val USAGE_ACCESS = "Usage access"
        const val BATTERY_EXEMPT = "Battery optimisation exemption"
        const val GRANTED = "granted"
        const val MISSING = "missing"
        fun status(name: String, state: String) = "$name: $state"

        const val HARVESTER = "Harvester"
        const val NEVER_RUN = "The harvester has not run yet."
        fun lastRun(time: String, outcome: String) = "Last run $time: $outcome"
        fun daysSinceSuccess(days: Long) = "$days days since the last successful harvest"
        fun gapWarning(days: Long) =
            "No successful harvest for $days days. Android keeps about 10 days of events, so unharvested days " +
                "older than that are lost."
        fun workState(state: String) = "Background job: $state"
        fun nextRun(minutes: Long) = "Next scheduled run in about $minutes min"
        fun counts(rawEvents: Long, sessions: Long) = "$rawEvents raw events, $sessions sessions"
        const val HARVEST_NOW = "Harvest now"

        const val CLASSIFIER = "Classifier"
        fun unlockEvidence(fromKeyguard: Boolean) =
            if (fromKeyguard) "Unlocks read from keyguard events" else "No keyguard events seen; unlocks inferred from apps"
        fun thresholds(lockedSeconds: Long, unlockedSeconds: Long, shortMinutes: Long) =
            "Glance limits: under ${lockedSeconds}s locked, under ${unlockedSeconds}s unlocked. " +
                "Short sessions run up to $shortMinutes min."
        const val LOCKED_WAKE_PACKAGES = "Apps seen on locked wakes, last 7 days"
        const val NONE_YET = "None in the last 7 days."
        fun packageCount(packageName: String, count: Int) = "$packageName: $count"
        const val RECOMPUTE_ALL = "Recompute all sessions"
        fun recomputed(sessions: Int) = "Recomputed $sessions sessions."

        const val DATA = "Data"
        const val EXPORT_CSV = "Export raw events as CSV"
        fun exported(rows: Long) = "Exported $rows rows."
        const val WIPE = "Delete all data"
        const val WIPE_TITLE = "Delete all data?"
        const val WIPE_BODY =
            "This removes every harvested event and derived session from this phone. Android still holds " +
                "roughly the last 10 days, and the next harvest copies those back."
        const val CANCEL = "Cancel"
        const val DELETE = "Delete"
    }

    object HarvestOutcome {
        const val OK = "ok"
        const val USER_LOCKED = "skipped, phone not unlocked since boot"
        const val NO_ACCESS = "skipped, usage access missing"
        const val FAILED = "failed"
    }

    object Trigger {
        const val NOTIFICATION = "a notification"
        const val ALARM = "an alarm"
        const val CALL = "a call"
    }
}
