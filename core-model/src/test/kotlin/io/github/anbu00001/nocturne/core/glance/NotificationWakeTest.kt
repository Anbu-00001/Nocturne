package io.github.anbu00001.nocturne.core.glance

import io.github.anbu00001.nocturne.core.event.UsageEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Classifier version 3: behaviour found in the A18's own event log. */
class NotificationWakeTest {

    private fun single(events: List<UsageEvent>): ClassifiedSession = deriveSessions(events).sessions.single()

    @Test
    fun `a notification logged just after the screen comes on still caused the wake`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            notification(0.4, WHATSAPP)
            screenOff(10.0)
        })
        assertEquals(WakeTrigger.NOTIFICATION, s.trigger)
        assertTrue(s.countsAsGlance)
    }

    @Test
    fun `a notification over a second after the wake did not cause it`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            notification(1.5, WHATSAPP)
            screenOff(4.0)
        })
        assertEquals(WakeTrigger.UNKNOWN, s.trigger)
    }

    @Test
    fun `the clock's upcoming-alarm notice lighting the screen is an alarm wake, not a glance`() {
        val s = single(script {
            keyguardShown(-60.0)
            notification(-0.2, CLOCK)
            screenOn(0.0)
            screenOff(10.0)
        })
        assertEquals(WakeTrigger.ALARM, s.trigger)
        assertFalse(s.countsAsGlance)
        assertEquals(s.startTs, s.lastActivityTs)
    }

    @Test
    fun `last activity is the last unlock or app switch, not the screen timing out`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(2.0, INSTAGRAM)
            screenOff(1_802.0)
            paused(1_803.0, INSTAGRAM)
        })
        assertEquals(at(2.0), s.lastActivityTs)
        assertEquals(at(1_802.0), s.endTs)
    }
}
