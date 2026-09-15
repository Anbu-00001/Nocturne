package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.data.NightTotals
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

/** The payoff screen (spec §8): every harvested night, however long ago. */
class PatternsViewModel(app: NocturneApp) : ViewModel() {
    /** Null until the first read, so "no nights yet" is not flashed while loading. */
    val nights: StateFlow<List<NightTotals>?> = app.database.sessions().observeNightTotals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sleep: StateFlow<List<NightEntity>> = app.database.sleep().observeNights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Vico: the producer a chart uses must not be replaced, so it lives as long as the screen's view model.
    val glancesProducer = CartesianChartModelProducer()
    val eveningProducer = CartesianChartModelProducer()
}

@Composable
fun PatternsScreen(app: NocturneApp) {
    val vm = viewModel { PatternsViewModel(app) }
    val loaded by vm.nights.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val nights = loaded ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (nights.isEmpty()) {
            Text(Tone.Patterns.EMPTY, color = muted)
        } else {
            Text(
                Tone.Patterns.range(nights.size, shortDate(nights.first().nightDate), shortDate(nights.last().nightDate)),
                color = muted,
            )
            SleepChart(sleep)
            NightColumnChart(Tone.Patterns.GLANCES_PER_NIGHT, vm.glancesProducer, nights) { it.glances }
            NightColumnChart(Tone.Patterns.EVENING_MINUTES, vm.eveningProducer, nights) { it.eveningScreenMs / 60_000 }
        }
    }
}

private const val SLEEP_CHART_NIGHTS = 28L

/**
 * One floating bar per night from sleep onset down to wake, on a clock axis that runs down the page like a
 * sleep diary. A single series: the title names it; lighter bars mark estimates and the note says so in
 * words, so the distinction never rests on shade alone. Tapping a bar shows its exact times as text.
 */
@Composable
private fun SleepChart(nights: List<NightEntity>) {
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurfaceVariant
    Text(Tone.Patterns.SLEEP, style = MaterialTheme.typography.titleMedium)
    val slept = nights.filter { it.noSleep || (it.estimatedSleepOnset != null && it.estimatedWakeTime != null) }
    if (slept.isEmpty()) {
        Text(Tone.Patterns.SLEEP_EMPTY, color = muted)
        return
    }
    val byDate = slept.associateBy { LocalDate.parse(it.dateOfNight) }
    val last = byDate.keys.max()
    val first = maxOf(byDate.keys.min(), last.minusDays(SLEEP_CHART_NIGHTS - 1))
    val slots = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()

    // Minutes after noon on each night's own date, so a 13:00 wake sits below a 01:00 onset.
    fun sinceNoon(date: LocalDate, ts: Long, offsetMinutes: Int): Long {
        val noonUtc = date.toEpochDay() * LocalClock.DAY_MS + LocalClock.NIGHT_BOUNDARY_HOUR * LocalClock.HOUR_MS -
            offsetMinutes * LocalClock.MINUTE_MS
        return (ts - noonUtc) / LocalClock.MINUTE_MS
    }
    val spans = slots.mapNotNull { date ->
        val n = byDate[date] ?: return@mapNotNull null
        val onset = n.estimatedSleepOnset ?: return@mapNotNull null
        val wake = n.estimatedWakeTime ?: return@mapNotNull null
        date to (sinceNoon(date, onset, n.utcOffsetMinutes) to sinceNoon(date, wake, n.utcOffsetMinutes))
    }.toMap()
    val step = 120.0
    // Sleepless nights have no span; with only those, the axis shows an ordinary night, 22:00 to 06:00.
    val top = spans.values.minOfOrNull { it.first }?.let { (floor(it / step) * step).toLong() } ?: (10 * 60L)
    val bottom = maxOf(spans.values.maxOfOrNull { it.second }?.let { (ceil(it / step) * step).toLong() } ?: 0L, top + 8 * 60)

    var selected by remember(slept) { mutableStateOf(last) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val axisWidth = 36.dp
    val xLabelHeight = 20.dp

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(slots) {
                detectTapGestures { tap ->
                    val slotWidth = (size.width - axisWidth.toPx()) / slots.size
                    val index = ((tap.x - axisWidth.toPx()) / slotWidth).toInt()
                    slots.getOrNull(index)?.takeIf { it in byDate }?.let { selected = it }
                }
            },
    ) {
        val left = axisWidth.toPx()
        // Half a label of air above and below, so the first and last clock labels centre on their gridlines.
        val pad = measurer.measure("00:00", labelStyle).size.height / 2f
        val plotTop = pad
        val plotHeight = size.height - xLabelHeight.toPx() - 2 * pad
        fun y(minutes: Long) = plotTop + (minutes - top).toFloat() / (bottom - top) * plotHeight

        var tick = top
        while (tick <= bottom) {
            val ty = y(tick)
            drawLine(colors.outlineVariant, Offset(left, ty), Offset(size.width, ty), strokeWidth = 1f)
            val layout = measurer.measure(minuteText(Math.floorMod(tick + 12 * 60, LocalClock.MINUTES_PER_DAY.toLong()).toInt()), labelStyle)
            drawText(layout, topLeft = Offset(0f, ty - layout.size.height / 2f))
            tick += step.toLong()
        }

        val slotWidth = (size.width - left) / slots.size
        val barWidth = minOf(slotWidth - 2.dp.toPx(), 24.dp.toPx()).coerceAtLeast(2f)
        val radius = CornerRadius(minOf(4.dp.toPx(), barWidth / 2))
        slots.forEachIndexed { i, date ->
            val n = byDate[date] ?: return@forEachIndexed
            val reported = n.source == SleepSource.USER_REPORTED
            val alpha = when {
                reported -> 1f
                date == selected -> 0.8f
                else -> 0.45f
            }
            val cx = left + (i + 0.5f) * slotWidth
            if (n.noSleep) {
                val r = minOf(barWidth / 2, 6.dp.toPx())
                drawCircle(colors.primary.copy(alpha = maxOf(alpha, 0.6f)), r, Offset(cx, plotTop + r + 2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                return@forEachIndexed
            }
            val (onset, wake) = spans[date] ?: return@forEachIndexed
            drawRoundRect(
                colors.primary.copy(alpha = alpha),
                Offset(cx - barWidth / 2, y(onset)),
                Size(barWidth, y(wake) - y(onset)),
                radius,
            )
        }

        listOf(slots.first(), slots.last()).distinct().forEach { date ->
            val i = slots.indexOf(date)
            val layout = measurer.measure(shortDate(date.toString()), labelStyle)
            val cx = left + (i + 0.5f) * slotWidth
            drawText(layout, topLeft = Offset((cx - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width), size.height - layout.size.height))
        }
    }

    byDate[selected]?.let { n ->
        val source = if (n.source == SleepSource.USER_REPORTED) Tone.Patterns.SOURCE_REPORTED else Tone.Patterns.SOURCE_ESTIMATED
        val onset = n.estimatedSleepOnset
        val wake = n.estimatedWakeTime
        Text(
            if (onset == null || wake == null) {
                Tone.Sleep.noSleepDetail(shortDate(n.dateOfNight), source)
            } else {
                Tone.Patterns.sleepDetail(shortDate(n.dateOfNight), clockText(onset, n.utcOffsetMinutes), clockText(wake, n.utcOffsetMinutes), source)
            },
        )
    }
    Text(Tone.Patterns.SLEEP_NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
    if (slept.any { it.noSleep }) Text(Tone.Sleep.CHART_NO_SLEEP, style = MaterialTheme.typography.bodySmall, color = muted)
}

private val NightLabelsKey = ExtraStore.Key<List<String>>()

private val NightLabelFormatter = CartesianValueFormatter { context, x, _ ->
    context.model.extraStore[NightLabelsKey].getOrElse(x.toInt()) { "" }
}

@Composable
private fun NightColumnChart(
    title: String,
    producer: CartesianChartModelProducer,
    nights: List<NightTotals>,
    value: (NightTotals) -> Number,
) {
    LaunchedEffect(nights) {
        producer.runTransaction {
            columnModel { series(nights.map(value)) }
            extras { it[NightLabelsKey] = nights.map { night -> shortDate(night.nightDate) } }
        }
    }
    val colors = MaterialTheme.colorScheme
    val label = rememberTextComponent(style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp))
    val axisLine = rememberLineComponent(Fill(colors.outlineVariant), 1.dp)

    Text(title, style = MaterialTheme.typography.titleMedium)
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                ColumnCartesianLayer.ColumnProvider.series(rememberLineComponent(Fill(colors.primary), 8.dp)),
            ),
            startAxis = VerticalAxis.rememberStart(line = axisLine, label = label, guideline = axisLine),
            bottomAxis = HorizontalAxis.rememberBottom(
                line = axisLine,
                label = label,
                valueFormatter = NightLabelFormatter,
                guideline = null,
            ),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
    )
}

private val shortFormat = DateTimeFormatter.ofPattern("d MMM")

private fun shortDate(isoDate: String): String = LocalDate.parse(isoDate).format(shortFormat)
