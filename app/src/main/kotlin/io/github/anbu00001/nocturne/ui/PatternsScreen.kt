package io.github.anbu00001.nocturne.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
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
import io.github.anbu00001.nocturne.data.NightTotals
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The payoff screen (spec §8): every harvested night, however long ago. */
class PatternsViewModel(app: NocturneApp) : ViewModel() {
    /** Null until the first read, so "no nights yet" is not flashed while loading. */
    val nights: StateFlow<List<NightTotals>?> = app.database.sessions().observeNightTotals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Vico: the producer a chart uses must not be replaced, so it lives as long as the screen's view model.
    val glancesProducer = CartesianChartModelProducer()
    val eveningProducer = CartesianChartModelProducer()
}

@Composable
fun PatternsScreen(app: NocturneApp) {
    val vm = viewModel { PatternsViewModel(app) }
    val loaded by vm.nights.collectAsStateWithLifecycle()
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
            NightColumnChart(Tone.Patterns.GLANCES_PER_NIGHT, vm.glancesProducer, nights) { it.glances }
            NightColumnChart(Tone.Patterns.EVENING_MINUTES, vm.eveningProducer, nights) { it.eveningScreenMs / 60_000 }
        }
    }
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
