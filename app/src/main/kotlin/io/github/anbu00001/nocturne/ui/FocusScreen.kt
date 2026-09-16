package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.focus.FocusTimer
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.delay

/** Spec §8, Focus: the timer, and each block's unlocks stated as counts. */
@Composable
fun FocusScreen(app: NocturneApp) {
    val focus = app.focus
    val running by focus.running.collectAsStateWithLifecycle()
    val blocks by remember { app.database.focus().observeRecent(HISTORY_BLOCKS) }.collectAsStateWithLifecycle(emptyList())
    var focusMinutes by remember { mutableIntStateOf(focus.focusMinutes) }
    var breakMinutes by remember { mutableIntStateOf(focus.breakMinutes) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val offset = currentOffsetMinutes()

    LaunchedEffect(running) {
        while (running != null) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val timer = running
        if (timer != null) {
            Text(if (timer.kind == TimerKind.FOCUS) Tone.Focus.FOCUS_RUNNING else Tone.Focus.BREAK_RUNNING, style = MaterialTheme.typography.titleMedium)
            Text(countdown(timer.remainingMs(now)), style = MaterialTheme.typography.displayLarge)
            Text(Tone.Focus.endsAt(clockText(timer.endTs, offset)), color = muted)
            Button(onClick = focus::stop) { Text(Tone.Focus.STOP) }
        } else {
            Text(Tone.Focus.FOCUS_LENGTH, style = MaterialTheme.typography.titleSmall)
            MinuteChips(FocusTimer.FOCUS_MINUTES, focusMinutes) {
                focusMinutes = it
                focus.focusMinutes = it
            }
            Text(Tone.Focus.BREAK_LENGTH, style = MaterialTheme.typography.titleSmall)
            MinuteChips(FocusTimer.BREAK_MINUTES, breakMinutes) {
                breakMinutes = it
                focus.breakMinutes = it
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { focus.start(TimerKind.FOCUS) }) { Text(Tone.Focus.START_FOCUS) }
                OutlinedButton(onClick = { focus.start(TimerKind.BREAK) }) { Text(Tone.Focus.START_BREAK) }
            }
        }
        if (!focus.exactAlarms) Text(Tone.Focus.INEXACT, style = MaterialTheme.typography.bodySmall, color = muted)

        val weekFrom = System.currentTimeMillis() - 7 * LocalClock.DAY_MS
        val week = blocks.filter { it.startTs >= weekFrom }
        if (week.isNotEmpty()) Text(Tone.Focus.week(week.size, week.sumOf { it.interruptionCount }))
        Text(Tone.Focus.HISTORY, style = MaterialTheme.typography.titleMedium)
        if (blocks.isEmpty()) {
            Text(Tone.Focus.NO_BLOCKS, color = muted)
        } else {
            for (block in blocks) {
                Text(
                    Tone.Focus.block(
                        dayText(block.startTs, offset),
                        clockText(block.startTs, offset),
                        Tone.duration(block.endTs - block.startTs),
                        block.interruptionCount,
                        block.completed,
                    ),
                )
            }
        }
        Text(Tone.Focus.COUNT_NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
private fun MinuteChips(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (minutes in options) {
            FilterChip(selected = minutes == selected, onClick = { onSelect(minutes) }, label = { Text(Tone.Focus.minutes(minutes)) })
        }
    }
}

private fun countdown(ms: Long): String {
    val seconds = (ms + 999) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val HISTORY_BLOCKS = 30
