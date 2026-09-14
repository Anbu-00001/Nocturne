package io.github.anbu00001.nocturne

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.anbu00001.nocturne.tone.Tone
import io.github.anbu00001.nocturne.ui.LastNightScreen
import io.github.anbu00001.nocturne.ui.NocturneTheme
import io.github.anbu00001.nocturne.ui.OnboardingScreen
import io.github.anbu00001.nocturne.ui.PatternsScreen
import io.github.anbu00001.nocturne.ui.SettingsScreen
import io.github.anbu00001.nocturne.ui.SystemAccess

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

    // Usage access can only be granted in Settings, so re-check every time the user comes back.
    LifecycleResumeEffect(Unit) {
        usageAccess = SystemAccess.hasUsageAccess(context)
        batteryExempt = SystemAccess.isIgnoringBatteryOptimizations(context)
        if (usageAccess) app.harvestNow()
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
        MainTabs(app, usageAccess, batteryExempt)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(app: NocturneApp, usageAccess: Boolean, batteryExempt: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(Tone.Nav.LAST_NIGHT, Tone.Nav.PATTERNS, Tone.Nav.SETTINGS)
    Scaffold(
        topBar = {
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> LastNightScreen(app)
                1 -> PatternsScreen(app)
                else -> SettingsScreen(app, usageAccess, batteryExempt)
            }
        }
    }
}
