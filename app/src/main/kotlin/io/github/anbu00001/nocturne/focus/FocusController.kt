package io.github.anbu00001.nocturne.focus

import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.focus.RunningTimer
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.FocusBlocks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The focus timer (spec §7): at most one timer, kept in [TimerStore], ended by [FocusAlarms], announced by
 * [FocusSignals]. There is no service: the alarm survives the process, and a reboot, which clears alarms, is handled
 * when the app next starts ([resume]). Ending a focus block harvests first, so the unlocks recorded with it reach its
 * last minute.
 */
class FocusController(private val app: NocturneApp) {

    private val store = TimerStore(app)
    private val alarms = FocusAlarms(app)
    private val signals = FocusSignals(app)
    private val state = MutableStateFlow(store.load())

    val running: StateFlow<RunningTimer?> = state.asStateFlow()

    var focusMinutes: Int by store::focusMinutes
    var breakMinutes: Int by store::breakMinutes

    val exactAlarms: Boolean get() = alarms.exact

    /** Does nothing while a timer runs. [minutes] other than the chosen length is for the debug receiver's short test blocks. */
    @Synchronized
    fun start(kind: TimerKind, minutes: Int = if (kind == TimerKind.FOCUS) focusMinutes else breakMinutes) {
        if (state.value != null) return
        val timer = RunningTimer(kind, System.currentTimeMillis(), minutes)
        store.save(timer)
        state.value = timer
        alarms.set(timer)
    }

    /** Stopped before its end: a focus block is recorded up to now, a break is not recorded. */
    fun stop() {
        val timer = take(null) ?: return
        alarms.cancel(timer)
        val at = System.currentTimeMillis()
        if (timer.kind == TimerKind.FOCUS) app.appScope.launch { conclude(timer, minOf(at, timer.endTs), completed = at >= timer.endTs, announce = false) }
    }

    /**
     * The alarm for the timer started at [startTs] fired, or the app started after the timer's end. Returns the
     * recording still under way, or null when there was nothing to finish (a stale alarm, or already finished).
     * A timer that ended while the phone was off is recorded without a signal.
     */
    fun finish(startTs: Long?): Job? {
        val timer = take(startTs) ?: return null
        val onTime = System.currentTimeMillis() - timer.endTs <= SIGNAL_GRACE_MS
        if (onTime) signals.ended()
        return app.appScope.launch { conclude(timer, timer.endTs, completed = true, announce = onTime) }
    }

    /** At process start: finish a timer whose end has passed, or set its alarm again (a reboot clears alarms). */
    fun resume() {
        val timer = state.value ?: return
        if (timer.finished(System.currentTimeMillis())) finish(timer.startTs) else alarms.set(timer)
    }

    private suspend fun conclude(timer: RunningTimer, endTs: Long, completed: Boolean, announce: Boolean) {
        val unlocks = if (timer.kind == TimerKind.FOCUS) {
            app.harvester.harvest()
            FocusBlocks(app.database).record(timer, endTs, completed).interruptionCount
        } else {
            0
        }
        if (announce) signals.recorded(timer, unlocks)
    }

    /** Takes the running timer, if it is the one started at [startTs] (any, when null): the alarm and a restart cannot both finish it. */
    @Synchronized
    private fun take(startTs: Long?): RunningTimer? {
        val timer = state.value ?: return null
        if (startTs != null && timer.startTs != startTs) return null
        store.clear()
        state.value = null
        return timer
    }

    private companion object {
        /** An alarm this late (the phone was off at the end, say) is recorded without buzzing. */
        const val SIGNAL_GRACE_MS = 2 * LocalClock.MINUTE_MS
    }
}
