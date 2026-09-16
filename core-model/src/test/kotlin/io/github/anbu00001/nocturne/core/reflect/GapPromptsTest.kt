package io.github.anbu00001.nocturne.core.reflect

import io.github.anbu00001.nocturne.core.sleep.IST
import io.github.anbu00001.nocturne.core.sleep.ist
import io.github.anbu00001.nocturne.core.time.LocalClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GapPromptsTest {
    private val day = "2026-09-16"

    private fun at(time: String, date: String = day) = ist(date, time)

    private fun gap(from: String, to: String, date: String = day) = PhoneDownGap(at(from, date), at(to, date))

    private fun prompt(id: Long, time: String, gap: PhoneDownGap?, label: GapLabel? = null, dismissed: Boolean = false, date: String = day, source: ReflectionSource = ReflectionSource.PROMPT) =
        GapReflection(id, source, at(time, date), gap?.startTs, gap?.endTs, label, dismissed)

    private fun card(now: Long, gaps: List<PhoneDownGap>, reflections: List<GapReflection> = emptyList(), atNight: Boolean = false) =
        GapPrompts.card(now, IST, atNight, gaps, reflections)

    @Test
    fun `asks about the latest gap that ended recently, never at night`() {
        val morning = gap("09:15", "11:40")
        val lunch = gap("12:10", "13:30")
        val running = PhoneDownGap(at("14:00"), at("16:00"))
        assertEquals(GapCard.Ask(lunch), card(at("15:00"), listOf(morning, lunch, running)))
        assertNull(card(at("15:00"), listOf(morning, lunch), atNight = true))
        // Twelve hours on, the morning gap is left to the weekly list.
        assertNull(card(at("23:41"), listOf(morning)))
        assertEquals(GapCard.Ask(morning), card(at("23:40"), listOf(morning)))
    }

    @Test
    fun `an unanswered card stays for 3 h and then gives way, an answered or dismissed one is never asked again`() {
        val morning = gap("09:15", "11:40")
        val lunch = gap("12:10", "13:30")
        val shown = prompt(1, "12:00", morning)
        assertEquals(GapCard.Open(shown), card(at("14:59"), listOf(morning, lunch), listOf(shown)))
        assertEquals(GapCard.Ask(lunch), card(at("15:00"), listOf(morning, lunch), listOf(shown)))

        val answered = shown.copy(label = GapLabel.DEEP_WORK)
        assertNull(card(at("14:00"), listOf(morning, lunch), listOf(answered))) // one prompt in 3 h, answered or not
        assertEquals(GapCard.Ask(lunch), card(at("15:00"), listOf(morning, lunch), listOf(answered)))
        val dismissed = shown.copy(dismissed = true)
        assertNull(card(at("15:00"), listOf(morning), listOf(dismissed)))
    }

    @Test
    fun `at most four prompts in a local day, counted at the local midnight`() {
        val gaps = listOf(gap("19:00", "21:00"), PhoneDownGap(at("23:00"), at("00:20", "2026-09-17")))
        val four = listOf("09:00", "12:00", "15:00", "18:00").mapIndexed { i, t -> prompt(i + 1L, t, gap("0$i:00", "0$i:30"), GapLabel.REST) }
        assertNull(card(at("21:30"), gaps, four))
        // 00:30 in India is still the 16th in UTC; the cap follows the local day.
        assertEquals(GapCard.Ask(gaps[1]), card(at("00:30", "2026-09-17"), gaps, four))
        // Labels given from the weekly list are not prompts.
        val backfilled = four.map { it.copy(source = ReflectionSource.BACKFILL) }
        assertEquals(GapCard.Ask(gaps[0]), card(at("21:30"), gaps, backfilled))
    }

    @Test
    fun `the weekly list holds the week's gaps without a label, newest first`() {
        val now = at("18:00")
        val old = PhoneDownGap(now - 8 * LocalClock.DAY_MS, now - 8 * LocalClock.DAY_MS + LocalClock.HOUR_MS)
        val monday = PhoneDownGap(now - 2 * LocalClock.DAY_MS, now - 2 * LocalClock.DAY_MS + 2 * LocalClock.HOUR_MS)
        val morning = gap("09:15", "11:40")
        val lunch = gap("12:10", "13:30")
        val afternoon = gap("14:00", "15:30")
        val reflections = listOf(
            prompt(1, "12:00", morning), // shown, never answered
            prompt(2, "15:40", afternoon, dismissed = true),
            prompt(3, "16:00", lunch, GapLabel.LIGHT_WORK, source = ReflectionSource.BACKFILL),
        )
        assertEquals(listOf(morning, monday), GapPrompts.unlabelled(now, listOf(old, monday, morning, lunch, afternoon), reflections))
    }
}
