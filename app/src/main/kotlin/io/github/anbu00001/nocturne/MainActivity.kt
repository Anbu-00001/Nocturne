package io.github.anbu00001.nocturne

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.anbu00001.nocturne.collector.LightService
import io.github.anbu00001.nocturne.tone.Tone
import io.github.anbu00001.nocturne.ui.FocusScreen
import io.github.anbu00001.nocturne.ui.GapCardHost
import io.github.anbu00001.nocturne.ui.LastNightScreen
import io.github.anbu00001.nocturne.ui.NocturneTheme
import io.github.anbu00001.nocturne.ui.OnboardingScreen
import io.github.anbu00001.nocturne.ui.PatternsScreen
import io.github.anbu00001.nocturne.ui.SettingsScreen
import io.github.anbu00001.nocturne.ui.SystemAccess
import io.github.anbu00001.nocturne.ui.TonightScreen
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NocturneApp
        setContent {
            NocturneTheme {
                NocturneRoot(app)
            }
        }
    }
}

private const val KEY_ONBOARDED = "onboarded"

@Composable
private fun NocturneRoot(app: NocturneApp) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(NocturneApp.PREFS, Context.MODE_PRIVATE) }
    var usageAccess by remember { mutableStateOf(SystemAccess.hasUsageAccess(context)) }
    var batteryExempt by remember { mutableStateOf(SystemAccess.isIgnoringBatteryOptimizations(context)) }
    var onboarded by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false)) }
    var harvest by remember { mutableStateOf<Job?>(null) }

    // Usage access can only be granted in Settings, so re-check every time the user comes back.
    LifecycleResumeEffect(Unit) {
        usageAccess = SystemAccess.hasUsageAccess(context)
        batteryExempt = SystemAccess.isIgnoringBatteryOptimizations(context)
        if (usageAccess) harvest = app.harvestNow()
        // An app in the foreground may always start a foreground service: the light sampler's surest restart.
        LightService.ensureRunning(context)
        onPauseOrDispose { }
    }

    if (!usageAccess || !onboarded) {
        OnboardingScreen(
            usageAccess = usageAccess,
            batteryExempt = batteryExempt,
            onContinue = {
                prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
                onboarded = true
            },
        )
    } else {
        MainTabs(app, usageAccess, batteryExempt, harvest)
    }
}

/**
 * The tabs do not fit a 6.5-inch phone at full label width ("Last night" wrapped on the A18), so the row scrolls. The
 * gap card (spec §7), when there is one, sits above whichever tab is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(app: NocturneApp, usageAccess: Boolean, batteryExempt: Boolean, harvest: Job?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(Tone.Nav.TONIGHT, Tone.Nav.LAST_NIGHT, Tone.Nav.PATTERNS, Tone.Nav.FOCUS, Tone.Nav.SETTINGS)
    Scaffold(
        topBar = {
            PrimaryScrollableTabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding(), edgePadding = 0.dp) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            GapCardHost(app, harvest)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TonightScreen(app)
                    1 -> LastNightScreen(app)
                    2 -> PatternsScreen(app)
                    3 -> FocusScreen(app)
                    else -> SettingsScreen(app, usageAccess, batteryExempt)
                }
            }
        }
    }
}
