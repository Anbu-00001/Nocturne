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

    /** "22:05 to 09:00" */
    fun clockRange(from: String, to: String) = "$from to $to"

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
        fun screenTime(duration: String, window: String) = "$duration with the screen on, $window"
        fun sessions(count: Int) = "$count screen sessions"
        fun glanceMedian(median: String, nights: Int) = "Median over the previous $nights nights: $median glances"
        const val MEDIAN_PENDING = "A median appears once 7 nights are recorded."
        const val WINDOW_PROVISIONAL =
            "The 21:00 to 07:00 window is a placeholder until 7 nights have a confident sleep estimate."
        fun windowPersonal(nights: Int) =
            "The evening window starts 3 h before your usual sleep onset and ends at your usual wake, from $nights nights."
        const val LEGEND =
            "Bars are screen sessions and ticks above them are glances. The shaded band is the evening window, " +
                "the line under the bars is sleep."
        const val EARLIER = "Earlier"
        const val LATER = "Later"

        fun sessionLine(time: String, kind: String, duration: String, app: String?, trigger: String?) = buildString {
            append("$time  $kind, $duration")
            if (app != null) append(", mostly $app")
            if (trigger != null) append(", woken by $trigger")
        }
    }

    object Sleep {
        fun estimated(onset: String, wake: String) = "Asleep about $onset to $wake"
        fun reported(onset: String, wake: String) = "Asleep $onset to $wake, as you entered it"
        fun basis(confidence: String) = "Estimated from when the phone went quiet, $confidence confidence"
        const val CONFIDENCE_LOW = "low"
        const val CONFIDENCE_MEDIUM = "medium"
        const val CONFIDENCE_HIGH = "high"
        fun interruptions(count: Int) = when (count) {
            0 -> "No phone use between onset and wake"
            1 -> "Phone used once between onset and wake"
            else -> "Phone used $count times between onset and wake"
        }
        const val NONE =
            "No sleep estimate for this night. A night is estimated once the harvested history reaches past 06:00 the next morning."
        const val ENTER = "Enter your times"
        const val CORRECT = "Correct these times"
        const val CHANGE = "Change your times"
        const val REMOVE = "Remove your times"
        const val ONSET_TITLE = "Fell asleep around"
        const val WAKE_TITLE = "Woke around"
        const val ENTRY_NOTE = "Optional. Your times replace the estimate for this night, and after three nights they tune the others."
        const val NEXT = "Next"
        const val SAVE = "Save"
        const val CANCEL = "Cancel"
    }

    object Patterns {
        const val GLANCES_PER_NIGHT = "Glances per night"
        const val EVENING_MINUTES = "Minutes on screen in the evening window"
        const val EMPTY =
            "Patterns fill in as nights are harvested. The first run copies up to 10 days of history Android already holds."
        fun range(nights: Int, first: String, last: String) = "$nights nights, $first to $last"
        const val SLEEP = "Sleep, night by night"
        const val SLEEP_NOTE = "Each bar runs from sleep onset down to wake. Solid bars are times you entered, lighter bars are estimates. Tap a bar for its times."
        const val SLEEP_EMPTY = "Sleep bars appear once nights have an estimate."
        fun sleepDetail(night: String, onset: String, wake: String, source: String) = "$night: $onset to $wake, $source"
        const val SOURCE_ESTIMATED = "estimated"
        const val SOURCE_REPORTED = "entered by you"
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
        const val RECOMPUTE_ALL = "Recompute all sessions and nights"
        fun recomputed(sessions: Int) = "Recomputed $sessions sessions and every night."

        const val SLEEP = "Sleep"
        fun habitual(onset: String, wake: String, nights: Int) = "Usual sleep onset $onset, usual wake $wake, from $nights nights"
        const val HABITUAL_PENDING =
            "Usual sleep times and a personal evening window appear once 7 nights have a confident estimate or times you entered."
        fun eveningWindow(window: String) = "Evening window: $window"
        fun screenTimeout(duration: String) = "Screen-off timeout used for sleep onset: $duration"
        fun reports(count: Int) = "Nights with times you entered: $count"

        const val DATA = "Data"
        const val EXPORT_CSV = "Export raw events as CSV"
        fun exported(rows: Long) = "Exported $rows rows."
        const val WIPE = "Delete all data"
        const val WIPE_TITLE = "Delete all data?"
        const val WIPE_BODY =
            "This removes every harvested event, derived session and night, and any sleep times you entered, from this " +
                "phone. Android still holds roughly the last 10 days, and the next harvest copies those back."
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
