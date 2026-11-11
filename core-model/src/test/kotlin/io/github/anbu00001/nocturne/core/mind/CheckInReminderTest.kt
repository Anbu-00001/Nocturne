package io.github.anbu00001.nocturne.core.mind

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckInReminderTest {
    private val day = LocalDate.of(2026, 11, 10)
    private fun post(minute: Int, posted: LocalDate? = null, answered: Boolean = false, enabled: Boolean = true) =
        CheckInReminder.shouldPost(minute, day, posted, answered, enabled)

    @Test fun `posts from 20 00`() {
        assertTrue(post(20 * 60))
        assertTrue(post(20 * 60 + 14))
        assertTrue(post(22 * 60 + 59))
    }

    @Test fun `not before 20 00 or from 23 00`() {
        assertFalse(post(19 * 60 + 59))
        assertFalse(post(23 * 60))
        assertFalse(post(30))
    }

    @Test fun `once a day`() {
        assertFalse(post(21 * 60, posted = day))
        assertTrue(post(21 * 60, posted = day.minusDays(1)))
    }

    @Test fun `not when today is answered or the switch is off`() {
        assertFalse(post(21 * 60, answered = true))
        assertFalse(post(21 * 60, enabled = false))
    }
}
