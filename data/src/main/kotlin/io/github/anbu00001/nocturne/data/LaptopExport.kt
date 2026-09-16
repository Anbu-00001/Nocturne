package io.github.anbu00001.nocturne.data

import androidx.room.withTransaction

/**
 * Phase 4: what the laptop reads to put the phone beside its own use in ActivityWatch. Both are derived rows, read in
 * one transaction so sessions and nights agree; the laptop replaces what it holds on every read. Returns rows written.
 */
object LaptopExport {
    const val SESSIONS_HEADER = "startTs,endTs,lastActivityTs,kind,unlocked,trigger,countsAsGlance,dominantPackage,utcOffsetMinutes,nightDate"
    const val NIGHTS_HEADER =
        "dateOfNight,onsetTs,wakeTs,confidence,source,noSleep,utcOffsetMinutes,suppressionLowPct,suppressionPct,suppressionHighPct," +
            "eveningScreenMinutes,lightLaptopMinutes"

    /** Sessions starting at or after [fromTs]. Package names never hold commas, so no field needs quoting. */
    suspend fun sessions(db: NocturneDatabase, fromTs: Long, out: Appendable): Int = db.withTransaction {
        out.append(SESSIONS_HEADER).append('\n')
        val rows = db.sessions().startingFrom(fromTs)
        for (s in rows) {
            out.append(
                "${s.startTs},${s.endTs},${maxOf(s.lastActivityTs, s.startTs)},${s.kind},${s.unlocked},${s.trigger},${s.countsAsGlance}," +
                    "${s.dominantPackage.orEmpty()},${s.utcOffsetMinutes},${s.nightDate}\n",
            )
        }
        rows.size
    }

    /** Nights from [fromDate] (ISO) on; empty fields where a night has no value. */
    suspend fun nights(db: NocturneDatabase, fromDate: String, out: Appendable): Int = db.withTransaction {
        out.append(NIGHTS_HEADER).append('\n')
        val rows = db.sleep().nightsFrom(fromDate)
        for (n in rows) {
            out.append(
                "${n.dateOfNight},${n.estimatedSleepOnset ?: ""},${n.estimatedWakeTime ?: ""},${n.confidence},${n.source},${n.noSleep}," +
                    "${n.utcOffsetMinutes},${n.suppressionLowPct ?: ""},${n.modelledSuppressionPct ?: ""},${n.suppressionHighPct ?: ""}," +
                    "${n.eveningScreenMinutes},${n.lightLaptopMinutes}\n",
            )
        }
        rows.size
    }
}
