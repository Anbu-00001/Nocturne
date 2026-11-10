package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.data.Digests
import io.github.anbu00001.nocturne.data.MusicHistory
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The digest the widget shows, re-read whenever [key] changes; null until the first read. */
@Composable
fun rememberGlance(app: NocturneApp, key: Any?): Digests.Glance? {
    var glance by remember { mutableStateOf<Digests.Glance?>(null) }
    LaunchedEffect(key) {
        glance = withContext(Dispatchers.IO) { app.digests.glance(System.currentTimeMillis()) }
    }
    return glance
}

/**
 * The top of Patterns: the last seven nights beside the four weeks before, in sentences (Bentley et al., TOCHI 2013:
 * plain-language statements about a person's own streams were what people understood and kept reading). The charts
 * below hold every night; this says what moved.
 */
@Composable
fun WeekSection(app: NocturneApp, key: Any?) {
    val glance = rememberGlance(app, key) ?: return
    val digest = glance.week ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Tone.Week.SECTION, style = MaterialTheme.typography.titleMedium)
        val lines = GlanceText.weekLines(digest)
        if (lines.isEmpty()) {
            Text(Tone.Week.PENDING, color = muted)
        } else {
            lines.forEach { Text(it) }
        }
        val lateApps = GlanceText.lateApps(digest, app.labels::of)
        lateApps.forEach { Text(it) }
        if (lateApps.isNotEmpty()) Text(Tone.Week.LATE_APP_NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
        glance.laptopQuietSince?.let { LaptopQuiet(it) }
    }
}

/** Tonight's first lines when the laptop has sent nothing for days: the one failure that otherwise makes no sound. */
@Composable
fun LaptopQuietNote(app: NocturneApp, key: Any?) {
    rememberGlance(app, key)?.laptopQuietSince?.let { LaptopQuiet(it) }
}

@Composable
private fun LaptopQuiet(since: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(GlanceText.laptopQuiet(since), color = Attention)
        Text(Tone.Laptop.QUIET_DETAIL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Patterns: listening as part of the day first (how much, and how much away from a screen), then nights with music in the
 * evening beside nights without. The owner asked what music does for them; this shows what their own days hold and says
 * plainly what it cannot answer. How they felt is not in the data until they say so.
 */
@Composable
fun MusicSection(app: NocturneApp, key: Any?) {
    var report by remember { mutableStateOf<MusicHistory.Report?>(null) }
    LaunchedEffect(key) {
        report = withContext(Dispatchers.IO) { MusicHistory(app.database).report() }
    }
    val current = report ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Tone.Music.SECTION, style = MaterialTheme.typography.titleMedium)
        if (current.anyMusic) {
            GlanceText.musicDays(current).forEach { Text(it) }
            Text(Tone.Music.BEFORE_SLEEP, style = MaterialTheme.typography.titleSmall)
        }
        GlanceText.musicSplit(current).forEach { Text(it, color = if (current.split == null) muted else MaterialTheme.colorScheme.onSurface) }
        if (current.anyMusic) Text(Tone.Music.NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** Last night's music lines, under the sleep summary. */
@Composable
fun NightMusicLines(app: NocturneApp, night: NightEntity?) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(night) {
        lines = if (night == null) emptyList() else withContext(Dispatchers.IO) {
            GlanceText.music(MusicHistory(app.database).report(listOf(night)).byNight[night.dateOfNight])
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
