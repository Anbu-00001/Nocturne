package io.github.anbu00001.nocturne.data

import io.github.anbu00001.nocturne.core.focus.FocusTimer
import io.github.anbu00001.nocturne.core.focus.RunningTimer

/** Focus blocks (spec §7): what was planned, when it ended, and the unlocks inside it. */
class FocusBlocks(private val db: NocturneDatabase) {

    /** Records a block that ran to [endTs], counting unlocks as sessions stand now; each later harvest recounts it. */
    suspend fun record(timer: RunningTimer, endTs: Long, completed: Boolean): FocusBlockEntity {
        val unlocks = db.sessions().unlockStartsBetween(timer.startTs, endTs)
        val block = FocusBlockEntity(
            startTs = timer.startTs,
            endTs = endTs,
            plannedMinutes = timer.plannedMinutes,
            completed = completed,
            interruptionCount = FocusTimer.interruptions(unlocks, timer.startTs, endTs),
            label = null,
        )
        return block.copy(id = db.focus().insert(block))
    }
}
