package io.github.anbu00001.nocturne.core.light

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtEyesTest {
    private val a18 = DisplayProfile.OPPO_A18

    @Test
    fun `without a room reading the estimate is the screen's share alone`() {
        // This phone at night: auto brightness 140, dark mode, 2700 K eye comfort.
        val screen = EveningLight.screenAtEyes(140, darkUi = true, warmFilter = true, profile = a18)
        val withRoom = EveningLight.atEyes(80.0, 140, darkUi = true, warmFilter = true, profile = a18, evening = true)
        assertEquals(screen.mid + LightDose.ambientMelanopicEdi(80.0, evening = true).mid, withRoom.mid, 1e-9)
        assertTrue(screen.high < MelanopicTargets.SLEEP_MAX_LUX, "$screen")
    }

    @Test
    fun `a daytime room reading counts as daylight-like light, an evening one as warm light`() {
        val day = EveningLight.atEyes(100.0, 140, darkUi = true, warmFilter = true, profile = a18, evening = false)
        val evening = EveningLight.atEyes(100.0, 140, darkUi = true, warmFilter = true, profile = a18, evening = true)
        assertTrue(day.mid > evening.mid, "day $day, evening $evening")
    }
}
