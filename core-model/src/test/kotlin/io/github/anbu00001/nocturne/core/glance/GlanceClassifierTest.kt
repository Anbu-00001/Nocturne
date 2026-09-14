package io.github.anbu00001.nocturne.core.glance

import io.github.anbu00001.nocturne.core.event.UsageEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlanceClassifierTest {

    private fun single(events: List<UsageEvent>, config: ClassifierConfig = ClassifierConfig()): ClassifiedSession =
        deriveSessions(events, config).sessions.single()

    // ---- the four spec kinds (plus LOCKED_EXTENDED) ----

    @Test
    fun `looking at the lock screen for a few seconds is GLANCE_NO_UNLOCK`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            screenOff(4.0)
        })
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, s.kind)
        assertFalse(s.unlocked)
        assertEquals(4_000L, s.durationMs)
        assertEquals(WakeTrigger.UNKNOWN, s.trigger)
        assertNull(s.dominantPackage)
        assertEquals(0, s.appCount)
        assertTrue(s.countsAsGlance)
        assertFalse(s.endInferred)
    }

    @Test
    fun `a locked wake of 15s or more is LOCKED_EXTENDED, not a glance`() {
        val justUnder = single(script { keyguardShown(-60.0); screenOn(0.0); screenOff(14.999) })
        val atLimit = single(script { keyguardShown(-60.0); screenOn(0.0); screenOff(15.0) })
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, justUnder.kind)
        assertEquals(SessionKind.LOCKED_EXTENDED, atLimit.kind)
        assertFalse(atLimit.countsAsGlance)
    }

    @Test
    fun `unlocking onto the launcher and locking again is GLANCE_UNLOCKED`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.2)
            resumed(1.3, LAUNCHER)
            paused(9.0, LAUNCHER)
            screenOff(9.1)
        })
        assertEquals(SessionKind.GLANCE_UNLOCKED, s.kind)
        assertTrue(s.unlocked)
        assertEquals(0, s.appCount)
        assertEquals(LAUNCHER, s.dominantPackage)
        assertTrue(s.countsAsGlance)
    }

    @Test
    fun `systemui and the clock app still count as seeing nothing`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(0.8)
            resumed(1.0, LAUNCHER)
            paused(4.0, LAUNCHER)
            resumed(4.0, CLOCK)
            paused(12.0, CLOCK)
            resumed(12.0, DefaultPackages.SYSTEM_UI)
            screenOff(14.0)
        })
        assertEquals(SessionKind.GLANCE_UNLOCKED, s.kind)
        assertEquals(WakeTrigger.UNKNOWN, s.trigger)
    }

    @Test
    fun `an unlock under 30s that opens a real app is SHORT`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(2.0, WHATSAPP)
            screenOff(20.0)
        })
        assertEquals(SessionKind.SHORT, s.kind)
        assertEquals(1, s.appCount)
        assertEquals(WHATSAPP, s.dominantPackage)
        assertEquals(18_000L, s.foregroundMs[WHATSAPP])
        assertFalse(s.countsAsGlance)
    }

    @Test
    fun `unlocked boundaries sit at 30s and 3min`() {
        fun unlockedFor(seconds: Double, app: String) = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(0.5)
            resumed(0.6, app)
            screenOff(seconds)
        })
        assertEquals(SessionKind.GLANCE_UNLOCKED, unlockedFor(29.999, LAUNCHER).kind)
        assertEquals(SessionKind.SHORT, unlockedFor(30.0, LAUNCHER).kind)
        assertEquals(SessionKind.SHORT, unlockedFor(180.0, INSTAGRAM).kind)
        assertEquals(SessionKind.EXTENDED, unlockedFor(180.001, INSTAGRAM).kind)
    }

    @Test
    fun `an extended session is attributed to the app with the most foreground time`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(2.0, LAUNCHER)
            paused(3.0, LAUNCHER)
            resumed(3.0, INSTAGRAM)
            paused(400.0, INSTAGRAM)
            resumed(400.0, WHATSAPP)
            paused(460.0, WHATSAPP)
            resumed(460.0, LAUNCHER)
            screenOff(470.0)
        })
        assertEquals(SessionKind.EXTENDED, s.kind)
        assertEquals(INSTAGRAM, s.dominantPackage)
        assertEquals(2, s.appCount)
        assertEquals(mapOf(LAUNCHER to 11_000L, INSTAGRAM to 397_000L, WHATSAPP to 60_000L), s.foregroundMs)
    }

    @Test
    fun `kindOf at every threshold`() {
        val c = ClassifierConfig()
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, kindOf(14_999, unlocked = false, realAppCount = 0, config = c))
        assertEquals(SessionKind.LOCKED_EXTENDED, kindOf(15_000, unlocked = false, realAppCount = 0, config = c))
        assertEquals(SessionKind.LOCKED_EXTENDED, kindOf(15_000, unlocked = false, realAppCount = 2, config = c))
        assertEquals(SessionKind.GLANCE_UNLOCKED, kindOf(29_999, unlocked = true, realAppCount = 0, config = c))
        assertEquals(SessionKind.SHORT, kindOf(29_999, unlocked = true, realAppCount = 1, config = c))
        assertEquals(SessionKind.SHORT, kindOf(30_000, unlocked = true, realAppCount = 0, config = c))
        assertEquals(SessionKind.SHORT, kindOf(180_000, unlocked = true, realAppCount = 1, config = c))
        assertEquals(SessionKind.EXTENDED, kindOf(180_001, unlocked = true, realAppCount = 0, config = c))
    }

    // ---- adversarial cases (spec §10) ----

    @Test
    fun `a notification that lights the screen without an unlock is a NOTIFICATION-triggered glance`() {
        val s = single(script {
            keyguardShown(-600.0)
            notification(-0.4, WHATSAPP)
            screenOn(0.0)
            screenOff(6.0)
        })
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, s.kind)
        assertEquals(WakeTrigger.NOTIFICATION, s.trigger)
        assertTrue(s.countsAsGlance)
    }

    @Test
    fun `a notification long before the wake is not its cause`() {
        val s = single(script {
            keyguardShown(-600.0)
            notification(-30.0, WHATSAPP)
            screenOn(0.0)
            screenOff(6.0)
        })
        assertEquals(WakeTrigger.UNKNOWN, s.trigger)
    }

    @Test
    fun `rapid on-off-on is two separate glances`() {
        val sessions = deriveSessions(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            screenOff(0.8)
            screenOn(1.5)
            screenOff(3.0)
        }).sessions
        assertEquals(listOf(800L, 1_500L), sessions.map { it.durationMs })
        assertTrue(sessions.all { it.kind == SessionKind.GLANCE_NO_UNLOCK })
    }

    @Test
    fun `an alarm over the lock screen is attributed to the alarm and never counts as a glance`() {
        val ringing = single(script {
            keyguardShown(-3_600.0)
            resumed(-0.2, CLOCK) // alarm activity launches just before it turns the screen on
            screenOn(0.0)
            paused(45.0, CLOCK)
            screenOff(46.0)
        })
        assertEquals(SessionKind.LOCKED_EXTENDED, ringing.kind)
        assertEquals(WakeTrigger.ALARM, ringing.trigger)
        assertEquals(CLOCK, ringing.dominantPackage)
        assertFalse(ringing.unlocked)

        val dismissedQuickly = single(script {
            keyguardShown(-3_600.0)
            screenOn(0.0)
            resumed(0.3, CLOCK)
            paused(5.0, CLOCK)
            screenOff(6.0)
        })
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, dismissedQuickly.kind)
        assertEquals(WakeTrigger.ALARM, dismissedQuickly.trigger)
        assertFalse(dismissedQuickly.countsAsGlance)
    }

    @Test
    fun `a call arriving during a phone-down gap is its own locked session and splits the gap`() {
        val sessions = deriveSessions(script {
            screenOn(-7_320.0)
            keyguardHidden(-7_319.0)
            resumed(-7_318.0, INSTAGRAM)
            paused(-7_200.0, INSTAGRAM)
            screenOff(-7_200.0)
            keyguardShown(-7_199.9)
            screenOn(0.0)
            resumed(0.4, INCALL)
            paused(240.0, INCALL)
            screenOff(241.0)
            screenOn(3_600.0)
            screenOff(3_603.0)
        }).sessions
        assertEquals(
            listOf(SessionKind.SHORT, SessionKind.LOCKED_EXTENDED, SessionKind.GLANCE_NO_UNLOCK),
            sessions.map { it.kind },
        )
        val call = sessions[1]
        assertEquals(WakeTrigger.CALL, call.trigger)
        assertEquals(INCALL, call.dominantPackage)
        assertFalse(call.unlocked)
        assertFalse(call.countsAsGlance)
        assertEquals(listOf(7_200_000L, 3_359_000L), sessions.zipWithNext { a, b -> b.startTs - a.endTs })
    }

    @Test
    fun `a dialer's ordinary screen is not a call, its in-call screen is, even when the user unlocks at once`() {
        val dialer = "com.google.android.dialer"
        // Real ColorOS order on a fingerprint wake: SCREEN_INTERACTIVE, ACTIVITY_RESUMED(last app), KEYGUARD_HIDDEN.
        val lastUsedDialer = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            resumed(0.1, dialer, "com.android.dialer.main.impl.MainActivity")
            keyguardHidden(0.3)
            paused(20.0, dialer)
            screenOff(21.0)
        })
        assertEquals(WakeTrigger.UNKNOWN, lastUsedDialer.trigger)
        assertTrue(lastUsedDialer.unlocked)

        // Sequence seen in the device dump: notification, in-call screen, wake.
        val ringing = single(script {
            keyguardShown(-60.0)
            notification(-0.5, dialer)
            resumed(0.0, dialer, "com.android.dialer.incall.activity.ui.InCallActivity")
            screenOn(0.0)
            keyguardHidden(1.0)
            screenOff(25.0)
        })
        assertEquals(WakeTrigger.CALL, ringing.trigger)
        assertFalse(ringing.countsAsGlance)
    }

    @Test
    fun `an app resumed behind the lock screen was not seen`() {
        // On the device, ColorOS resumes the last-used app on wake even when nobody unlocks.
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            resumed(0.1, INSTAGRAM)
            paused(4.0, INSTAGRAM)
            screenOff(4.1)
        })
        assertFalse(s.unlocked)
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, s.kind)
        assertNull(s.dominantPackage)
        assertEquals(0, s.appCount)
        assertTrue(s.countsAsGlance)
    }

    @Test
    fun `the clock app returning just before a fingerprint unlock is not an alarm`() {
        val s = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            resumed(0.1, CLOCK)
            keyguardHidden(0.3)
            screenOff(8.0)
        })
        assertEquals(WakeTrigger.UNKNOWN, s.trigger)
        assertEquals(SessionKind.GLANCE_UNLOCKED, s.kind)
    }

    @Test
    fun `a fingerprint unlock may log KEYGUARD_HIDDEN just before or in the same millisecond as the wake`() {
        val hiddenFirst = single(script {
            keyguardShown(-60.0)
            keyguardHidden(-0.02)
            screenOn(0.0)
            resumed(0.3, LAUNCHER)
            screenOff(5.0)
        })
        val sameMillisecond = single(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(0.0)
            resumed(0.3, LAUNCHER)
            screenOff(5.0)
        })
        for (s in listOf(hiddenFirst, sameMillisecond)) {
            assertTrue(s.unlocked)
            assertEquals(SessionKind.GLANCE_UNLOCKED, s.kind)
        }
    }

    @Test
    fun `a re-wake inside the lock delay is already unlocked without a new KEYGUARD_HIDDEN`() {
        val sessions = deriveSessions(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(1.1, INSTAGRAM)
            paused(50.0, INSTAGRAM)
            screenOff(50.0)
            screenOn(55.0)
            resumed(55.2, INSTAGRAM)
            paused(70.0, INSTAGRAM)
            screenOff(70.0)
        }).sessions
        val rewake = sessions[1]
        assertTrue(rewake.unlocked)
        assertEquals(SessionKind.SHORT, rewake.kind)
        assertEquals(WakeTrigger.UNKNOWN, rewake.trigger)
    }

    @Test
    fun `KEYGUARD_SHOWN logged right after the wake means that wake was locked`() {
        val s = single(script {
            keyguardHidden(-100.0)
            screenOn(0.0)
            keyguardShown(0.05)
            screenOff(5.0)
        })
        assertFalse(s.unlocked)
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, s.kind)
    }

    @Test
    fun `a lost SCREEN_NON_INTERACTIVE ends the session at its last activity and flags the end as inferred`() {
        val first = deriveSessions(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(1.5, INSTAGRAM)
            paused(95.0, INSTAGRAM)
            screenOn(4_000.0)
            screenOff(4_003.0)
        }).sessions.first()
        assertEquals(at(95.0), first.endTs)
        assertTrue(first.endInferred)
        assertEquals(SessionKind.SHORT, first.kind)
    }

    @Test
    fun `shutdown closes the open session and after boot the keyguard is assumed locked`() {
        val sessions = deriveSessions(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(2.0, INSTAGRAM)
            shutdown(300.0)
            startup(900.0)
            screenOn(910.0)
            screenOff(913.0)
        }).sessions
        assertEquals(2, sessions.size)
        assertEquals(at(300.0), sessions[0].endTs)
        assertFalse(sessions[0].endInferred)
        assertEquals(SessionKind.EXTENDED, sessions[0].kind)
        assertFalse(sessions[1].unlocked)
        assertEquals(SessionKind.GLANCE_NO_UNLOCK, sessions[1].kind)
    }

    @Test
    fun `with no keyguard history a wake is assumed locked`() {
        assertFalse(single(script { screenOn(0.0); screenOff(3.0) }).unlocked)
    }

    @Test
    fun `ACTIVITY_INFERRED infers unlocks on devices that never log keyguard events`() {
        val config = ClassifierConfig(unlockEvidence = UnlockEvidence.ACTIVITY_INFERRED)
        val sessions = deriveSessions(script {
            screenOn(0.0)
            resumed(0.5, LAUNCHER)
            screenOff(6.0)
            screenOn(100.0)
            screenOff(104.0)
            screenOn(200.0)
            resumed(200.3, CLOCK)
            screenOff(230.0)
        }, config).sessions
        assertEquals(listOf(true, false, false), sessions.map { it.unlocked })
        assertEquals(
            listOf(SessionKind.GLANCE_UNLOCKED, SessionKind.GLANCE_NO_UNLOCK, SessionKind.LOCKED_EXTENDED),
            sessions.map { it.kind },
        )
        assertEquals(WakeTrigger.ALARM, sessions[2].trigger)
    }

    @Test
    fun `a session still open when the events run out is held back, not guessed`() {
        val result = deriveSessions(script {
            keyguardShown(-60.0)
            screenOn(0.0)
            keyguardHidden(1.0)
            resumed(1.2, INSTAGRAM)
        })
        assertTrue(result.sessions.isEmpty())
        assertEquals(at(0.0), result.openSessionStart)
    }

    @Test
    fun `a sleep with no wake before it produces nothing`() {
        val result = deriveSessions(script {
            screenOff(0.0)
            keyguardShown(0.1)
            paused(0.2, INSTAGRAM)
        })
        assertTrue(result.sessions.isEmpty())
        assertNull(result.openSessionStart)
    }
}
