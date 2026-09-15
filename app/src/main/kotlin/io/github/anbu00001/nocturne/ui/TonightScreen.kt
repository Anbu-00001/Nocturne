package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.collector.AndroidDisplayState
import io.github.anbu00001.nocturne.collector.LightService
import io.github.anbu00001.nocturne.core.light.Band
import io.github.anbu00001.nocturne.core.light.EveningLight
import io.github.anbu00001.nocturne.core.light.MelanopicTargets
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.LightSampleEntity
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.math.log10

data class TonightState(
    val now: Long = System.currentTimeMillis(),
    val window: EveningWindow = EveningWindow.PROVISIONAL,
    /** Tonight's row, which carries the suppression so far once a harvest has run with light samples. */
    val night: NightEntity? = null,
    val glances: Int? = null,
    val latestSample: LightSampleEntity? = null,
    val lightEnabled: Boolean = false,
)

class TonightViewModel(private val app: NocturneApp) : ViewModel() {
    private val lightEnabled = MutableStateFlow(LightService.isEnabled(app))

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<TonightState> = combine(
        clock,
        app.database.sleep().observeNights(),
        app.database.sessions().observeNightTotals(),
        app.database.light().observeLatest(),
        lightEnabled,
    ) { now, nights, totals, sample, enabled ->
        val nightDate = LocalClock.nightOf(now, currentOffsetMinutes()).toString()
        val night = nights.lastOrNull { it.dateOfNight == nightDate }
        // Before tonight's first session there is no row yet; the latest night's window is this week's or last week's.
        val windowSource = night ?: nights.lastOrNull()
        TonightState(
            now = now,
            window = windowSource?.let { EveningWindow(it.eveningWindowStartMinute, it.eveningWindowEndMinute) } ?: EveningWindow.PROVISIONAL,
            night = night,
            glances = totals.lastOrNull { it.nightDate == nightDate }?.glances,
            latestSample = sample,
            lightEnabled = enabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightState())

    fun refresh() {
        lightEnabled.value = LightService.isEnabled(app)
    }

    fun startLight() {
        LightService.setEnabled(app, true)
        refresh()
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}

private const val FRESH_SAMPLE_MS = 90_000L

@Composable
fun TonightScreen(app: NocturneApp) {
    val vm = viewModel { TonightViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val profile = remember { app.deviceProfile.displayProfile() }
    val displayState = remember { AndroidDisplayState(app) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall

    // The screen is on while this is read, so its own state is current; room light needs a fresh sample.
    val display = remember(state.now) { displayState.snapshot() }
    val lux = state.latestSample
        ?.takeIf { state.now - (it.timestamp + it.durationMs) <= FRESH_SAMPLE_MS }
        ?.ambientLux?.toDouble()
    val evening = state.window.contains(LocalClock.minuteOfDay(state.now, currentOffsetMinutes()))
    // Without a room reading the headline is the screen alone: a room prior would only restate an assumption as a number.
    val band = if (lux != null) {
        EveningLight.atEyes(lux, display.brightnessSetting, display.darkUi, display.warmFilter, profile, evening = evening)
    } else {
        EveningLight.screenAtEyes(display.brightnessSetting, display.darkUi, display.warmFilter, profile)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(windowLine(state), style = MaterialTheme.typography.titleMedium)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Tone.Tonight.atEyes(luxText(band.mid)), style = MaterialTheme.typography.headlineSmall)
            Text(Tone.Tonight.range(luxText(band.low), luxText(band.high)))
            Text(targetLine(band), color = muted)
            LightRangeBar(band, Modifier.fillMaxWidth().height(44.dp))
            Text(Tone.Tonight.CHART_NOTE, style = small, color = muted)
            Text(if (lux != null) Tone.Tonight.MEASURED else Tone.Tonight.NOT_MEASURED, style = small, color = muted)
        }

        Text(state.glances?.let(Tone.Tonight::glances) ?: Tone.Tonight.GLANCES_PENDING)

        val night = state.night
        val suppression = night?.modelledSuppressionPct
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                night != null && suppression != null -> {
                    Text(
                        Tone.Tonight.suppression(
                            percentText(suppression),
                            percentText(night.suppressionLowPct ?: suppression),
                            percentText(night.suppressionHighPct ?: suppression),
                        ),
                    )
                    Text(Tone.Light.coverage(night.lightMeasuredMinutes, night.lightScreenMinutes), color = muted)
                    if (night.suppressionDurationClamped) Text(Tone.Light.DURATION_CLAMPED, style = small, color = muted)
                }
                state.lightEnabled -> Text(Tone.Tonight.SUPPRESSION_PENDING, color = muted)
                else -> {
                    Text(Tone.Tonight.LIGHT_OFF, color = muted)
                    OutlinedButton(onClick = vm::startLight) { Text(Tone.Light.START) }
                }
            }
        }

        Text(Tone.Tonight.MODELLED_NOTE, style = small, color = muted)
    }
}

/** The modelled range on a log scale from 0.1 to 1000 lux, the middle estimate as a tick and 10 lux as a line. */
@Composable
private fun LightRangeBar(band: Band, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    Canvas(modifier) {
        fun x(lux: Double): Float {
            val t = (log10(lux.coerceIn(SCALE_MIN, SCALE_MAX)) - log10(SCALE_MIN)) / (log10(SCALE_MAX) - log10(SCALE_MIN))
            return t.toFloat() * size.width
        }
        val top = 4.dp.toPx()
        val barHeight = 14.dp.toPx()
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(colors.surfaceVariant, Offset(0f, top), Size(size.width, barHeight), radius)
        val low = x(band.low)
        val high = x(band.high)
        drawRoundRect(colors.primary.copy(alpha = 0.45f), Offset(low, top), Size(maxOf(high - low, 3.dp.toPx()), barHeight), radius)
        val mid = x(band.mid)
        drawLine(colors.primary, Offset(mid, top - 2.dp.toPx()), Offset(mid, top + barHeight + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        val target = x(MelanopicTargets.EVENING_MAX_LUX)
        drawLine(colors.tertiary, Offset(target, 0f), Offset(target, top + barHeight + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
        for (tick in listOf(0.1, 1.0, 10.0, 100.0, 1000.0)) {
            val layout = measurer.measure(if (tick < 1) "0.1" else tick.toInt().toString(), labelStyle)
            val left = (x(tick) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(left, size.height - layout.size.height))
        }
    }
}

private const val SCALE_MIN = 0.1
private const val SCALE_MAX = 1000.0

private fun windowLine(state: TonightState): String {
    val minute = LocalClock.minuteOfDay(state.now, currentOffsetMinutes())
    val window = state.window
    return if (window.contains(minute)) {
        Tone.Tonight.windowOpen(minuteText(window.startMinute), minuteText(window.endMinute))
    } else {
        val wait = Math.floorMod(window.startMinute - minute, LocalClock.MINUTES_PER_DAY) * LocalClock.MINUTE_MS
        Tone.Tonight.windowStartsIn(minuteText(window.startMinute), Tone.duration(wait))
    }
}

private fun targetLine(band: Band): String = when {
    band.high < MelanopicTargets.EVENING_MAX_LUX -> Tone.Tonight.UNDER_TARGET
    band.low > MelanopicTargets.EVENING_MAX_LUX -> Tone.Tonight.OVER_TARGET
    else -> Tone.Tonight.SPANS_TARGET
}

internal fun luxText(lux: Double): String = when {
    lux < 1 -> "%.2f".format(lux)
    lux < 10 -> "%.1f".format(lux)
    else -> "%.0f".format(lux)
}

internal fun percentText(percent: Float): String = "%.0f".format(percent)
