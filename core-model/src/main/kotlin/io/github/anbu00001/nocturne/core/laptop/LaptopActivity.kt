package io.github.anbu00001.nocturne.core.laptop

/** Laptop use as sleep inference sees it: when someone was at any computer, whichever it was. */
object LaptopActivity {

    /** The union of [spans] across hosts, as sorted, disjoint ranges from start to end. */
    fun merged(spans: List<LaptopSpan>): List<LongRange> {
        val merged = ArrayList<LongRange>()
        for (s in spans.sortedBy { it.startTs }) {
            val last = merged.lastOrNull()
            if (last != null && s.startTs <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, s.endTs)
            } else {
                merged += s.startTs..s.endTs
            }
        }
        return merged
    }

    /** Milliseconds of the disjoint ranges [merged] inside [fromTs] until [toTs]. */
    fun overlapMs(merged: List<LongRange>, fromTs: Long, toTs: Long): Long =
        merged.sumOf { r -> maxOf(0L, minOf(r.last, toTs) - maxOf(r.first, fromTs)) }
}
