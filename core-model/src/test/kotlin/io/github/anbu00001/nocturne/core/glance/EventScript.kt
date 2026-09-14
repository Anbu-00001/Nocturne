package io.github.anbu00001.nocturne.core.glance

import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.event.UsageEvent
import kotlin.math.roundToLong

/** 2026-09-14T22:00:00Z. Script times are seconds relative to this. */
const val T0 = 1_789_423_200_000L

const val LAUNCHER = "com.android.launcher"
const val CLOCK = "com.android.deskclock"
const val INCALL = "com.android.incallui"
const val INSTAGRAM = "com.instagram.android"
const val WHATSAPP = "com.whatsapp"

fun at(seconds: Double): Long = T0 + (seconds * 1000).roundToLong()

/** Builds an event stream in exactly the order written, so same-millisecond orderings can be tested. */
class EventScript {
    val events = mutableListOf<UsageEvent>()

    private fun add(seconds: Double, type: Int, pkg: String, className: String? = null) {
        events += UsageEvent(at(seconds), type, pkg, className)
    }

    fun screenOn(s: Double) = add(s, EventType.SCREEN_INTERACTIVE, DefaultPackages.SYSTEM)
    fun screenOff(s: Double) = add(s, EventType.SCREEN_NON_INTERACTIVE, DefaultPackages.SYSTEM)
    fun keyguardShown(s: Double) = add(s, EventType.KEYGUARD_SHOWN, DefaultPackages.SYSTEM)
    fun keyguardHidden(s: Double) = add(s, EventType.KEYGUARD_HIDDEN, DefaultPackages.SYSTEM)
    fun resumed(s: Double, pkg: String, className: String? = null) = add(s, EventType.ACTIVITY_RESUMED, pkg, className)
    fun paused(s: Double, pkg: String) = add(s, EventType.ACTIVITY_PAUSED, pkg)
    fun notification(s: Double, pkg: String) = add(s, EventType.NOTIFICATION_INTERRUPTION, pkg)
    fun shutdown(s: Double) = add(s, EventType.DEVICE_SHUTDOWN, DefaultPackages.SYSTEM)
    fun startup(s: Double) = add(s, EventType.DEVICE_STARTUP, DefaultPackages.SYSTEM)
}

fun script(block: EventScript.() -> Unit): List<UsageEvent> = EventScript().apply(block).events
