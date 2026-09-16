package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.reflect.GapLabel
import io.github.anbu00001.nocturne.core.reflect.PhoneDownGap
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.Reflections
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Spec §7: the one-tap card, above every tab. It is looked for once each time Nocturne comes to the front, after that
 * visit's harvest, so the gap that just ended is known. It never appears as a notification.
 */
@Composable
fun GapCardHost(app: NocturneApp, harvest: Job?) {
    var card by remember { mutableStateOf<Reflections.Card?>(null) }
    var laptopMs by remember { mutableStateOf(0L) }
    var answered by remember { mutableStateOf<GapLabel?>(null) }
    LaunchedEffect(harvest) {
        harvest?.join()
        // An answered card is not offered again, so each visit starts from what the rules say now.
        val (found, atLaptop) = withContext(Dispatchers.IO) {
            val next = app.reflections.card(app.deviceProfile.sleepConfig())
            next to (next?.let { app.reflections.laptopMs(it.gap) } ?: 0L)
        }
        card = found
        laptopMs = atLaptop
        answered = null
    }
    val shown = card ?: return
    val offset = currentOffsetMinutes()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Tone.Reflect.phoneDown(clockText(shown.gap.startTs, offset), clockText(shown.gap.endTs, offset)), style = MaterialTheme.typography.titleMedium)
            if (laptopMs >= LocalClock.MINUTE_MS) {
                Text(Tone.Reflect.atLaptop(Tone.duration(laptopMs)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val label = answered
            if (label == null) {
                LabelButtons { chosen ->
                    answered = chosen
                    app.appScope.launch { app.reflections.answer(shown.reflectionId, chosen) }
                }
                TextButton(
                    onClick = {
                        card = null
                        app.appScope.launch { app.reflections.dismiss(shown.reflectionId) }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(Tone.Reflect.DISMISS) }
            } else {
                AnsweredCard(label, onNote = { note -> app.appScope.launch { app.reflections.note(shown.reflectionId, note) } }, onDone = { card = null })
            }
        }
    }
}

/** After the tap: the note is behind a button, and the field never takes focus by itself (rule 4). */
@Composable
private fun AnsweredCard(label: GapLabel, onNote: (String) -> Unit, onDone: () -> Unit) {
    var writing by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    Text(Tone.Reflect.labelled(Tone.Reflect.label(label)), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (writing) {
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(Tone.Reflect.NOTE_FIELD) }, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (writing) {
            TextButton(onClick = {
                onNote(note)
                onDone()
            }) { Text(Tone.Reflect.SAVE_NOTE) }
        } else {
            TextButton(onClick = { writing = true }) { Text(Tone.Reflect.ADD_NOTE) }
        }
        TextButton(onClick = onDone) { Text(Tone.Reflect.DONE) }
    }
}

@Composable
private fun LabelButtons(onLabel: (GapLabel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in GapLabel.entries.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (label in row) {
                    OutlinedButton(onClick = { onLabel(label) }, modifier = Modifier.weight(1f)) { Text(Tone.Reflect.label(label), maxLines = 1) }
                }
            }
        }
    }
}

/** Rule 5 in Patterns: the week's labelled time, and a quiet way to label the gaps no card asked about. */
@Composable
fun GapsSection(app: NocturneApp) {
    var unlabelled by remember { mutableStateOf<List<PhoneDownGap>?>(null) }
    var laptop by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var totals by remember { mutableStateOf<Map<GapLabel, Long>>(emptyMap()) }
    var open by rememberSaveable { mutableStateOf(false) }
    var labelled by remember { mutableIntStateOf(0) }
    LaunchedEffect(labelled) {
        val (gaps, byLabel) = withContext(Dispatchers.IO) {
            app.reflections.unlabelled(app.deviceProfile.sleepConfig()) to app.reflections.weekByLabel()
        }
        laptop = withContext(Dispatchers.IO) { gaps.associate { it.startTs to app.reflections.laptopMs(it) } }
        unlabelled = gaps
        totals = byLabel
    }
    val gaps = unlabelled ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val offset = currentOffsetMinutes()
    Text(Tone.Reflect.WEEK, style = MaterialTheme.typography.titleMedium)
    for (label in GapLabel.entries) {
        totals[label]?.let { Text(Tone.Reflect.labelledTime(Tone.Reflect.label(label), Tone.duration(it))) }
    }
    if (gaps.isEmpty()) {
        Text(Tone.Reflect.ALL_LABELLED, color = muted)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tone.Reflect.unlabelled(gaps.size), color = muted)
            TextButton(onClick = { open = !open }) { Text(if (open) Tone.Reflect.HIDE_UNLABELLED else Tone.Reflect.SHOW_UNLABELLED) }
        }
        if (open) {
            for (gap in gaps) {
                key(gap.startTs) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val atLaptop = laptop[gap.startTs]?.takeIf { it >= LocalClock.MINUTE_MS }?.let(Tone::duration)
                        Text(Tone.Reflect.gapLine(dayText(gap.startTs, offset), clockText(gap.startTs, offset), clockText(gap.endTs, offset), Tone.duration(gap.durationMs), atLaptop))
                        LabelButtons { label ->
                            unlabelled = gaps - gap
                            app.appScope.launch {
                                app.reflections.label(gap, label)
                                labelled++
                            }
                        }
                    }
                }
            }
        }
    }
    Text(Tone.Reflect.WEEK_NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

fun dayText(ts: Long, offsetMinutes: Int): String =
    LocalDate.ofEpochDay(Math.floorDiv(LocalClock.localMillis(ts, offsetMinutes), LocalClock.DAY_MS)).format(dayFormat)
