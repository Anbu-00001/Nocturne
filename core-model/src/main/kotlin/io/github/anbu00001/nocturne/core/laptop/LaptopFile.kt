package io.github.anbu00001.nocturne.core.laptop

import io.github.anbu00001.nocturne.core.light.DisplayProfile

/**
 * A computer ActivityWatch watches (spec §9 Phase 4), with its panel as the laptop describes it: the size from the
 * panel's EDID, the luminance from its specification. Both are device facts, like the phone's own display profile.
 */
data class LaptopDisplay(val host: String, val widthMm: Int, val heightMm: Int, val minNits: Double, val peakNits: Double) {
    init {
        require(widthMm > 0 && heightMm > 0 && minNits >= 0 && peakNits > minNits) { "implausible panel for $host" }
    }

    fun profile(): DisplayProfile = DisplayProfile.panel(minNits, peakNits, widthMm, heightMm)
}

/**
 * Someone was using the computer from [startTs] to [endTs]: ActivityWatch's not-afk, which ends at the last keyboard or
 * pointer input. [backlight] is the backlight's share of its range and [warmFilter] whether a night filter was on, both
 * null where the laptop did not record them. A span holds one display state; the laptop splits a stretch where it changed.
 */
data class LaptopSpan(val host: String, val startTs: Long, val endTs: Long, val backlight: Double?, val warmFilter: Boolean?) {
    init {
        require(endTs >= startTs) { "span of $host ends before it starts" }
        require(backlight == null || backlight in 0.0..1.0) { "backlight $backlight outside 0..1" }
    }
}

/** Every span of [host] that starts in [fromTs] until [toTs] is in the file carrying this row, so an import replaces exactly those. */
data class LaptopCoverage(val host: String, val fromTs: Long, val toTs: Long) {
    init {
        require(toTs > fromTs) { "empty coverage for $host" }
    }

    fun covers(span: LaptopSpan) = span.host == host && span.startTs >= fromTs && span.startTs < toTs
}

data class LaptopFile(val displays: List<LaptopDisplay>, val coverage: List<LaptopCoverage>, val spans: List<LaptopSpan>)

/**
 * What the laptop sends the phone: plain lines, written by `tools/activitywatch/nocturne_aw.py` and read here, so the
 * format has one reader and one writer with a shared test file (`laptop/sample.csv`).
 *
 * ```
 * nocturne-laptop,1
 * display,<host>,<width mm>,<height mm>,<min nits>,<peak nits>
 * coverage,<host>,<from ms>,<to ms>
 * span,<host>,<start ms>,<end ms>,<backlight 0..1 or empty>,<1, 0 or empty>
 * ```
 * Times are UTC epoch milliseconds. Every covered host needs a display row, every span its host's coverage and a start
 * of its own; a file that breaks any rule is refused whole, never imported in part.
 */
object LaptopFileFormat {
    const val HEADER = "nocturne-laptop,1"

    private val HOST = Regex("[A-Za-z0-9._-]{1,64}")

    fun parse(lines: Sequence<String>): LaptopFile {
        val displays = ArrayList<LaptopDisplay>()
        val coverage = ArrayList<LaptopCoverage>()
        val spans = ArrayList<LaptopSpan>()
        var header = false
        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEachIndexed
            val row = index + 1
            fun fail(reason: String): Nothing = throw IllegalArgumentException("line $row: $reason")
            if (!header) {
                if (line != HEADER) fail("expected \"$HEADER\"")
                header = true
                return@forEachIndexed
            }
            val f = line.split(',')
            fun host() = f[1].takeIf { HOST.matches(it) } ?: fail("bad host \"${f[1]}\"")
            fun long(i: Int) = f[i].toLongOrNull() ?: fail("bad number \"${f[i]}\"")
            fun double(i: Int) = f[i].toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad number \"${f[i]}\"")
            try {
                when (f[0]) {
                    "display" -> {
                        if (f.size != 6) fail("display needs 6 fields")
                        displays += LaptopDisplay(host(), long(2).toInt(), long(3).toInt(), double(4), double(5))
                    }
                    "coverage" -> {
                        if (f.size != 4) fail("coverage needs 4 fields")
                        coverage += LaptopCoverage(host(), long(2), long(3))
                    }
                    "span" -> {
                        if (f.size != 6) fail("span needs 6 fields")
                        val backlight = if (f[4].isEmpty()) null else double(4)
                        val warm = when (f[5]) {
                            "" -> null
                            "1" -> true
                            "0" -> false
                            else -> fail("bad night filter \"${f[5]}\"")
                        }
                        spans += LaptopSpan(host(), long(2), long(3), backlight, warm)
                    }
                    else -> fail("unknown row \"${f[0]}\"")
                }
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("line ") == true) throw e
                fail(e.message ?: "invalid row")
            }
        }
        require(header) { "empty file" }
        require(displays.map { it.host }.toSet().size == displays.size) { "a host has two display rows" }
        require(coverage.map { it.host }.toSet().size == coverage.size) { "a host has two coverage rows" }
        val covered = coverage.associateBy { it.host }
        val described = displays.map { it.host }.toSet()
        for (c in coverage) require(c.host in described) { "no display for ${c.host}" }
        for (s in spans) {
            val c = requireNotNull(covered[s.host]) { "no coverage for ${s.host}" }
            require(c.covers(s)) { "span at ${s.startTs} of ${s.host} is outside its coverage" }
        }
        require(spans.map { it.host to it.startTs }.toSet().size == spans.size) { "two spans of one host start at the same time" }
        return LaptopFile(displays, coverage, spans)
    }
}
