package io.github.anbu00001.nocturne.core.glance

enum class SessionKind {
    /** Lit, never unlocked, under [ClassifierConfig.glanceNoUnlockMaxMs]: looked at the lock screen and put it down. */
    GLANCE_NO_UNLOCK,

    /**
     * Lit, never unlocked, for at least [ClassifierConfig.glanceNoUnlockMaxMs]: an alarm, a call,
     * reading notifications on the lock screen. Not one of the spec's four kinds; without it
     * those wakes would have no kind at all.
     */
    LOCKED_EXTENDED,

    /** Unlocked, under [ClassifierConfig.glanceUnlockedMaxMs], only trivial apps: unlocked it, saw nothing, locked it. */
    GLANCE_UNLOCKED,

    /**
     * Unlocked, up to [ClassifierConfig.shortMaxMs]. The spec defines SHORT as 30s–3min with a real
     * app; unlocked wakes under 30s that did open a real app also land here, as do 30s+ unlocks
     * that never left the launcher.
     */
    SHORT,

    /** Unlocked, longer than [ClassifierConfig.shortMaxMs]. */
    EXTENDED;

    val isGlance: Boolean get() = this == GLANCE_NO_UNLOCK || this == GLANCE_UNLOCKED
}

/** What most likely turned the screen on. UNKNOWN is usually the user. */
enum class WakeTrigger { UNKNOWN, NOTIFICATION, ALARM, CALL }

data class ClassifiedSession(
    val startTs: Long,
    val endTs: Long,
    val kind: SessionKind,
    val unlocked: Boolean,
    val trigger: WakeTrigger,
    /** Longest-foreground non-trivial app, else the longest-foreground app of any kind. */
    val dominantPackage: String?,
    /** Distinct non-trivial apps brought to the foreground. */
    val appCount: Int,
    val foregroundMs: Map<String, Long>,
    /** True when SCREEN_NON_INTERACTIVE was never logged and the end had to be estimated. */
    val endInferred: Boolean,
) {
    val durationMs: Long get() = endTs - startTs

    /** Alarms and incoming calls light the screen on their own; that is not the user checking the phone. */
    val countsAsGlance: Boolean
        get() = kind.isGlance && trigger != WakeTrigger.ALARM && trigger != WakeTrigger.CALL
}
