package io.github.anbu00001.nocturne.tone

import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.metrics.MetricKey
import io.github.anbu00001.nocturne.core.reflect.GapLabel

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
        const val TONIGHT = "Tonight"
        const val LAST_NIGHT = "Last night"
        const val PATTERNS = "Patterns"
        const val FOCUS = "Focus"
        const val SETTINGS = "Settings"
    }

    /** Spec §7: one question, four taps, no text field unless asked for. */
    object Reflect {
        fun phoneDown(from: String, to: String) = "Phone was down $from to $to"
        fun label(label: GapLabel): String = when (label) {
            GapLabel.DEEP_WORK -> "Deep work"
            GapLabel.LIGHT_WORK -> "Light work"
            GapLabel.REST -> "Rest"
            GapLabel.NOT_SURE -> "Not sure"
        }
        const val DISMISS = "Dismiss"
        fun labelled(label: String) = "Labelled $label"
        const val ADD_NOTE = "Add a note"
        const val NOTE_FIELD = "Note"
        const val SAVE_NOTE = "Save note"
        const val DONE = "Done"

        const val WEEK = "Phone-down gaps, last 7 days"
        const val WEEK_NOTE =
            "A gap is an hour or more without unlocking the phone, outside sleep and the evening window. Lock-screen glances do not end one."
        fun labelledTime(label: String, duration: String) = "$label: $duration"
        fun unlabelled(count: Int) = if (count == 1) "1 gap has no label" else "$count gaps have no label"
        const val ALL_LABELLED = "Every gap this week has a label or was dismissed."
        const val SHOW_UNLABELLED = "Label them"
        const val HIDE_UNLABELLED = "Hide"
        fun gapLine(day: String, from: String, to: String, duration: String) = "$day, $from to $to ($duration)"
    }

    /** Spec §7, "Pomodoro / focus timer". Interruptions are reported flatly, without commentary. */
    object Focus {
        const val FOCUS_LENGTH = "Focus length"
        const val BREAK_LENGTH = "Break length"
        fun minutes(count: Int) = "$count min"
        const val START_FOCUS = "Start focus"
        const val START_BREAK = "Start break"
        const val STOP = "Stop"
        const val FOCUS_RUNNING = "Focus"
        const val BREAK_RUNNING = "Break"
        fun endsAt(clock: String) = "Ends at $clock"
        const val INEXACT =
            "Android has not allowed exact alarms for Nocturne, so the end of a block may be signalled a few minutes late."

        fun unlocks(count: Int) = if (count == 1) "1 unlock" else "$count unlocks"
        fun week(blocks: Int, unlocks: Int) =
            (if (blocks == 1) "Last 7 days: 1 focus block, " else "Last 7 days: $blocks focus blocks, ") + "${unlocks(unlocks)} during them"
        const val HISTORY = "Recent blocks"
        const val NO_BLOCKS = "No focus blocks yet."
        fun block(day: String, time: String, duration: String, unlocks: Int, completed: Boolean) =
            "$day $time, $duration" + (if (completed) "" else ", stopped early") + ", ${unlocks(unlocks)}"
        const val COUNT_NOTE =
            "Unlocks come from Android's usage events and are recounted at every harvest, so a count can change shortly after a block ends."

        const val CHANNEL_NAME = "Focus timer"
        const val CHANNEL_DESCRIPTION = "The end of a focus block or break you started."
        fun focusEnded(duration: String, unlocks: Int) = "Focus block of $duration ended, ${unlocks(unlocks)} during it"
        fun breakEnded(duration: String) = "Break of $duration ended"
    }

    object Tonight {
        fun windowStartsIn(time: String, wait: String) = "Evening window starts at $time, in $wait"
        fun windowOpen(since: String, until: String) = "In the evening window since $since, until $until"
        fun atEyes(mid: String) = "About $mid lux melanopic at your eyes now"
        fun screenAtEyes(mid: String) = "About $mid lux melanopic from the screen now"
        fun range(low: String, high: String) = "Modelled range $low to $high lux"
        const val UNDER_TARGET = "Under the evening target of 10 lux"
        const val OVER_TARGET = "Above the evening target of 10 lux"
        const val SPANS_TARGET = "The range spans the evening target of 10 lux"
        const val MEASURED = "Room light from the sensor in the last minute; screen light from brightness and display mode."
        const val NOT_MEASURED =
            "Room light is not being measured, so this is the screen alone, from brightness and display mode. A lit room adds to it."
        const val CHART_NOTE = "Log scale from 0.1 to 1000 lux. The bar is the modelled range, the line marks 10 lux."
        fun glances(count: Int) = if (count == 1) "1 glance so far tonight" else "$count glances so far tonight"
        const val GLANCES_PENDING = "Glances appear after the next harvest."
        fun suppression(mid: String, low: String, high: String) = "Modelled melatonin suppression so far: $mid% (range $low to $high%)"
        const val SUPPRESSION_PENDING = "Suppression so far appears after the next harvest, once light has been measured this evening."
        const val LIGHT_OFF = "Light measurement is off, so this evening's suppression is not modelled."
        const val MODELLED_NOTE =
            "Light and melatonin numbers are modelled from assumptions about viewing distance, screen content and the room. " +
                "Sensitivity to evening light differs several-fold between people."
    }

    object Light {
        const val CHANNEL_NAME = "Light measurement"
        const val CHANNEL_DESCRIPTION = "Shown while Nocturne can read the light sensor with the screen on."
        const val NOTIFICATION_TITLE = "Measuring light"
        const val NOTIFICATION_TEXT = "Reads the light sensor only while the screen is on."

        const val SECTION = "Light"
        const val START = "Start measuring light"
        const val STOP = "Stop measuring light"
        const val ENABLED = "Light measurement is on"
        const val DISABLED = "Light measurement is off. Usage is recorded either way."
        fun sampler(running: Boolean) = if (running) "Sampler: running" else "Sampler: not running"
        fun lastSample(time: String, count: Int) = "Last sample $time, $count samples in the last 24 h"
        const val NO_SAMPLES = "No light samples yet."
        fun refused(time: String, reason: String) = "Android refused a background start at $time ($reason). Opening Nocturne starts it."
        fun sensor(name: String, step: String, max: String) = "Sensor: $name, steps of $step lux, up to $max lux"
        const val NO_SENSOR = "No light sensor reported, so room light uses the evening prior."
        fun display(profile: String, minNits: String, peakNits: String) = "Screen model: $profile, $minNits to $peakNits nits"
        const val PROFILE_A18 = "Oppo A18, read from its display config"
        const val PROFILE_GENERIC = "generic, uncalibrated"
        fun warmFilter(state: String) = "Warm display filter now: $state"
        const val STATE_ON = "on"
        const val STATE_OFF = "off"
        const val STATE_UNREADABLE = "not readable"
        const val NOTIFICATION_PERMISSION = "Notification permission"
        const val ALLOW_NOTIFICATION = "Show the sampler's notification"
        const val NOTIFICATION_NOTE =
            "Optional. Without it the sampler still runs; Android lists it under active apps instead of in the notification shade."

        fun nightSuppression(mid: String, low: String, high: String) =
            "Modelled melatonin suppression before sleep: $mid% (range $low to $high%)"
        fun coverage(measured: Int, screen: Int) = "Light measured for $measured of $screen minutes with the screen on"
        const val DURATION_CLAMPED =
            "The exposure fell outside the 30 min to 4 h the suppression model was fitted on, so the nearest limit was used."
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
        const val DIDNT_SLEEP = "I did not sleep"
        const val NO_SLEEP_REPORTED = "No sleep this night, as you entered it"
        const val NO_SLEEP_FOUND = "No sleep found this night"
        fun noSleepBasis(confidence: String) =
            "The quiet stretches look more like time away from the phone than sleep, $confidence confidence"
        const val LATENCY_TITLE = "Time to fall asleep (optional)"
        val LATENCY_BANDS = listOf("15 min or less", "16 to 30 min", "31 to 60 min", "Over 60 min")
        fun noSleepDetail(night: String, source: String) = "$night: no sleep, $source"
        const val CHART_NO_SLEEP = "A ring marks a night with no sleep."
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

        const val REGULARITY = "Regularity"
        const val REGULARITY_NOTE =
            "Each figure waits for its window to hold enough recorded nights. Sleep figures come from the sleep estimates " +
                "and your entered times; rhythm figures come from screen use, not movement, so they are not comparable " +
                "with actigraphy studies."
        fun regularityWindow(nights: Int) = "Last $nights nights"
        const val SLEEP_TIMING = "Sleep timing"
        const val SCREEN_RHYTHM = "Screen-use rhythm"

        /** Minute-valued figures to the whole minute: a regularity window cannot resolve seconds. */
        fun roundedMinutes(minutes: Double): String =
            if (minutes < 1) "under 1m" else duration(kotlin.math.round(minutes).toLong() * 60_000)

        fun sri(value: String) = "Sleep Regularity Index: $value, where 100 is the same sleep every day and 0 no pattern"
        fun onsetSpread(duration: String) = "Sleep onset spread: $duration (standard deviation)"
        fun socialJetlag(duration: String) = "Free-night and work-night midsleep are $duration apart"
        fun phaseDeviation(hours: String) = "Composite phase deviation: $hours h a night on average"
        fun stability(value: String) = "Day-to-day stability (IS): $value of 1"
        fun fragmentation(value: String) = "Hour-to-hour fragmentation (IV): $value, where 0 is smooth and 2 is noise"
        fun quietest(start: String) = "Quietest 5 hours start around $start"
        fun busiest(start: String) = "Busiest 10 hours start around $start"
        fun amplitude(value: String) = "Relative amplitude (RA): $value of 1"
        fun functionIndex(value: String) = "Circadian function index: $value of 1"

        const val SLEEP_CHECK = "Screen rhythm beside the sleep estimates"
        fun quietestAsleep(percent: String) = "Asleep for $percent of the quietest 5 hours"
        fun stabilityFromSleep(value: String) = "Stability (IS) from sleep times alone: $value of 1"
        fun fragmentationFromSleep(value: String) = "Fragmentation (IV) from sleep times alone: $value"
        const val SCREEN_RHYTHM_NOT_REST =
            "The quietest screen hours were mostly awake time in this window, so the screen-use figures describe phone habits more than rest."

        fun metricName(key: MetricKey): String = when (key) {
            MetricKey.SRI -> "The Sleep Regularity Index"
            MetricKey.ONSET_SD -> "Sleep onset spread"
            MetricKey.SOCIAL_JETLAG -> "Social jetlag"
            MetricKey.CPD -> "Composite phase deviation"
            MetricKey.IS -> "Stability (IS)"
            MetricKey.IV -> "Fragmentation (IV)"
            MetricKey.L5 -> "The quietest 5 hours"
            MetricKey.M10 -> "The busiest 10 hours"
            MetricKey.RA -> "Relative amplitude"
            MetricKey.CFI -> "The circadian function index"
            MetricKey.IS_SLEEP -> "Stability from sleep times"
            MetricKey.IV_SLEEP -> "Fragmentation from sleep times"
            MetricKey.L5_ASLEEP -> "The sleep check"
        }
        fun needsNights(name: String, have: Int, need: Int) = "$name needs $need recorded nights in this window; $have so far"
        fun needsPairs(name: String, have: Int, need: Int) = "$name needs $need back-to-back pairs of nights; $have so far"
        fun needsCoverage(name: String, have: Int, need: Int) = "$name needs $need% of the window recorded; $have% so far"
        fun needsDayTypes(name: String) = "$name needs both free nights and work nights in the window"
        fun noVariation(name: String) = "$name is undefined while nothing in the window varies"
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
            "This removes every harvested event, light sample, derived session and night, and any sleep times, gap labels " +
                "and focus blocks you entered, " +
                "from this phone. Android still holds roughly the last 10 days of events, and the next harvest copies those " +
                "back. Light samples cannot be recovered."
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
