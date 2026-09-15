package io.github.anbu00001.nocturne.core.light

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WarmFilterTest {

    @Test
    fun `the A18's scheduled eye comfort counts only inside its 22 to 07 schedule`() {
        fun at(hour: Int, minute: Int) = WarmFilter.colorOs("1", "22:00", "07:00", hour * 60 + minute)
        assertEquals(true, at(2, 44))
        assertEquals(true, at(22, 0))
        assertEquals(false, at(7, 0))
        assertEquals(false, at(15, 30))
    }

    @Test
    fun `a flag without a usable schedule is taken at its word, and a missing flag is unknown`() {
        assertEquals(true, WarmFilter.colorOs("1", null, "07:00", 900))
        assertEquals(false, WarmFilter.colorOs("0", "22:00", "07:00", 60))
        assertNull(WarmFilter.colorOs(null, "22:00", "07:00", 60))
        assertNull(WarmFilter.clockMinute("25:00"))
        assertEquals(1320, WarmFilter.clockMinute(" 22:00 "))
    }
}
