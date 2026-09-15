package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.data.NightTotals
import io.github.anbu00001.nocturne.data.SessionEntity
import io.github.anbu00001.nocturne.data.SleepReportEntity
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LastNightState(
    val nightDate: String? = null,
    val sessions: List<SessionEntity> = emptyList(),
    val totals: NightTotals? = null,
    val night: NightEntity? = null,
    val report: SleepReportEntity? = null,
    val glanceMedian: Double? = null,
    val priorNights: Int = 0,
    val hasEarlier: Boolean = false,
    val hasLater: Boolean = false,
)

class LastNightViewModel(app: NocturneApp) : ViewModel() {
    private val sessionDao = app.database.sessions()
    private val sleepDao = app.database.sleep()

    /** Null means the default: the latest night that has finished, not tonight's, which is still running. */
    private val selectedDate = MutableStateFlow<String?>(null)
    private var knownDates: List<String> = emptyList() // newest first

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LastNightState> =
        combine(sessionDao.observeNightDates(), sessionDao.observeNightTotals(), selectedDate) { dates, totals, selected ->
            knownDates = dates
            Triple(dates, totals, selected?.takeIf { it in dates } ?: latestFinishedNight(dates))
        }.flatMapLatest { (dates, totals, date) ->
            if (date == null) {
                flowOf(LastNightState())
            } else {
                val index = dates.indexOf(date)
                combine(sessionDao.observeNight(date), sleepDao.observeNight(date), sleepDao.observeReport(date)) { sessions, night, report ->
                    val prior = totals.filter { it.nightDate < date }.takeLast(MEDIAN_NIGHTS)
                    LastNightState(
                        nightDate = date,
                        sessions = sessions,
                        totals = totals.firstOrNull { it.nightDate == date },
                        night = night,
                        report = report,
                        glanceMedian = prior.takeIf { it.size >= MIN_NIGHTS_FOR_MEDIAN }?.map { it.glances }?.median(),
                        priorNights = prior.size,
                        hasEarlier = index < dates.lastIndex,
                        hasLater = index > 0,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LastNightState())

    fun showEarlier() = step(+1)
    fun showLater() = step(-1)

    private fun step(by: Int) {
        val index = knownDates.indexOf(state.value.nightDate)
        knownDates.getOrNull(index + by)?.let { selectedDate.value = it }
    }

    private fun latestFinishedNight(dates: List<String>): String? {
        val now = System.currentTimeMillis()
        val tonight = LocalClock.nightOf(now, currentOffsetMinutes()).toString()
        return dates.firstOrNull { it < tonight } ?: dates.firstOrNull()
    }

    private companion object {
        const val MEDIAN_NIGHTS = 28
        const val MIN_NIGHTS_FOR_MEDIAN = 7
    }
}

private fun List<Int>.median(): Double {
    val s = sorted()
    return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

@Composable
fun LastNightScreen(app: NocturneApp) {
    val vm = viewModel { LastNightViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var entering by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::showEarlier, enabled = state.hasEarlier) { Text(Tone.LastNight.EARLIER) }
                Spacer(Modifier.weight(1f))
                state.nightDate?.let { Text(Tone.LastNight.nightTitle(formatNight(it)), style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::showLater, enabled = state.hasLater) { Text(Tone.LastNight.LATER) }
            }
        }
        val nightDate = state.nightDate
        if (nightDate == null) {
            item { Text(Tone.LastNight.EMPTY, color = muted) }
            return@LazyColumn
        }
        val night = state.night
        val window = night?.let { EveningWindow(it.eveningWindowStartMinute, it.eveningWindowEndMinute) } ?: EveningWindow.PROVISIONAL
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.totals?.let { totals ->
                    Text(Tone.LastNight.glances(totals.glances, totals.lockScreenGlances), style = MaterialTheme.typography.headlineSmall)
                    Text(Tone.LastNight.screenTime(Tone.duration(totals.eveningScreenMs), windowText(window)))
                    Text(Tone.LastNight.sessions(totals.sessions))
                }
                Text(
                    state.glanceMedian?.let { Tone.LastNight.glanceMedian(formatNumber(it), state.priorNights) }
                        ?: Tone.LastNight.MEDIAN_PENDING,
                    color = muted,
                )
                val suppression = night?.modelledSuppressionPct
                if (night != null && suppression != null) {
                    Text(
                        Tone.Light.nightSuppression(
                            percentText(suppression),
                            percentText(night.suppressionLowPct ?: suppression),
                            percentText(night.suppressionHighPct ?: suppression),
                        ),
                    )
                    Text(Tone.Light.coverage(night.lightMeasuredMinutes, night.lightScreenMinutes), color = muted)
                    if (night.suppressionDurationClamped) {
                        Text(Tone.Light.DURATION_CLAMPED, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        item {
            SleepSummary(night, onEnter = { entering = true }, onRemove = { app.removeSleepReport(nightDate) })
        }
        item {
            NightTimeline(nightDate, state.sessions, night, window, Modifier.fillMaxWidth().height(132.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Tone.LastNight.LEGEND, style = MaterialTheme.typography.bodySmall, color = muted)
                Text(
                    if (night != null && night.windowPersonalised) Tone.LastNight.windowPersonal(night.windowNights) else Tone.LastNight.WINDOW_PROVISIONAL,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
        items(state.sessions, key = { it.startTs }) { session ->
            SessionRow(session, app.labels)
        }
    }

    val nightDate = state.nightDate
    if (entering && nightDate != null) {
        val offset = state.sessions.firstOrNull()?.utcOffsetMinutes ?: currentOffsetMinutes()
        val night = state.night
        SleepEntryDialog(
            initialOnset = night?.estimatedSleepOnset?.let { localTime(it, offset) } ?: LocalTime.MIDNIGHT,
            initialWake = night?.estimatedWakeTime?.let { localTime(it, offset) } ?: LocalTime.of(8, 0),
            onSave = { onset, wake ->
                entering = false
                reportTimes(nightDate, onset, wake, offset)?.let { (onsetTs, wakeTs) ->
                    app.saveSleepReport(nightDate, onsetTs, wakeTs, offset)
                }
            },
            onDismiss = { entering = false },
        )
    }
}

@Composable
private fun SleepSummary(night: NightEntity?, onEnter: () -> Unit, onRemove: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onset = night?.estimatedSleepOnset
    val wake = night?.estimatedWakeTime
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (night == null || onset == null || wake == null) {
            Text(Tone.Sleep.NONE, color = muted)
            OutlinedButton(onClick = onEnter) { Text(Tone.Sleep.ENTER) }
            return@Column
        }
        val offset = night.utcOffsetMinutes
        val reported = night.source == SleepSource.USER_REPORTED
        Text(
            if (reported) {
                Tone.Sleep.reported(clockText(onset, offset), clockText(wake, offset))
            } else {
                Tone.Sleep.estimated(clockText(onset, offset), clockText(wake, offset))
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (!reported) Text(Tone.Sleep.basis(confidenceLabel(night.confidence)), color = muted)
        Text(Tone.Sleep.interruptions(night.postOnsetInterruptions), color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEnter) { Text(if (reported) Tone.Sleep.CHANGE else Tone.Sleep.CORRECT) }
            if (reported) TextButton(onClick = onRemove) { Text(Tone.Sleep.REMOVE) }
        }
    }
}

/** One tap per step, preset to the estimate; the μEMA rule is that entering times never becomes a chore (spec §7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepEntryDialog(
    initialOnset: LocalTime,
    initialWake: LocalTime,
    onSave: (LocalTime, LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val onsetState = rememberTimePickerState(initialOnset.hour, initialOnset.minute, is24Hour = true)
    val wakeState = rememberTimePickerState(initialWake.hour, initialWake.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) Tone.Sleep.ONSET_TITLE else Tone.Sleep.WAKE_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = if (step == 0) onsetState else wakeState, layoutType = TimePickerLayoutType.Vertical)
                Text(Tone.Sleep.ENTRY_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (step == 0) {
                    step = 1
                } else {
                    onSave(LocalTime.of(onsetState.hour, onsetState.minute), LocalTime.of(wakeState.hour, wakeState.minute))
                }
            }) { Text(if (step == 0) Tone.Sleep.NEXT else Tone.Sleep.SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Tone.Sleep.CANCEL) } },
    )
}

/** Onsets from noon on belong to the night's own date, earlier ones to the next morning; wakes are always the next day. */
private fun reportTimes(nightDate: String, onset: LocalTime, wake: LocalTime, offsetMinutes: Int): Pair<Long, Long>? {
    val date = LocalDate.parse(nightDate)
    fun at(day: LocalDate, time: LocalTime) =
        day.toEpochDay() * LocalClock.DAY_MS + time.toSecondOfDay() * 1000L - offsetMinutes * LocalClock.MINUTE_MS
    val onsetTs = at(if (onset.hour >= LocalClock.NIGHT_BOUNDARY_HOUR) date else date.plusDays(1), onset)
    val wakeTs = at(date.plusDays(1), wake)
    return if (wakeTs > onsetTs) onsetTs to wakeTs else null
}

@Composable
private fun SessionRow(s: SessionEntity, labels: AppLabels) {
    val line = Tone.LastNight.sessionLine(
        time = clockText(s.startTs, s.utcOffsetMinutes),
        kind = Tone.kindLabel(s.kind),
        duration = Tone.duration(s.endTs - s.startTs),
        app = s.dominantPackage?.takeIf { s.appCount > 0 }?.let(labels::of),
        trigger = triggerLabel(s.trigger),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(kindColor(s.kind), CircleShape))
        Spacer(Modifier.size(10.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Noon on the night's date to noon the next day, the same boundary sessions are grouped into nights by,
 * so nothing is clamped. Positions use the local time captured with each session.
 */
@Composable
private fun NightTimeline(nightDate: String, sessions: List<SessionEntity>, night: NightEntity?, window: EveningWindow, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val boundaryHour = LocalClock.NIGHT_BOUNDARY_HOUR
    val startLocal = LocalDate.parse(nightDate).toEpochDay() * LocalClock.DAY_MS + boundaryHour * LocalClock.HOUR_MS
    val spanMs = LocalClock.DAY_MS
    fun sinceBoundary(minuteOfDay: Int) =
        Math.floorMod(minuteOfDay - boundaryHour * 60, LocalClock.MINUTES_PER_DAY) * LocalClock.MINUTE_MS

    Canvas(modifier) {
        fun x(localMs: Long) = ((localMs - startLocal).toFloat() / spanMs).coerceIn(0f, 1f) * size.width
        val tickBottom = 18.dp.toPx()
        val barTop = 26.dp.toPx()
        val barBottom = size.height - 30.dp.toPx()

        val windowStart = x(startLocal + sinceBoundary(window.startMinute))
        val windowEnd = x(startLocal + sinceBoundary(window.endMinute))
        drawRect(colors.surfaceVariant, Offset(windowStart, barTop), Size(windowEnd - windowStart, barBottom - barTop))

        for (h in 0..24 step 3) {
            val px = x(startLocal + h * LocalClock.HOUR_MS)
            drawLine(colors.outlineVariant, Offset(px, barTop), Offset(px, barBottom), strokeWidth = 1f)
            val layout = measurer.measure("%02d".format((boundaryHour + h) % 24), labelStyle)
            val left = (px - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(left, size.height - layout.size.height))
        }

        val minWidth = 1.5.dp.toPx()
        for (s in sessions) {
            val a = x(s.startTs + s.utcOffsetMinutes * LocalClock.MINUTE_MS)
            val b = x(s.endTs + s.utcOffsetMinutes * LocalClock.MINUTE_MS)
            drawRect(kindColor(s.kind), Offset(a, barTop), Size(maxOf(b - a, minWidth), barBottom - barTop))
            if (s.countsAsGlance) {
                drawLine(colors.primary, Offset(a, 0f), Offset(a, tickBottom), strokeWidth = minWidth)
            }
        }

        val onset = night?.estimatedSleepOnset
        val wake = night?.estimatedWakeTime
        if (night != null && onset != null && wake != null) {
            val shift = night.utcOffsetMinutes * LocalClock.MINUTE_MS
            val a = x(onset + shift)
            val b = x(wake + shift)
            val height = 4.dp.toPx()
            drawRoundRect(
                colors.secondary,
                Offset(a, barBottom + 5.dp.toPx()),
                Size(maxOf(b - a, minWidth), height),
                CornerRadius(height / 2),
            )
        }
    }
}

private val nightFormat = DateTimeFormatter.ofPattern("EEE d MMM")

private fun formatNight(isoDate: String): String = LocalDate.parse(isoDate).format(nightFormat)

fun clockText(ts: Long, offsetMinutes: Int): String = minuteText(LocalClock.minuteOfDay(ts, offsetMinutes))

fun minuteText(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

fun windowText(window: EveningWindow): String = Tone.clockRange(minuteText(window.startMinute), minuteText(window.endMinute))

fun currentOffsetMinutes(): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(System.currentTimeMillis())).totalSeconds / 60

private fun localTime(ts: Long, offsetMinutes: Int): LocalTime =
    LocalTime.ofSecondOfDay(LocalClock.minuteOfDay(ts, offsetMinutes) * 60L)

fun confidenceLabel(confidence: Float): String = when {
    confidence < 0.4f -> Tone.Sleep.CONFIDENCE_LOW
    confidence < 0.7f -> Tone.Sleep.CONFIDENCE_MEDIUM
    else -> Tone.Sleep.CONFIDENCE_HIGH
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

private fun triggerLabel(trigger: WakeTrigger): String? = when (trigger) {
    WakeTrigger.UNKNOWN -> null
    WakeTrigger.NOTIFICATION -> Tone.Trigger.NOTIFICATION
    WakeTrigger.ALARM -> Tone.Trigger.ALARM
    WakeTrigger.CALL -> Tone.Trigger.CALL
}
