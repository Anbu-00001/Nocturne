package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.mind.AlarmGroup
import io.github.anbu00001.nocturne.core.mind.CheckIn
import io.github.anbu00001.nocturne.core.mind.MoodLinks
import io.github.anbu00001.nocturne.data.CheckIns
import io.github.anbu00001.nocturne.data.MindHistory
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The daily check-in on Tonight: one tap on a five by five field of squares picks pleasantness (across) and energy (up)
 * together, and an optional second tap gives tension. A layout of Nocturne's own; the two dimensions are the long-used
 * pleasure and arousal pair, which a single tap supports only broadly, so the app stores the numbers and names no emotion.
 * Never prompted: it is here when the app is opened, and behind one button on the widget.
 */
@Composable
fun CheckInCard(app: NocturneApp) {
    val scope = rememberCoroutineScope()
    var today by remember { mutableStateOf<CheckIns.Entry?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
    LaunchedEffect(loaded) {
        today = withContext(Dispatchers.IO) { CheckIns(app.database).today(System.currentTimeMillis(), currentOffsetMinutes()) }
    }
    fun save(checkIn: CheckIn) {
        scope.launch {
            app.saveCheckIn(checkIn).join()
            loaded++
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    val picked = today?.checkIn
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Tone.Mind.CHECK_IN_TITLE, style = MaterialTheme.typography.titleMedium)
        Text(picked?.let { Tone.Mind.today(it.pleasant, it.energy, it.tension) } ?: Tone.Mind.CHECK_IN_HINT, color = muted)
        Text(Tone.Mind.AXIS_ENERGY_HIGH, style = small, color = muted)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (energy in 5 downTo 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (pleasant in 1..5) {
                        val on = picked?.pleasant == pleasant && picked.energy == energy
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { save(CheckIn(pleasant, energy, picked?.tension)) },
                        )
                    }
                }
            }
        }
        Text(Tone.Mind.AXIS_ENERGY_LOW, style = small, color = muted)
        Row(Modifier.fillMaxWidth(0.7f), verticalAlignment = Alignment.CenterVertically) {
            Text(Tone.Mind.AXIS_PLEASANT_LOW, style = small, color = muted)
            Box(Modifier.weight(1f))
            Text(Tone.Mind.AXIS_PLEASANT_HIGH, style = small, color = muted)
        }
        if (picked != null) {
            Text(Tone.Mind.TENSION_TITLE, style = small, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tension in 1..5) {
                    FilterChip(
                        selected = picked.tension == tension,
                        onClick = { save(picked.copy(tension = if (picked.tension == tension) null else tension)) },
                        label = { Text(tension.toString()) },
                    )
                }
            }
        }
        Text(Tone.Mind.CHECK_IN_NOTE, style = small, color = muted)
    }
}

/** Patterns: the shape of nights and days in sentences, each research line beside the one figure it is about. */
@Composable
fun RhythmSection(app: NocturneApp, key: Any?) {
    var report by remember { mutableStateOf<MindHistory.Report?>(null) }
    LaunchedEffect(key) {
        report = withContext(Dispatchers.IO) { MindHistory(app.database).report(System.currentTimeMillis(), currentOffsetMinutes()) }
    }
    val current = report ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Tone.Mind.SECTION, style = MaterialTheme.typography.titleMedium)
        for (line in MindText.lines(current)) Text(line.text, style = if (line.note) small else MaterialTheme.typography.bodyMedium, color = if (line.note) muted else MaterialTheme.colorScheme.onSurface)
        Text(Tone.Mind.LINKS_TITLE, style = MaterialTheme.typography.titleSmall)
        for (line in MindText.links(current)) Text(line.text, style = if (line.note) small else MaterialTheme.typography.bodyMedium, color = if (line.note) muted else MaterialTheme.colorScheme.onSurface)
    }
}

/** The Rhythm section in sentences; the debug receiver prints the same lines, so they can be read without the screen. */
object MindText {
    /** [note] marks a research or limits line, drawn smaller and muted under the figure it belongs to. */
    data class Line(val text: String, val note: Boolean = false)

    /** Social jetlag from which the research line is shown: the comparison the cited study made. */
    private const val JETLAG_RESEARCH_FROM_MINUTES = 120.0

    fun lines(report: MindHistory.Report): List<Line> = buildList {
        report.jumps?.let { add(Line(Tone.Mind.jumps(it.pairs, span(it.medianJumpMinutes), it.overTwoHours))) }
        report.socialJetlagMinutes?.let { jetlag ->
            add(Line(Tone.Mind.jetlag(span(jetlag))))
            if (jetlag >= JETLAG_RESEARCH_FROM_MINUTES) add(Line(Tone.Mind.JETLAG_RESEARCH, note = true))
        }
        report.alarmsEarlyWeek?.let { add(Line(Tone.Mind.alarmsEarly(it.mornings, it.nights, count(it.medianAlarms), span(it.medianSpanMinutes)))) }
        val other: AlarmGroup? = report.alarmsOther
        if (other != null) add(Line(Tone.Mind.alarmsOther(other.mornings, other.nights, count(other.medianAlarms), span(other.medianSpanMinutes))))
        if (report.musicMorning + report.musicAfternoon + report.musicEvening > 0) {
            add(Line(Tone.Mind.musicWhen(span(report.musicMorning.toDouble()), span(report.musicAfternoon.toDouble()), span(report.musicEvening.toDouble()))))
            add(Line(Tone.Mind.MUSIC_RESEARCH, note = true))
        }
        val week = report.pickupsWeek
        val before = report.pickupsBefore
        if (week != null && before != null) {
            add(Line(Tone.Mind.pickups(count(week), count(before))))
            add(Line(Tone.Mind.PICKUPS_NOTE, note = true))
        }
    }

    fun links(report: MindHistory.Report): List<Line> = buildList {
        val entries = report.checkIns
        if (entries.isNotEmpty()) {
            add(Line(Tone.Mind.checkIns(entries.size, count(median(entries.map { it.checkIn.pleasant.toDouble() })), count(median(entries.map { it.checkIn.energy.toDouble() })))))
        }
        val links = report.links
        if (links.withheld) {
            add(Line(Tone.Mind.linksWithheld(links.days, MoodLinks.MIN_DAYS), note = true))
            return@buildList
        }
        links.sleepEnergy?.let { add(Line(Tone.Mind.linkSleepEnergy(signed(it.slope), signed(it.low), signed(it.high), it.pairs))) }
        links.sleepPleasant?.let { add(Line(Tone.Mind.linkSleepPleasant(signed(it.slope), signed(it.low), signed(it.high), it.pairs))) }
        links.musicPleasant?.let { add(Line(Tone.Mind.linkMusicPleasant(signed(it.slope), signed(it.low), signed(it.high), it.pairs))) }
        add(Line(Tone.Mind.LINKS_NOTE, note = true))
    }

    private fun span(minutes: Double): String = Tone.duration((minutes * 60_000).toLong())

    /** Whole numbers stay whole; a median between two counts keeps its half. */
    private fun count(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.1f".format(value)

    private fun signed(value: Double): String = "%+.1f".format(value)

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
