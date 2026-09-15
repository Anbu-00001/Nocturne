package io.github.anbu00001.nocturne.core.circadian

import kotlin.test.Test
import kotlin.test.assertEquals

/** Peaks against `scipy.signal.find_peaks` 1.18.1: each expected list is what SciPy returned for the same input. */
class PeaksTest {

    private fun peaks(distance: Int, vararg x: Double) = Peaks.find(x, distance)

    @Test
    fun `plateaus count once at their middle, and the ends are never peaks`() {
        assertEquals(listOf(1), peaks(1, 0.0, 1.0, 0.0))
        assertEquals(listOf(1), peaks(1, 0.0, 2.0, 2.0, 0.0))
        assertEquals(listOf(2), peaks(1, 0.0, 2.0, 2.0, 2.0, 0.0))
        assertEquals(emptyList(), peaks(1, 3.0, 1.0, 2.0))
        assertEquals(emptyList(), peaks(1, 0.0, 2.0, 2.0))
        assertEquals(listOf(3), peaks(1, 0.0, 1.0, 1.0, 2.0, 1.0, 0.0))
        assertEquals(listOf(1, 4), peaks(2, 1.0, 2.0, 2.0, 1.0, 2.0, 2.0, 1.0))
    }

    @Test
    fun `distance keeps the highest peaks`() {
        assertEquals(listOf(1, 5), peaks(3, 0.0, 5.0, 0.0, 3.0, 0.0, 4.0, 0.0))
    }

    /**
     * When peaks of equal height compete, SciPy's pick follows NumPy's argsort, which on this laptop (a vectorised
     * quicksort) does not keep equal values in order: series with many ties gave different picks from these rules.
     * Peaks here takes the later of equal peaks first, which matched SciPy on these short series. The CBT signal is
     * continuous, so equal peaks do not arise where it matters.
     */
    @Test
    fun `equal peaks go to the later one first`() {
        assertEquals(listOf(1, 5), peaks(3, 0.0, 2.0, 0.0, 2.0, 0.0, 2.0, 0.0))
        assertEquals(listOf(3, 7), peaks(4, 0.0, 2.0, 0.0, 2.0, 0.0, 2.0, 0.0, 2.0, 0.0))
    }

    @Test
    fun `random series with distinct heights match SciPy`() {
        assertEquals(
            listOf(4, 9, 18, 27, 33),
            peaks(
                5, 463.0, 886.0, 573.0, 877.0, 946.0, 799.0, 476.0, 462.0, 520.0, 875.0, 601.0, 194.0, 189.0, 823.0, 524.0, 487.0,
                644.0, 628.0, 812.0, 190.0, 96.0, 457.0, 310.0, 145.0, 92.0, 551.0, 829.0, 911.0, 710.0, 649.0, 42.0, 609.0, 405.0,
                987.0, 669.0, 756.0, 630.0, 665.0, 161.0, 638.0,
            ),
        )
        assertEquals(
            listOf(1, 7, 16, 24, 31, 37),
            peaks(
                5, 15.0, 851.0, 541.0, 64.0, 60.0, 36.0, 194.0, 900.0, 247.0, 614.0, 30.0, 796.0, 475.0, 334.0, 451.0, 605.0, 862.0,
                200.0, 531.0, 239.0, 655.0, 301.0, 511.0, 4.0, 678.0, 87.0, 468.0, 670.0, 284.0, 416.0, 564.0, 954.0, 860.0, 85.0,
                724.0, 260.0, 322.0, 776.0, 235.0, 525.0,
            ),
        )
        assertEquals(
            listOf(4, 11, 18, 23, 30, 38),
            peaks(
                5, 295.0, 30.0, 71.0, 576.0, 784.0, 110.0, 410.0, 866.0, 297.0, 395.0, 68.0, 981.0, 17.0, 867.0, 701.0, 0.0, 218.0,
                214.0, 949.0, 932.0, 53.0, 481.0, 384.0, 725.0, 406.0, 429.0, 74.0, 579.0, 644.0, 203.0, 797.0, 691.0, 276.0, 344.0,
                89.0, 318.0, 340.0, 15.0, 987.0, 419.0,
            ),
        )
    }
}
