package io.github.anbu00001.nocturne.core.glance

import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.event.UsageEvent

/**
 * Streaming screen-session builder and glance classifier (spec §6.5).
 *
 * Feed events in timestamp order. SCREEN_INTERACTIVE opens a session; SCREEN_NON_INTERACTIVE or
 * DEVICE_SHUTDOWN closes it. Keyguard state carries across sessions (a re-wake inside the
 * lock delay is already unlocked), so callers must start feeding from before the last keyguard
 * transition that precedes the first session they care about.
 *
 * Streaming rather than list-in/list-out so recomputeAll() can walk years of raw_events in pages.
 */
class SessionDeriver(private val config: ClassifierConfig = ClassifierConfig()) {

    private var keyguard = KeyguardState.UNKNOWN
    private var open: OpenSession? = null
    private var lastNotificationTs: Long? = null
    private var lastOffScreenResume: UsageEvent? = null

    /** Start of the session still in progress when the stream ran out, or null. */
    val openSessionStart: Long? get() = open?.startTs

    /** Consumes one event; returns the session it closed, if any. */
    fun feed(event: UsageEvent): ClassifiedSession? {
        val session = open
        when (event.type) {
            EventType.SCREEN_INTERACTIVE -> {
                open = OpenSession(event.timestamp, keyguard, lastNotificationTs, lastOffScreenResume)
                lastOffScreenResume = null
                // A second wake with no sleep in between: SCREEN_NON_INTERACTIVE was lost.
                return session?.let { classify(it, it.lastSeenTs, endInferred = true) }
            }
            EventType.SCREEN_NON_INTERACTIVE -> return endOpen(event.timestamp, endInferred = false)
            EventType.DEVICE_SHUTDOWN -> {
                keyguard = KeyguardState.UNKNOWN
                return endOpen(event.timestamp, endInferred = false)
            }
            EventType.DEVICE_STARTUP -> {
                keyguard = KeyguardState.UNKNOWN
                return session?.let { endOpen(it.lastSeenTs, endInferred = true) }
            }
            EventType.KEYGUARD_SHOWN -> {
                keyguard = KeyguardState.SHOWING
                session?.onKeyguard(event)
            }
            EventType.KEYGUARD_HIDDEN -> {
                keyguard = KeyguardState.HIDDEN
                session?.onKeyguard(event)
            }
            EventType.NOTIFICATION_INTERRUPTION -> lastNotificationTs = event.timestamp
            EventType.ACTIVITY_RESUMED ->
                if (session != null) session.onResumed(event) else lastOffScreenResume = event
            EventType.ACTIVITY_PAUSED, EventType.ACTIVITY_STOPPED ->
                if (session != null) {
                    session.onPaused(event)
                } else if (lastOffScreenResume?.packageName == event.packageName) {
                    lastOffScreenResume = null
                }
            EventType.USER_INTERACTION -> session?.touch(event.timestamp)
        }
        return null
    }

    private fun endOpen(endTs: Long, endInferred: Boolean): ClassifiedSession? {
        val session = open ?: return null
        open = null
        return classify(session, endTs, endInferred)
    }

    private fun classify(s: OpenSession, rawEndTs: Long, endInferred: Boolean): ClassifiedSession {
        val endTs = maxOf(rawEndTs, s.startTs)
        val foreground = s.closeForeground(endTs)
        val unlocked = when (config.unlockEvidence) {
            UnlockEvidence.KEYGUARD_EVENTS -> s.hiddenTs != null || !s.lockedAtStart(config.keyguardSettleMs)
            UnlockEvidence.ACTIVITY_INFERRED -> foreground.keys.any { it !in config.overLockscreenPackages }
        }
        // Behind the lock screen, ColorOS still resumes the last-used app on wake (seen on a real device).
        // On a wake that never unlocked, only apps that can draw over the lock screen were actually seen.
        val seen = if (unlocked) foreground.keys else foreground.keys.filter { it in config.overLockscreenPackages }
        val realApps = seen.filter { it !in config.trivialPackages }
        val durationMs = endTs - s.startTs
        return ClassifiedSession(
            startTs = s.startTs,
            endTs = endTs,
            kind = kindOf(durationMs, unlocked, realApps.size, config),
            unlocked = unlocked,
            trigger = triggerOf(s),
            dominantPackage = realApps.ifEmpty { seen }.maxByOrNull { foreground.getValue(it) },
            appCount = realApps.size,
            foregroundMs = foreground,
            endInferred = endInferred,
        )
    }

    private fun triggerOf(s: OpenSession): WakeTrigger {
        val window = config.triggerWindowMs
        val early = buildList {
            s.offScreenResume?.takeIf { s.startTs - it.timestamp in 0..window }?.let(::add)
            // A re-wake inside the lock delay resumes whatever was last open; that is not an alarm or call.
            if (s.lockedAtStart(config.keyguardSettleMs)) {
                s.resumes.filterTo(this) { e ->
                    e.timestamp - s.startTs <= window && s.hiddenTs.let { it == null || e.timestamp < it }
                }
            }
        }
        // ColorOS logs the last-used app's resume just before KEYGUARD_HIDDEN on a fingerprint wake
        // (seen on a real device), so a clock that returns right before an unlock is not an alarm ringing.
        fun stayedLocked(e: UsageEvent) = s.hiddenTs.let { it == null || it - e.timestamp > window }
        val notification = s.notificationTs
        return when {
            early.any(::isCallScreen) -> WakeTrigger.CALL
            early.any { it.packageName in config.alarmPackages && stayedLocked(it) } -> WakeTrigger.ALARM
            notification != null && s.startTs - notification in 0..config.notificationWakeWindowMs ->
                WakeTrigger.NOTIFICATION
            else -> WakeTrigger.UNKNOWN
        }
    }

    /** Dialer apps also host their ordinary screens, so trust the activity name when there is one. */
    private fun isCallScreen(e: UsageEvent): Boolean =
        e.packageName in config.callPackages &&
            (e.className?.contains(config.callScreenClassMarker, ignoreCase = true) ?: true)
}

data class DerivationResult(
    val sessions: List<ClassifiedSession>,
    /** Start of a session left open at the end of the input, which a later harvest will close. */
    val openSessionStart: Long?,
)

fun deriveSessions(events: Iterable<UsageEvent>, config: ClassifierConfig = ClassifierConfig()): DerivationResult {
    val deriver = SessionDeriver(config)
    val sessions = events.mapNotNull(deriver::feed)
    return DerivationResult(sessions, deriver.openSessionStart)
}

internal fun kindOf(durationMs: Long, unlocked: Boolean, realAppCount: Int, config: ClassifierConfig): SessionKind =
    when {
        !unlocked && durationMs < config.glanceNoUnlockMaxMs -> SessionKind.GLANCE_NO_UNLOCK
        !unlocked -> SessionKind.LOCKED_EXTENDED
        realAppCount == 0 && durationMs < config.glanceUnlockedMaxMs -> SessionKind.GLANCE_UNLOCKED
        durationMs <= config.shortMaxMs -> SessionKind.SHORT
        else -> SessionKind.EXTENDED
    }

internal enum class KeyguardState { UNKNOWN, SHOWING, HIDDEN }

private class OpenSession(
    val startTs: Long,
    private val keyguardAtStart: KeyguardState,
    val notificationTs: Long?,
    val offScreenResume: UsageEvent?,
) {
    var lastSeenTs = startTs
        private set
    var hiddenTs: Long? = null
        private set
    val resumes = mutableListOf<UsageEvent>()

    private var firstKeyguard: UsageEvent? = null
    private val foregroundMs = LinkedHashMap<String, Long>()
    private var current: String? = null
    private var currentSince = 0L

    init {
        // An activity that came up while the screen was off (a ringing alarm, an incoming call)
        // is already in front when the screen turns on.
        offScreenResume?.let {
            foregroundMs[it.packageName] = 0L
            current = it.packageName
            currentSince = startTs
        }
    }

    fun touch(ts: Long) {
        lastSeenTs = maxOf(lastSeenTs, ts)
    }

    fun onKeyguard(e: UsageEvent) {
        if (firstKeyguard == null) firstKeyguard = e
        if (e.type == EventType.KEYGUARD_HIDDEN && hiddenTs == null) {
            hiddenTs = e.timestamp
            touch(e.timestamp)
        }
    }

    /** Unknown state counts as locked: claiming an unlock that never happened is the worse error. */
    fun lockedAtStart(settleMs: Long): Boolean = when (keyguardAtStart) {
        KeyguardState.HIDDEN -> firstKeyguard.let {
            it != null && it.type == EventType.KEYGUARD_SHOWN && it.timestamp - startTs <= settleMs
        }
        KeyguardState.SHOWING, KeyguardState.UNKNOWN -> true
    }

    fun onResumed(e: UsageEvent) {
        resumes += e
        touch(e.timestamp)
        foregroundMs.putIfAbsent(e.packageName, 0L)
        if (current == e.packageName) return
        settle(e.timestamp)
        current = e.packageName
        currentSince = e.timestamp
    }

    fun onPaused(e: UsageEvent) {
        touch(e.timestamp)
        if (current != e.packageName) return
        settle(e.timestamp)
        current = null
    }

    fun closeForeground(endTs: Long): Map<String, Long> {
        settle(endTs)
        current = null
        return foregroundMs.toMap()
    }

    private fun settle(ts: Long) {
        val pkg = current ?: return
        foregroundMs[pkg] = foregroundMs.getValue(pkg) + (ts - currentSince).coerceAtLeast(0)
        currentSince = ts
    }
}
