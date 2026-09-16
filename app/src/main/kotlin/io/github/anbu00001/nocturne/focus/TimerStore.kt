package io.github.anbu00001.nocturne.focus

import android.content.Context
import androidx.core.content.edit
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.focus.FocusTimer
import io.github.anbu00001.nocturne.core.focus.RunningTimer
import io.github.anbu00001.nocturne.core.focus.TimerKind

/** The running timer and the chosen lengths, kept in preferences so they outlive the process. */
internal class TimerStore(context: Context) {

    private val prefs = context.getSharedPreferences(NocturneApp.PREFS, Context.MODE_PRIVATE)

    var focusMinutes: Int
        get() = prefs.getInt(KEY_FOCUS_LENGTH, FocusTimer.DEFAULT_FOCUS_MINUTES)
        set(value) = prefs.edit { putInt(KEY_FOCUS_LENGTH, value) }

    var breakMinutes: Int
        get() = prefs.getInt(KEY_BREAK_LENGTH, FocusTimer.DEFAULT_BREAK_MINUTES)
        set(value) = prefs.edit { putInt(KEY_BREAK_LENGTH, value) }

    fun load(): RunningTimer? {
        val start = prefs.getLong(KEY_START, -1)
        val kind = prefs.getString(KEY_KIND, null)?.let { name -> TimerKind.entries.firstOrNull { it.name == name } }
        return if (start < 0 || kind == null) null else RunningTimer(kind, start, prefs.getInt(KEY_MINUTES, FocusTimer.DEFAULT_FOCUS_MINUTES))
    }

    /** Written before the alarm is set, synchronously, so a process killed straight after still knows the timer. */
    fun save(timer: RunningTimer) = prefs.edit(commit = true) {
        putString(KEY_KIND, timer.kind.name)
        putLong(KEY_START, timer.startTs)
        putInt(KEY_MINUTES, timer.plannedMinutes)
    }

    fun clear() = prefs.edit(commit = true) {
        remove(KEY_KIND)
        remove(KEY_START)
        remove(KEY_MINUTES)
    }

    private companion object {
        const val KEY_KIND = "focusKind"
        const val KEY_START = "focusStart"
        const val KEY_MINUTES = "focusMinutes"
        const val KEY_FOCUS_LENGTH = "focusLength"
        const val KEY_BREAK_LENGTH = "breakLength"
    }
}
