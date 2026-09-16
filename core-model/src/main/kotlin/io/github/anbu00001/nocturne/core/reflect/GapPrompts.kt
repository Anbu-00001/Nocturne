package io.github.anbu00001.nocturne.core.reflect

import io.github.anbu00001.nocturne.core.time.LocalClock

/** One tap on a gap card (spec §7), stored as reflections.rating. */
enum class GapLabel(val rating: Int) {
    DEEP_WORK(1),
    LIGHT_WORK(2),
    REST(3),
    NOT_SURE(4),
    ;

    companion object {
        fun ofRating(rating: Int?): GapLabel? = entries.firstOrNull { it.rating == rating }
    }
}

/** Where a reflection came from. Only prompts count against the caps; labelling from the weekly list is the user's own doing. */
enum class ReflectionSource { PROMPT, BACKFILL }

/** A reflections row as the prompt rules see it. */
data class GapReflection(
    val id: Long,
    val source: ReflectionSource,
    val promptedAt: Long,
    val gapStartTs: Long?,
    val gapEndTs: Long?,
    val label: GapLabel?,
    val dismissed: Boolean,
) {
    /** Answered or dismissed: either way it is never asked about again. */
    val settled: Boolean get() = label != null || dismissed

    fun covers(gap: PhoneDownGap): Boolean = gapStartTs != null && gapEndTs != null && gapStartTs < gap.endTs && gap.startTs < gapEndTs
}

sealed interface GapCard {
    /** A card already asked and not yet answered, shown until the next prompt could be asked. Not a new prompt. */
    data class Open(val reflection: GapReflection) : GapCard

    /** A new prompt. Recording it is what counts against the caps. */
    data class Ask(val gap: PhoneDownGap) : GapCard
}

/**
 * Spec §7's rules for the one-tap card, all in one place. The card is never a notification: it waits for the next time
 * Nocturne is open, so asking cannot end the gap it asks about (rule 1).
 */
object GapPrompts {
    /** Rule 2: at most one prompt in 3 h. */
    const val MIN_SPACING_MS = 3 * LocalClock.HOUR_MS

    /** Rule 2: at most 4 prompts a local day. */
    const val MAX_PER_DAY = 4

    /** A card asks about a gap that ended in the last 12 h; older gaps wait in the weekly list, where recall is not the point. */
    const val FRESH_MS = 12 * LocalClock.HOUR_MS

    /** Rule 5: the weekly list reaches back 7 days. */
    const val BACKFILL_MS = 7 * LocalClock.DAY_MS

    /**
     * The card to show at [now], or null. [atNight] is true inside the evening window (rule 6), where nothing is shown.
     * A dismissed or answered gap never comes back (rule 3). Prompts are counted by the local day at [offsetMinutes].
     */
    fun card(now: Long, offsetMinutes: Int, atNight: Boolean, gaps: List<PhoneDownGap>, reflections: List<GapReflection>): GapCard? {
        if (atNight) return null
        val prompts = reflections.filter { it.source == ReflectionSource.PROMPT }
        val last = prompts.maxByOrNull { it.promptedAt }
        if (last != null && now - last.promptedAt < MIN_SPACING_MS) return if (last.settled) null else GapCard.Open(last)
        val today = localDay(now, offsetMinutes)
        if (prompts.count { localDay(it.promptedAt, offsetMinutes) == today } >= MAX_PER_DAY) return null
        return gaps
            .filter { it.endTs <= now && now - it.endTs <= FRESH_MS }
            .filter { gap -> reflections.none { it.covers(gap) } }
            .maxByOrNull { it.endTs }
            ?.let(GapCard::Ask)
    }

    /** Rule 5: the week's gaps with no label and not dismissed, newest first. A card shown but left unanswered is among them. */
    fun unlabelled(now: Long, gaps: List<PhoneDownGap>, reflections: List<GapReflection>): List<PhoneDownGap> = gaps
        .filter { it.endTs <= now && now - it.endTs <= BACKFILL_MS }
        .filter { gap -> reflections.none { it.settled && it.covers(gap) } }
        .sortedByDescending { it.endTs }

    private fun localDay(ts: Long, offsetMinutes: Int): Long = Math.floorDiv(LocalClock.localMillis(ts, offsetMinutes), LocalClock.DAY_MS)
}
