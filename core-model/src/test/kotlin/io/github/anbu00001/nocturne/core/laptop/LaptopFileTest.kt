package io.github.anbu00001.nocturne.core.laptop

import io.github.anbu00001.nocturne.core.light.DisplayProfile
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LaptopFileTest {

    /** The same file tools/activitywatch/test_nocturne_aw.py writes and compares, so the two ends agree on the format. */
    private val sample = checkNotNull(javaClass.getResource("/laptop/sample.csv")) { "missing /laptop/sample.csv" }.readText()

    @Test
    fun `the shared sample file parses into its display, coverage and spans`() {
        val file = LaptopFileFormat.parse(sample.lineSequence())
        assertEquals(listOf(LaptopDisplay("workbook", 345, 215, 2.0, 250.0)), file.displays)
        assertEquals(listOf(LaptopCoverage("workbook", ist("2026-09-14", "18:00"), ist("2026-09-15", "06:00"))), file.coverage)
        assertEquals(
            listOf(
                LaptopSpan("workbook", ist("2026-09-14", "21:30"), ist("2026-09-14", "23:10"), 0.06, false),
                LaptopSpan("workbook", ist("2026-09-14", "23:10"), ist("2026-09-15", "00:31"), 0.06, true),
                LaptopSpan("workbook", ist("2026-09-15", "03:12"), ist("2026-09-15", "03:12"), null, null),
            ),
            file.spans,
        )
        val profile = file.displays.single().profile()
        assertEquals(0.345 * 0.215, profile.screenAreaM2, 1e-12)
        assertEquals(2.0 + 248.0 * 0.06, profile.luminanceAtShare(0.06), 1e-9)
    }

    @Test
    fun `a file breaking any rule is refused whole, naming the line`() {
        fun parse(vararg rows: String) = LaptopFileFormat.parse(sequenceOf(LaptopFileFormat.HEADER, *rows))
        val display = "display,workbook,345,215,2.0,250.0"
        val coverage = "coverage,workbook,1000,2000"

        assertFailsWith<IllegalArgumentException> { LaptopFileFormat.parse(sequenceOf("nocturne-laptop,2", display)) }
        assertFailsWith<IllegalArgumentException> { LaptopFileFormat.parse(emptySequence()) }
        assertEquals(
            "line 4: backlight 1.5 outside 0..1",
            assertFailsWith<IllegalArgumentException> { parse(display, coverage, "span,workbook,1000,1500,1.5,0") }.message,
        )
        assertEquals("line 4: bad host \"work book\"", assertFailsWith<IllegalArgumentException> { parse(display, coverage, "span,work book,1000,1500,,") }.message)
        assertEquals("line 2: bad number \"wide\"", assertFailsWith<IllegalArgumentException> { parse("display,workbook,wide,215,2.0,250.0") }.message)
        assertTrue("outside its coverage" in assertFailsWith<IllegalArgumentException> { parse(display, coverage, "span,workbook,2000,2500,,") }.message!!)
        assertTrue("no display" in assertFailsWith<IllegalArgumentException> { parse(coverage, "span,workbook,1000,1500,,") }.message!!)
        assertTrue("no coverage" in assertFailsWith<IllegalArgumentException> { parse(display, "span,workbook,1000,1500,,") }.message!!)
        assertTrue("ends before" in assertFailsWith<IllegalArgumentException> { parse(display, coverage, "span,workbook,1500,1000,,") }.message!!)
        assertTrue("night filter" in assertFailsWith<IllegalArgumentException> { parse(display, coverage, "span,workbook,1000,1500,,yes") }.message!!)
        assertTrue("implausible panel" in assertFailsWith<IllegalArgumentException> { parse("display,workbook,345,215,300,250") }.message!!)
        assertTrue("two coverage rows" in assertFailsWith<IllegalArgumentException> { parse(display, coverage, coverage) }.message!!)
        assertTrue("same time" in assertFailsWith<IllegalArgumentException> { parse(display, coverage, "span,workbook,1000,1500,,", "span,workbook,1000,1600,,") }.message!!)
    }

    @Test
    fun `use on two computers at once counts once, and touching spans join`() {
        fun span(host: String, from: Long, to: Long) = LaptopSpan(host, from, to, null, null)
        val merged = LaptopActivity.merged(
            listOf(span("desk", 50, 80), span("workbook", 0, 10), span("workbook", 10, 20), span("desk", 15, 30), span("workbook", 60, 70)),
        )
        assertEquals(listOf(0L..30L, 50L..80L), merged)
        assertEquals(15L + 10L, LaptopActivity.overlapMs(merged, 15, 60))
        assertEquals(0L, LaptopActivity.overlapMs(merged, 30, 50))
    }

    @Test
    fun `a laptop panel's luminance follows its backlight share linearly`() {
        val panel = DisplayProfile.panel(minNits = 2.0, peakNits = 250.0, widthMm = 345, heightMm = 215)
        assertEquals(2.0, panel.luminanceAtShare(0.0), 1e-9)
        assertEquals(126.0, panel.luminanceAtShare(0.5), 1e-9)
        assertEquals(250.0, panel.luminanceAtShare(1.4), 1e-9)
    }

    private fun ist(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
}
