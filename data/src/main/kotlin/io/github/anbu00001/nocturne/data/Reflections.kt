package io.github.anbu00001.nocturne.data

import androidx.room.withTransaction
import io.github.anbu00001.nocturne.core.light.EveningLight
import io.github.anbu00001.nocturne.core.reflect.GapCard
import io.github.anbu00001.nocturne.core.reflect.GapLabel
import io.github.anbu00001.nocturne.core.reflect.GapPrompts
import io.github.anbu00001.nocturne.core.reflect.GapReflection
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGap
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGaps
import io.github.anbu00001.nocturne.core.reflect.ReflectionSource
import io.github.anbu00001.nocturne.core.sleep.SleepConfig
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.core.time.ZoneChange
import io.github.anbu00001.nocturne.core.time.ZoneTimeline
import java.time.LocalDate
import java.time.ZoneId

/** Spec §7 against the database: which phone-down gap to ask about, and what the user said. */
class Reflections(
    private val db: NocturneDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val fallbackZoneId: () -> String = { ZoneId.systemDefault().id },
) {
    /** A card to show, with the reflections row its prompt is recorded in. */
    data class Card(val reflectionId: Long, val gap: PhoneDownGap)

    /** The card for now, if any. Asking records the prompt in the same transaction, so two screens cannot both ask. */
    suspend fun card(config: SleepConfig): Card? = db.withTransaction {
        val at = now()
        val view = load(at, config)
        when (val card = GapPrompts.card(at, view.offsetMinutes, view.atNight, view.gaps, view.reflections)) {
            null -> null
            is GapCard.Open -> card.reflection.let { r -> Card(r.id, PhoneDownGap(r.gapStartTs!!, r.gapEndTs!!)) }
            is GapCard.Ask -> Card(db.reflections().insert(entity(card.gap, promptedAt = at, source = ReflectionSource.PROMPT)), card.gap)
        }
    }

    /** What [card] would do now, without recording a prompt: for checking the rules on the phone. */
    suspend fun preview(config: SleepConfig): GapCard? {
        val at = now()
        val view = load(at, config)
        return GapPrompts.card(at, view.offsetMinutes, view.atNight, view.gaps, view.reflections)
    }

    suspend fun answer(reflectionId: Long, label: GapLabel) = db.reflections().answer(reflectionId, label.rating, now())

    /** Rule 3: recorded, and never asked again. */
    suspend fun dismiss(reflectionId: Long) = db.reflections().dismiss(reflectionId, now())

    /** Rule 4: optional, and only ever added to a gap already asked about or labelled. */
    suspend fun note(reflectionId: Long, note: String) = db.reflections().note(reflectionId, note.trim().ifEmpty { null })

    /** Rule 5: the week's gaps with no label, newest first. */
    suspend fun unlabelled(config: SleepConfig): List<PhoneDownGap> {
        val at = now()
        val view = load(at, config)
        return GapPrompts.unlabelled(at, view.gaps, view.reflections)
    }

    /** Labels a gap from the weekly list: the card that asked about it, if one did, else a new row. Returns the row. */
    suspend fun label(gap: PhoneDownGap, label: GapLabel): Long = db.withTransaction {
        val at = now()
        val asked = db.reflections().since(gap.startTs - GapPrompts.BACKFILL_MS).map { it.toGapReflection() }
            .firstOrNull { !it.settled && it.covers(gap) }
        if (asked != null) {
            db.reflections().answer(asked.id, label.rating, at)
            asked.id
        } else {
            db.reflections().insert(entity(gap, promptedAt = at, source = ReflectionSource.BACKFILL).copy(answeredAt = at, rating = label.rating))
        }
    }

    /** Labelled time among gaps that ended in the last 7 days. */
    suspend fun weekByLabel(): Map<GapLabel, Long> {
        val from = now() - GapPrompts.BACKFILL_MS
        return db.reflections().since(from)
            .filter { (it.gapEndTs ?: Long.MIN_VALUE) >= from && it.gapStartTs != null && !it.dismissed }
            .mapNotNull { row -> GapLabel.ofRating(row.rating)?.let { it to row.gapEndTs!! - row.gapStartTs!! } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, spans) -> spans.sum() }
    }

    private class View(val offsetMinutes: Int, val atNight: Boolean, val gaps: List<PhoneDownGap>, val reflections: List<GapReflection>)

    private suspend fun load(at: Long, config: SleepConfig): View {
        val from = at - GapPrompts.BACKFILL_MS - LocalClock.DAY_MS
        val zones = db.harvest().zones().map { ZoneChange(it.sinceTs, it.zoneId) }.ifEmpty { listOf(ZoneChange(0, fallbackZoneId())) }
        val offset = ZoneTimeline(zones).offsetMinutesAt(at)
        val nights = db.sleep().nightsFrom(LocalClock.nightOf(from, offset).minusDays(1).toString())
        val quiet = quietIntervals(nights)
        val window = nights.lastOrNull()?.let { EveningWindow(it.eveningWindowStartMinute, it.eveningWindowEndMinute) } ?: EveningWindow.PROVISIONAL
        val sessions = db.sessions().startingFrom(from).map { it.toNightSession() }
        return View(
            offsetMinutes = offset,
            atNight = window.contains(at, offset),
            gaps = PhoneDownGaps.find(sessions, quiet, config),
            reflections = db.reflections().since(from).map { it.toGapReflection() },
        )
    }

    private fun entity(gap: PhoneDownGap, promptedAt: Long, source: ReflectionSource) = ReflectionEntity(
        promptedAt = promptedAt,
        answeredAt = null,
        gapStartTs = gap.startTs,
        gapEndTs = gap.endTs,
        rating = null,
        note = null,
        dismissed = false,
        source = source,
    )
}

/** Sleep and the evening windows are the night being measured: no gap touching them is asked about (spec §7, rule 6). */
internal fun quietIntervals(nights: List<NightEntity>): List<LongRange> = nights.flatMap { night ->
    val window = EveningWindow(night.eveningWindowStartMinute, night.eveningWindowEndMinute)
    listOfNotNull(
        EveningLight.interval(LocalDate.parse(night.dateOfNight), night.utcOffsetMinutes, window, null, Long.MAX_VALUE)?.let { it.startTs until it.endTs },
        night.estimatedSleepOnset?.let { onset -> night.estimatedWakeTime?.takeIf { it > onset }?.let { onset until it } },
    )
}

internal fun ReflectionEntity.toGapReflection() =
    GapReflection(id, source, promptedAt, gapStartTs, gapEndTs, GapLabel.ofRating(rating), dismissed)
