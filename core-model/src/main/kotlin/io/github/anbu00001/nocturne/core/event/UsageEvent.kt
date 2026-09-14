package io.github.anbu00001.nocturne.core.event

/**
 * Event type codes emitted by the platform's UsageEvents.Event.
 *
 * Mirrored as plain ints so this module never imports android.*. Values checked against
 * AOSP frameworks/base core/java/android/app/usage/UsageEvents.java; a :collector unit test
 * asserts the public ones still equal the platform constants.
 */
object EventType {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val USER_INTERACTION = 7

    /** @hide on the platform, but still delivered to usage-access apps with the channel obfuscated. */
    const val NOTIFICATION_INTERRUPTION = 12
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val FOREGROUND_SERVICE_START = 19
    const val FOREGROUND_SERVICE_STOP = 20
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27
}

/** One harvested usage event. [timestamp] is epoch millis, UTC. */
data class UsageEvent(
    val timestamp: Long,
    val type: Int,
    val packageName: String,
    val className: String? = null,
)
