package io.github.anbu00001.nocturne.data

import androidx.room.withTransaction
import io.github.anbu00001.nocturne.core.laptop.LaptopDisplay
import io.github.anbu00001.nocturne.core.laptop.LaptopFile
import io.github.anbu00001.nocturne.core.laptop.LaptopSpan

/**
 * Phase 4: laptop use sent from the laptop. Each host's spans starting inside its coverage are replaced by the file's,
 * so sending the same week twice changes nothing, and a span still growing when it was sent is corrected by the next
 * send. Returns the earliest instant whose nights can differ, for the caller to re-derive in the same transaction.
 */
class LaptopImport(private val db: NocturneDatabase, private val now: () -> Long = System::currentTimeMillis) {

    data class Result(
        val hosts: List<String>,
        val spans: Int,
        /** Spans new or different since the last import of the same range, and spans gone from it. */
        val added: Int,
        val removed: Int,
        /** Null when nothing a night depends on changed. */
        val changedFromTs: Long?,
    )

    suspend fun import(file: LaptopFile): Result = db.withTransaction {
        val dao = db.laptop()
        val at = now()
        val displays = file.displays.associateBy { it.host }
        var added = 0
        var removed = 0
        var changedFromTs: Long? = null
        fun changed(ts: Long) {
            changedFromTs = minOf(changedFromTs ?: ts, ts)
        }
        for (coverage in file.coverage) {
            val display = displays.getValue(coverage.host)
            val old = dao.spansStarting(coverage.host, coverage.fromTs, coverage.toTs).toSet()
            val new = file.spans.filter { coverage.covers(it) }.map { it.toEntity() }.toSet()
            (old - new).forEach { changed(it.startTs) }
            (new - old).forEach { changed(it.startTs) }
            removed += (old - new).size
            added += (new - old).size
            dao.deleteSpans(coverage.host, coverage.fromTs, coverage.toTs)
            dao.insertSpans(new.sortedBy { it.startTs })

            val previous = dao.host(coverage.host)
            // A corrected panel changes the light of every night this laptop was used on.
            if (previous != null && previous.display() != display) changed(previous.coveredFromTs)
            dao.upsertHost(
                LaptopHostEntity(
                    host = coverage.host,
                    widthMm = display.widthMm,
                    heightMm = display.heightMm,
                    minNits = display.minNits,
                    peakNits = display.peakNits,
                    coveredFromTs = minOf(previous?.coveredFromTs ?: coverage.fromTs, coverage.fromTs),
                    coveredToTs = maxOf(previous?.coveredToTs ?: coverage.toTs, coverage.toTs),
                    lastImportAt = at,
                    lastImportSpans = new.size,
                ),
            )
        }
        Result(file.coverage.map { it.host }, file.spans.size, added, removed, changedFromTs)
    }
}

internal fun LaptopSpan.toEntity() = LaptopSpanEntity(host, startTs, endTs, backlight, warmFilter)

internal fun LaptopSpanEntity.toLaptopSpan() = LaptopSpan(host, startTs, endTs, backlight, warmFilter)

internal fun LaptopHostEntity.display() = LaptopDisplay(host, widthMm, heightMm, minNits, peakNits)
