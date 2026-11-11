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
        fun gapLine(day: String, from: String, to: String, duration: String, atLaptop: String? = null) =
            if (atLaptop == null) "$day, $from to $to ($duration)" else "$day, $from to $to ($duration, $atLaptop at a laptop)"
        fun atLaptop(duration: String) = "$duration of it at a laptop"
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
        const val NOT_EXEMPT =
            "Battery optimisation is on for Nocturne. Android can then hold the end-of-block alarm back, in the strictest case to one alarm a day."
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
                "the line under the bars is sleep, and the line below it is time at a laptop."
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

        /** One tap in place of two time pickers, on Last night and on the home-screen widget. */
        const val ABOUT_RIGHT = "About right"
        const val NOT_RIGHT = "Not right"
        fun confirmed(onset: String, wake: String) = "Asleep about $onset to $wake, as you confirmed"
        const val NO_SLEEP_CONFIRMED = "No sleep this night, as you confirmed"
        const val CONFIRM_NOTE =
            "About right keeps this estimate as your night: it then counts for the evening window and for shifts, where a low-confidence estimate does not."
    }

    /**
     * The last seven nights beside the four weeks before them: the home-screen widget and the top of Patterns. Figures only,
     * each beside the person's own earlier figure and never beside a norm.
     */
    object Week {
        const val SECTION = "This week"
        const val PENDING = "The week in figures appears once four of the last seven nights have sleep times."
        fun onset(nights: Int, onset: String) = "Sleep began around $onset over the last $nights nights with times."
        fun onsetBeside(nights: Int, onset: String, before: String) =
            "Sleep began around $onset over the last $nights nights with times; around $before over the four weeks before."
        fun spread(spread: String) = "Onset varied by $spread from night to night (standard deviation)."
        fun spreadBeside(spread: String, before: String) =
            "Onset varied by $spread from night to night (standard deviation); by $before over the four weeks before."
        fun length(median: String) = "Median sleep $median."
        fun lengthBeside(median: String, before: String) = "Median sleep $median; $before over the four weeks before."
        fun runs(short: Int, long: Int, sleepless: Int): String? {
            val parts = listOfNotNull(
                nights(short, "under 5 h").takeIf { short > 0 },
                nights(long, "of 9 h or more").takeIf { long > 0 },
                nights(sleepless, "without sleep").takeIf { sleepless > 0 },
            )
            return if (parts.isEmpty()) null else parts.joinToString(", ", postfix = ".").replaceFirstChar(Char::uppercase)
        }
        private fun nights(count: Int, what: String) = if (count == 1) "one night $what" else "$count nights $what"

        fun lateApp(app: String, withNights: Int, withOnset: String, withSleep: String, withoutNights: Int, withoutOnset: String, withoutSleep: String) =
            "On the $withNights nights with $app open for 10 minutes or more in the 3 h before sleep, sleep began around $withOnset " +
                "and lasted about $withSleep. On the other $withoutNights, around $withoutOnset and $withoutSleep."
        const val LATE_APP_NOTE =
            "The two apps with the most minutes before sleep over the last five weeks, whatever they show, counted from estimates. " +
                "A difference does not say which leads to which: a late night leaves more room for an app as surely as an app can keep someone up."

        /** The widget's one line. */
        fun short(nights: Int, onset: String, spread: String?) =
            if (spread == null) "$nights nights: onset around $onset" else "$nights nights: onset around $onset, varying by $spread"
        fun shortBefore(onset: String, spread: String?) =
            if (spread == null) "Four weeks before: $onset" else "Four weeks before: $onset, varying by $spread"
    }

    /**
     * Listening, from the phone's player and the laptop's: first as part of the day (how much, and how much of it away from
     * a screen), then beside sleep. Figures and their limits only: nothing here says music did anything. Trials found
     * people rate their sleep a little better with it and did not find they fall asleep sooner (Jespersen et al., Cochrane
     * 2022), and one person's unrandomised nights can show less than a trial. No line names a hormone or a mood: neither is
     * measured here.
     */
    object Music {
        const val SECTION = "Music"
        fun days(days: Int, of: Int, total: String, screenOff: String) =
            "Music on $days of the last $of days: up to $total in all, $screenOff of it with no screen in use."
        fun daysNone(of: Int) = "No music recorded in the last $of days."
        fun daysBefore(days: Int, of: Int, total: String) = "Over the $of days before: on $days days, up to $total in all."
        const val BEFORE_SLEEP = "Beside sleep"
        fun evening(minutes: Int, lastHour: Int, screenOff: Int) =
            "Music for up to $minutes min of the evening, $lastHour of them in the hour before sleep and $screenOff with no screen in use."
        const val EVENING_NONE = "No music recorded this evening."
        fun afterOnset(minutes: Int) = "A player was still running $minutes min into sleep."
        fun side(nights: Int, onset: String, sleep: String, spread: String) =
            "$nights nights with 10 minutes or more of music in the evening: sleep began around $onset, lasted about $sleep, and onset varied by $spread."
        fun otherSide(nights: Int, onset: String, sleep: String, spread: String) =
            "The other $nights nights: around $onset, about $sleep, varying by $spread."
        fun screenOff(music: Int, screenOff: Int) =
            "On the music nights, a median of $music min of music, $screenOff of them with no screen in use."
        fun pending(with: Int, without: Int, need: Int) =
            "Nights with and without evening music are set side by side from $need of each ($with with and $without without so far)."
        const val NONE_RECORDED =
            "No music has been recorded yet. On the phone it is read from Spotify's player; on the laptop, from the player while the laptop's watcher runs."
        const val NOTE =
            "Phone minutes are the time Spotify's player ran, ended where the screen then stayed off for 90 minutes or more; a stop can still be late. " +
                "These are your own nights, not a trial: evenings with music differ from the others in ways nothing here measures, " +
                "so a difference is not an effect. In trials, people with insomnia who listened to music rated their sleep a little better; " +
                "the time they took to fall asleep did not clearly change."
    }

    /**
     * The daily check-in and Patterns' "Rhythm" section. Rules beyond the audit's, from the evidence review of 10 Nov 2026:
     * nothing here reads a mood or a hormone from the data, because the phone measures neither. A research line states a
     * finding about people in a study, with its source, and is never turned into a statement about this person. Links
     * between the check-in and behaviour say "went with", give an interval, and stay hidden until there are 28 days.
     */
    object Mind {
        const val CHECK_IN_TITLE = "How was today"
        const val CHECK_IN_HINT = "One tap: across for unpleasant to pleasant, up for low to high energy."
        const val AXIS_PLEASANT_LOW = "Unpleasant"
        const val AXIS_PLEASANT_HIGH = "Pleasant"
        const val AXIS_ENERGY_LOW = "Low energy"
        const val AXIS_ENERGY_HIGH = "High energy"
        const val TENSION_TITLE = "Tension (optional): calm to tense"
        fun today(pleasant: Int, energy: Int, tension: Int?) =
            if (tension == null) "Today: pleasant $pleasant of 5, energy $energy of 5" else "Today: pleasant $pleasant of 5, energy $energy of 5, tension $tension of 5"
        const val CHECK_IN_NOTE = "Optional, once a day; a later tap replaces it. A tap before 04:00 counts for the day that is ending."
        const val WIDGET_ASK = "How was today"

        // The evening notification (asked for by the owner, 10 Nov 2026): silent, once a day from 20:00, gone once answered.
        const val NOTIFY_CHANNEL = "Evening check-in"
        const val NOTIFY_CHANNEL_DESCRIPTION = "Once a day around 20:00, without sound, until today has an answer."
        const val NOTIFY_TEXT = "Expand and tap a square: across for pleasant, up for energy."
        const val NOTIFY_TENSION = "Saved. Tension, if you like: 1 calm to 5 tense."
        const val NOTIFY_SKIP = "Skip"
        const val NOTIFY_AXES = "Bottom row: low energy. Left: unpleasant, right: pleasant."
        const val NOTIFY_SETTING_ON = "Evening check-in notification: on, around 20:00, silent."
        const val NOTIFY_SETTING_OFF = "Evening check-in notification: off."
        const val NOTIFY_TURN_OFF = "Turn off"
        const val NOTIFY_TURN_ON = "Turn on"

        const val SECTION = "Rhythm"
        fun jumps(pairs: Int, median: String, big: Int) =
            "Sleep onset moved by a median of $median from one night to the next ($pairs pairs of nights in the last five weeks); on $big of them by more than 2 h."
        fun jetlag(duration: String) = "Sleep on free nights and on work nights sits $duration apart at its midpoint (social jetlag, 28 nights)."
        const val JETLAG_RESEARCH =
            "What research links this to: in one cross-sectional study of 145 adults, social jetlag of 2 h or more " +
                "has been linked with higher cortisol than 1 h or less (Rutters et al., J Biol Rhythms 2014). " +
                "That compares groups of people; it is not a reading of yours."
        fun alarmsEarly(mornings: Int, nights: Int, alarms: String, span: String) =
            "After Sunday to Wednesday nights: alarms on $mornings of $nights mornings, a median of $alarms a morning, $span from the first to the last."
        fun alarmsOther(mornings: Int, nights: Int, alarms: String, span: String) =
            "After the other nights: alarms on $mornings of $nights mornings, a median of $alarms a morning, $span from the first to the last."
        fun alarmsOtherNone(nights: Int) = "After the other $nights nights: no alarms."
        fun musicWhen(morning: String, afternoon: String, evening: String) =
            "Music over the last five weeks: up to $morning before noon, $afternoon in the afternoon, $evening from 18:00 on."
        const val MUSIC_RESEARCH =
            "What research links this to: in lab studies, music people enjoy " +
                "has been shown to release dopamine at its peak moments (Salimpoor et al., Nat Neurosci 2011; Ferreri et al., PNAS 2019), " +
                "and across 104 trials listening to music lowered stress measures a little to moderately, " +
                "about d = 0.35 for hormone measures, mostly cortisol (de Witte et al., Health Psychol Rev 2020). " +
                "Those are findings about music and about groups of people, many of them patients; they say nothing about any amount of listening here."
        fun pickups(week: String, before: String) = "The phone was unlocked a median of $week times a day over the last 7 days; $before over the four weeks before."
        const val PICKUPS_NOTE = "A count of unlocks, and nothing more is read into it. The apps open before sleep are under This week."

        const val LINKS_TITLE = "The check-in beside the data"
        fun linksWithheld(have: Int, need: Int) =
            "How the check-in moves with sleep and music is shown from $need days that have both a check-in and a sleep estimate ($have of $need days so far)."
        fun checkIns(count: Int, pleasant: String, energy: String) = "$count check-ins: median pleasant $pleasant of 5, median energy $energy of 5."
        fun linkSleepEnergy(slope: String, low: String, high: String, pairs: Int) =
            "An hour more sleep than the night before went with an energy rating $slope points different from the day before (interval $low to $high, $pairs pairs of days)."
        fun linkSleepPleasant(slope: String, low: String, high: String, pairs: Int) =
            "An hour more sleep than the night before went with a pleasantness rating $slope points different (interval $low to $high, $pairs pairs of days)."
        fun linkMusicPleasant(slope: String, low: String, high: String, pairs: Int) =
            "An hour more music than the day before went with a pleasantness rating $slope points different (interval $low to $high, $pairs pairs of days)."
        const val LINKS_NOTE =
            "Day-to-day differences in your own days, three comparisons chosen before any answer existed. An interval that spans zero shows no link. " +
                "None of it shows what leads to what, and days without a check-in are left out."
    }

    /** The home-screen widget. It is looked at, never pushed: nothing here lights the screen or makes a sound. */
    object Widget {
        const val EMPTY = "No night with a sleep estimate yet."
        fun estimated(onset: String, wake: String, confidence: String) = "Asleep about $onset to $wake ($confidence confidence)"
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

    /** Phase 3.5: the drift sentinel. A posterior, never a verdict (analytics spec, pitfall 6). */
    object Shifts {
        const val SECTION = "Shifts"
        fun onsetWithheld(have: Int, need: Int) = "Shifts in sleep onset are looked for from $need nights with an onset ($have so far)."
        fun sriWithheld(have: Int, need: Int) = "Shifts in weekly sleep regularity are looked for from $need weeks with an SRI ($have so far)."
        fun onsetSteady(probability: String) = "No shift in sleep onset over the last 6 weeks. P(change) = $probability."
        fun sriSteady(probability: String) = "No shift in weekly sleep regularity over the last 6 weeks. P(change) = $probability."
        fun onsetShift(around: String, before: String, after: String, probability: String, nights: Int) =
            "Your sleep onset appears to have shifted around $around, from about $before to about $after. " +
                "P(change) = $probability, based on $nights nights since."
        fun sriShift(around: String, before: String, after: String, probability: String, weeks: Int) =
            "Weekly sleep regularity (SRI) appears to have shifted around the week ending $around, from $before to $after. " +
                "P(change) = $probability, based on $weeks weeks since."
        const val NOTE =
            "A probability from a changepoint model (Bayesian online changepoint detection), not a finding. " +
                "It looks for a lasting shift, so one late night does not count."
    }

    /** Phase 4: laptop use sent from ActivityWatch on the laptop. */
    object Laptop {
        const val SECTION = "Laptop"
        const val NONE =
            "No laptop has sent its use. On the laptop, with the phone on USB: python3 tools/activitywatch/nocturne_aw.py sync"
        fun host(host: String, sent: String, upTo: String, periods: Int) = "$host: sent $sent, use recorded up to $upTo ($periods periods in that send)"
        fun panel(widthMm: Int, heightMm: Int, peakNits: String) = "Panel $widthMm x $heightMm mm, $peakNits nits at full backlight"
        const val NOTE =
            "Time at a laptop counts as awake for sleep estimates, and its screen adds to the modelled evening light. " +
                "What the screen shows is not recorded, so the light range covers dark and light pages."
        fun eveningMinutes(minutes: Int) = "$minutes of those minutes at a laptop, its screen included"

        /** Phase 6: the laptop was on and nothing recorded its use, so its share of the evening is a floor. */
        fun eveningGap(minutes: Int) =
            "The laptop was on for $minutes minutes of this evening with nothing recording it, so this figure may be low."

        /** The laptop's programs are not running: said on the widget and on Tonight, because twice it went unseen for two weeks. */
        fun quiet(since: String) = "No laptop use has arrived since $since."
        const val QUIET_DETAIL =
            "Evening screen time, light and sleep estimates since then leave the laptop out. Its recording programs start only when " +
                "asked; to start them at every login, run this once on the laptop: tools/activitywatch/install-services.sh"

        /** Phase 5: the phone and the laptop exchange over the home Wi-Fi, started by the phone. */
        const val SYNC_TITLE = "Wi-Fi sync"
        const val SYNC_NOT_PAIRED =
            "No laptop is paired. On the laptop, with the phone on USB: python3 tools/activitywatch/nocturne_aw.py pair"
        fun syncPaired(name: String) =
            "Paired with $name. About every 15 minutes on Wi-Fi the phone sends its sessions and nights to it and takes the laptop's use in reply."
        const val SYNC_NOTE =
            "Only the paired laptop is contacted: its certificate is pinned, each request is signed with a secret from pairing, " +
                "and addresses outside a home or office network are refused. The laptop answers only while it is on."
        const val SYNC_NEVER = "No exchange yet."
        fun syncOk(at: String, spans: Int, nights: Int) = "Last exchange $at: $spans laptop periods received, $nights nights re-derived"
        fun syncTried(at: String, result: String) = "Last try $at: $result"
        const val SYNC_UNREACHABLE = "the laptop did not answer (it is off, or on another network)"
        const val SYNC_OFF_WIFI = "the phone is not on Wi-Fi"
        const val SYNC_WRONG_CERT = "something else answered at the laptop's address; pair the laptop once more over USB"
        const val SYNC_REJECTED = "the laptop did not accept the secret; pair the laptop once more over USB"
        const val SYNC_CLOCK = "the phone's and the laptop's clocks differ by more than five minutes"
        const val SYNC_NO_AW = "the laptop is on but ActivityWatch is not running there"
        fun syncFailed(detail: String) = "the exchange broke off: $detail"
        const val SYNC_NOW = "Sync now"
        const val SYNC_RUNNING = "Syncing"
        const val SYNC_FORGET = "Forget this laptop"
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
        const val EXPORT_ALL = "Export all data"
        const val EXPORT_ALL_NOTE =
            "One zip file with every table as a CSV: events, sessions, nights, light, charging, sleep times you entered, " +
                "gap labels (whether a note exists, not its text), focus blocks, laptop use, and this phone's model, " +
                "Android version and permissions. It stays on this phone until you share it."
        fun exportedAll(rows: Long, tables: Int) = "Exported $rows rows in $tables tables."
        fun exportFailed(reason: String) = "The export did not finish: $reason"
        const val WIPE = "Delete all data"
        const val WIPE_TITLE = "Delete all data?"
        const val WIPE_BODY =
            "This removes every harvested event, light sample, derived session and night, laptop use sent to it, and any " +
                "sleep times, gap labels and focus blocks you entered, " +
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
