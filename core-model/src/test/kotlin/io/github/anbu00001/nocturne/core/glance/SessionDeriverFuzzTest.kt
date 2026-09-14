package io.github.anbu00001.nocturne.core.glance

import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.event.UsageEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionDeriverFuzzTest {

    private val types = intArrayOf(
        EventType.SCREEN_INTERACTIVE, EventType.SCREEN_NON_INTERACTIVE,
        EventType.KEYGUARD_SHOWN, EventType.KEYGUARD_HIDDEN,
        EventType.ACTIVITY_RESUMED, EventType.ACTIVITY_PAUSED, EventType.ACTIVITY_STOPPED,
        EventType.NOTIFICATION_INTERRUPTION, EventType.USER_INTERACTION,
        EventType.DEVICE_SHUTDOWN, EventType.DEVICE_STARTUP, EventType.FOREGROUND_SERVICE_START,
    )
    private val packages = listOf(LAUNCHER, CLOCK, INCALL, INSTAGRAM, WHATSAPP, DefaultPackages.SYSTEM_UI)

    @Test
    fun `garbage event streams still yield ordered, non-overlapping, self-consistent sessions`() {
        val config = ClassifierConfig()
        repeat(300) { seed ->
            val rnd = Random(seed)
            var ts = T0
            val events = List(600) {
                ts += rnd.nextLong(0, 90_000) // includes same-millisecond pile-ups
                UsageEvent(ts, types[rnd.nextInt(types.size)], packages[rnd.nextInt(packages.size)])
            }
            val sessions = deriveSessions(events, config).sessions
            for (s in sessions) {
                assertTrue(s.durationMs >= 0, "seed $seed: negative duration")
                assertTrue(s.foregroundMs.values.sum() <= s.durationMs, "seed $seed: foreground exceeds session")
                assertEquals(kindOf(s.durationMs, s.unlocked, s.appCount, config), s.kind, "seed $seed")
                if (s.kind.isGlance) assertTrue(s.durationMs < config.glanceUnlockedMaxMs, "seed $seed")
            }
            sessions.zipWithNext { a, b -> assertTrue(a.endTs <= b.startTs, "seed $seed: sessions overlap") }
        }
    }
}
