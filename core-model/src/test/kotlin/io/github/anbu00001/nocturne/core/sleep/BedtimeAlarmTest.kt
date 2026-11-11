package io.github.anbu00001.nocturne.core.sleep

import io.github.anbu00001.nocturne.core.time.LocalClock.HOUR_MS
import io.github.anbu00001.nocturne.core.time.LocalClock.MINUTE_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BedtimeAlarmTest {
    private val evening = BedtimeAlarm.eveningStart(20_400, 330) // 18:00 IST on the night's date
    private fun at(hoursAfter18: Double) = evening + (hoursAfter18 * HOUR_MS).toLong()

    @Test fun `the evening's last clock use and the first ring after it`() {
        val alarm = BedtimeAlarm.find(listOf(at(1.0), at(4.0)), listOf(at(9.5), at(10.0)), evening)
        assertEquals(BedtimeAlarm(at(4.0), at(9.5)), alarm)
    }

    @Test fun `under five hours ahead is not an alarm for the night`() {
        assertNull(BedtimeAlarm.find(listOf(at(5.0)), listOf(at(9.9)), evening))
        assertEquals(BedtimeAlarm(at(5.0), at(10.0)), BedtimeAlarm.find(listOf(at(5.0)), listOf(at(10.0)), evening))
    }

    @Test fun `clock uses outside 18 to 03 and rings after noon are left out`() {
        assertNull(BedtimeAlarm.find(listOf(at(-0.5), at(9.5)), listOf(at(15.0)), evening))
        assertNull(BedtimeAlarm.find(listOf(at(2.0)), listOf(at(18.5)), evening))
        assertNull(BedtimeAlarm.find(listOf(at(2.0)), emptyList(), evening))
    }

    @Test fun `placement of the screen-inactivity onset`() {
        val alarm = BedtimeAlarm(at(4.0), at(10.0))
        assertEquals(BedtimeAlarm.Placement.WITH, alarm.placement(at(4.0) + 30 * MINUTE_MS))
        assertEquals(BedtimeAlarm.Placement.WITH, alarm.placement(at(4.0) - 5 * MINUTE_MS))
        assertEquals(BedtimeAlarm.Placement.AFTER, alarm.placement(at(4.0) + 31 * MINUTE_MS))
        assertEquals(BedtimeAlarm.Placement.BEFORE, alarm.placement(at(4.0) - 6 * MINUTE_MS))
        assertNull(alarm.placement(null))
    }

    @Test fun `summary counts nights together and the median wait after`() {
        val a = BedtimeAlarm(at(4.0), at(10.0))
        val summary = BedtimeAlarmSummary.of(
            20,
            listOf(a to at(4.0), a to at(5.0), a to at(7.0), a to null, a to at(6.0)),
        )
        assertEquals(BedtimeAlarmSummary(20, 5, 1, 120.0), summary)
    }
}
