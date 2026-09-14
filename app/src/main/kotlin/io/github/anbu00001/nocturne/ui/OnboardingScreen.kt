package io.github.anbu00001.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anbu00001.nocturne.tone.Tone

@Composable
fun OnboardingScreen(usageAccess: Boolean, batteryExempt: Boolean, onContinue: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(Tone.Onboarding.TITLE, style = MaterialTheme.typography.headlineSmall)

            Step(Tone.Onboarding.STEP_USAGE) {
                Text(Tone.Onboarding.USAGE_ACCESS_BODY)
                if (usageAccess) {
                    Text(Tone.Onboarding.USAGE_ACCESS_GRANTED, color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = { SystemAccess.openUsageAccessSettings(context) }) {
                        Text(Tone.Onboarding.USAGE_ACCESS_BUTTON)
                    }
                }
            }

            Step(Tone.Onboarding.STEP_BATTERY) {
                Text(Tone.Onboarding.BATTERY_BODY)
                if (batteryExempt) {
                    Text(Tone.Onboarding.BATTERY_GRANTED, color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = { SystemAccess.requestIgnoreBatteryOptimizations(context) }) {
                        Text(Tone.Onboarding.BATTERY_BUTTON)
                    }
                }
            }

            Step(Tone.Onboarding.VENDOR_TITLE) {
                Tone.Onboarding.VENDOR_STEPS.forEach { Text(it) }
                OutlinedButton(onClick = { SystemAccess.openAppInfo(context) }) { Text(Tone.Onboarding.OPEN_APP_INFO) }
                TextButton(onClick = { SystemAccess.openUrl(context, Tone.Onboarding.VENDOR_LINK) }) {
                    Text(Tone.Onboarding.VENDOR_LINK_LABEL)
                }
            }

            Button(onClick = onContinue, enabled = usageAccess, modifier = Modifier.align(Alignment.End)) {
                Text(Tone.Onboarding.CONTINUE)
            }
        }
    }
}

@Composable
private fun Step(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
