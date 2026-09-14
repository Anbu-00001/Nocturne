package io.github.anbu00001.nocturne.core.glance

/**
 * Every glance threshold lives here rather than inline (spec §6.5): they are expected to be
 * tuned against real nights during the first fortnight.
 */
data class ClassifierConfig(
    /** A wake that never unlocks and is shorter than this is a GLANCE_NO_UNLOCK. */
    val glanceNoUnlockMaxMs: Long = 15_000,
    /** An unlock that shows only [trivialPackages] and is shorter than this is a GLANCE_UNLOCKED. */
    val glanceUnlockedMaxMs: Long = 30_000,
    /** Unlocked sessions up to and including this length are SHORT; longer ones are EXTENDED. */
    val shortMaxMs: Long = 180_000,
    /**
     * A KEYGUARD_SHOWN logged this soon after a wake means the lock screen came up on that wake,
     * even if the last known keyguard state was hidden.
     */
    val keyguardSettleMs: Long = 1_000,
    /** How close to the wake an alarm or call screen must come up to count as its cause. */
    val triggerWindowMs: Long = 2_000,
    /** How soon before the wake a notification must have fired to count as its cause. */
    val notificationWakeWindowMs: Long = 1_500,
    val unlockEvidence: UnlockEvidence = UnlockEvidence.KEYGUARD_EVENTS,
    /** Seeing only these after unlocking means nothing was looked at (spec: launcher, systemui, clock). */
    val trivialPackages: Set<String> = DefaultPackages.TRIVIAL,
    val alarmPackages: Set<String> = DefaultPackages.CLOCKS,
    val callPackages: Set<String> = DefaultPackages.CALLS,
    /**
     * A call package's activity is a call screen only if its class name contains this, when the class
     * is known. Google Dialer runs both its main UI and com.android.dialer.incall...InCallActivity.
     */
    val callScreenClassMarker: String = "incall",
    /** Apps that can appear over the lock screen, so bringing one forward does not prove an unlock. */
    val overLockscreenPackages: Set<String> = DefaultPackages.OVER_LOCKSCREEN,
)

enum class UnlockEvidence {
    /** Trust KEYGUARD_SHOWN / KEYGUARD_HIDDEN (API 28+). */
    KEYGUARD_EVENTS,

    /**
     * For devices that never log keyguard events: an unlock is inferred when an app that cannot
     * run over the lock screen comes to the foreground.
     */
    ACTIVITY_INFERRED,
}

/**
 * Package names from common Android builds. The collector adds the device's resolved home app,
 * clock and dialer at runtime, and the debug screen lists what was actually seen, so these are
 * a starting point to verify on the phone, not a claim about any particular ROM.
 */
object DefaultPackages {
    const val SYSTEM = "android"
    const val SYSTEM_UI = "com.android.systemui"

    val LAUNCHERS = setOf(
        "com.android.launcher",
        "com.oppo.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "net.oneplus.launcher",
    )
    val CLOCKS = setOf(
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
    )
    val CALLS = setOf(
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
    )
    /** Lock-screen content apps (ColorOS lock screen magazine, seen on the device). */
    val LOCK_SCREEN = setOf("com.heytap.pictorial")
    val CAMERAS = setOf(
        "com.oplus.camera",
        "com.oppo.camera",
        "com.android.camera",
        "com.google.android.GoogleCamera",
        "com.sec.android.app.camera",
    )

    val TRIVIAL: Set<String> = setOf(SYSTEM, SYSTEM_UI) + LAUNCHERS + CLOCKS + LOCK_SCREEN
    val OVER_LOCKSCREEN: Set<String> = setOf(SYSTEM, SYSTEM_UI) + CLOCKS + CALLS + CAMERAS + LOCK_SCREEN
}

/**
 * Bump whenever classification logic or defaults change. The app re-derives every stored session
 * when it sees a new value (spec §5: the whole history is re-scored when the model improves).
 */
const val CLASSIFIER_VERSION = 2
