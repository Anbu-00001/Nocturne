package io.github.anbu00001.nocturne.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.collector.AndroidDisplayState
import io.github.anbu00001.nocturne.collector.HarvestScheduler
import io.github.anbu00001.nocturne.collector.LightSamplerService
import io.github.anbu00001.nocturne.collector.LightService
import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.HarvestOutcome
import io.github.anbu00001.nocturne.data.HarvestRunEntity
import io.github.anbu00001.nocturne.data.LightSampleEntity
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.data.PackageCount
import io.github.anbu00001.nocturne.data.writeCsv
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Health(
    val latestRun: HarvestRunEntity? = null,
    val lastSuccessAt: Long? = null,
    val rawEvents: Long = 0,
    val sessions: Long = 0,
    val work: WorkInfo? = null,
)

data class Diagnostics(
    val keyguardEventsSeen: Boolean? = null,
    val lockedWakePackages: List<PackageCount> = emptyList(),
)

data class SleepSettings(
    /** The most recent night with a personalised window, else the most recent night. */
    val latest: NightEntity? = null,
    val reports: Int = 0,
)

data class LightSettings(
    val latest: LightSampleEntity? = null,
    val lastDay: Int = 0,
)

class SettingsViewModel(private val app: NocturneApp) : ViewModel() {

    val health: StateFlow<Health> = combine(
        app.database.harvest().observeLatestRun(),
        app.database.harvest().observeLastSuccess(),
        app.database.rawEvents().observeCount(),
        app.database.sessions().observeCount(),
        HarvestScheduler.observePeriodic(app),
    ) { run, success, raw, sessions, work ->
        Health(run, success, raw, sessions, work.firstOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Health())

    val sleep: StateFlow<SleepSettings> = combine(
        app.database.sleep().observeNights(),
        app.database.sleep().observeReportCount(),
    ) { nights, reports ->
        SleepSettings(nights.lastOrNull { it.windowPersonalised } ?: nights.lastOrNull(), reports)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepSettings())

    val light: StateFlow<LightSettings> = combine(
        app.database.light().observeLatest(),
        app.database.light().observeCountSince(System.currentTimeMillis() - LocalClock.DAY_MS),
    ) { latest, lastDay ->
        LightSettings(latest, lastDay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LightSettings())

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun refreshDiagnostics() {
        app.appScope.launch {
            val now = System.currentTimeMillis()
            _diagnostics.value = Diagnostics(
                keyguardEventsSeen = app.database.rawEvents().anySince(EventType.KEYGUARD_HIDDEN, now - 14 * LocalClock.DAY_MS),
                lockedWakePackages = app.database.sessions().lockedWakePackages(now - 7 * LocalClock.DAY_MS),
            )
        }
    }

    fun harvestNow() {
        app.appScope.launch {
            app.harvester.harvest()
            refreshDiagnostics()
        }
    }

    fun recomputeAll() {
        app.appScope.launch { _message.value = Tone.Settings.recomputed(app.harvester.recomputeAll()) }
    }

    fun export(uri: Uri) {
        app.appScope.launch {
            val rows = app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { app.database.rawEvents().writeCsv(it) }
            _message.value = Tone.Settings.exported(rows ?: 0)
        }
    }

    fun wipe() {
        app.appScope.launch {
            app.database.clearAllTables()
            _message.value = null
            refreshDiagnostics()
        }
    }
}

private const val HARVEST_WARN_DAYS = 5 // spec §4.1: warn loudly past 5 days

@Composable
fun SettingsScreen(app: NocturneApp, usageAccess: Boolean, batteryExempt: Boolean) {
    val context = LocalContext.current
    val vm = viewModel { SettingsViewModel(app) }
    val health by vm.health.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val light by vm.light.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    val screenTimeoutMs = remember { app.deviceProfile.sleepConfig().screenOffTimeoutMs }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.export(uri)
    }
    LaunchedEffect(Unit) { vm.refreshDiagnostics() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Service state lives outside the database; re-read it whenever the screen resumes or the switch changes.
    var refreshes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshes++
        onPauseOrDispose { }
    }
    val lightEnabled = remember(refreshes) { LightService.isEnabled(context) }
    val samplerRunning = remember(refreshes) { LightSamplerService.running }
    val refusal = remember(refreshes) { LightService.lastRefusal(context) }
    val warmFilter = remember(refreshes) { AndroidDisplayState(context).snapshot().warmFilter }
    val notificationsGranted = remember(refreshes) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshes++ }
    val lightSensor = remember { app.deviceProfile.lightSensor() }
    val displayProfile = remember { app.deviceProfile.displayProfile() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(Tone.Settings.PERMISSIONS)
        Text(Tone.Settings.status(Tone.Settings.USAGE_ACCESS, grantedText(usageAccess)))
        Text(Tone.Settings.status(Tone.Settings.BATTERY_EXEMPT, grantedText(batteryExempt)))
        if (!batteryExempt) {
            OutlinedButton(onClick = { SystemAccess.requestIgnoreBatteryOptimizations(context) }) {
                Text(Tone.Onboarding.BATTERY_BUTTON)
            }
        }
        OutlinedButton(onClick = { SystemAccess.openAppInfo(context) }) { Text(Tone.Onboarding.OPEN_APP_INFO) }

        HorizontalDivider()
        SectionTitle(Tone.Settings.HARVESTER)
        val run = health.latestRun
        Text(if (run == null) Tone.Settings.NEVER_RUN else Tone.Settings.lastRun(dateTime(run.finishedAt), outcomeLabel(run.outcome)))
        health.lastSuccessAt?.let { success ->
            val days = (System.currentTimeMillis() - success) / LocalClock.DAY_MS
            Text(Tone.Settings.daysSinceSuccess(days))
            if (days > HARVEST_WARN_DAYS) Text(Tone.Settings.gapWarning(days), color = MaterialTheme.colorScheme.error)
        }
        health.work?.let { work ->
            Text(Tone.Settings.workState(work.state.name.lowercase()), color = muted)
            val next = work.nextScheduleTimeMillis
            if (work.state == WorkInfo.State.ENQUEUED && next != Long.MAX_VALUE) {
                Text(Tone.Settings.nextRun(maxOf((next - System.currentTimeMillis()) / 60_000, 0)), color = muted)
            }
        }
        Text(Tone.Settings.counts(health.rawEvents, health.sessions), color = muted)
        Button(onClick = vm::harvestNow) { Text(Tone.Settings.HARVEST_NOW) }

        HorizontalDivider()
        SectionTitle(Tone.Light.SECTION)
        Text(if (lightEnabled) Tone.Light.ENABLED else Tone.Light.DISABLED)
        if (lightEnabled) Text(Tone.Light.sampler(samplerRunning), color = muted)
        if (lightEnabled) {
            OutlinedButton(onClick = {
                LightService.setEnabled(context, false)
                refreshes++
            }) { Text(Tone.Light.STOP) }
        } else {
            Button(onClick = {
                LightService.setEnabled(context, true)
                refreshes++
            }) { Text(Tone.Light.START) }
        }
        val latestSample = light.latest
        Text(
            if (latestSample == null) {
                Tone.Light.NO_SAMPLES
            } else {
                Tone.Light.lastSample(dateTime(latestSample.timestamp + latestSample.durationMs), light.lastDay)
            },
            color = muted,
        )
        refusal?.let { Text(Tone.Light.refused(dateTime(it.at), it.reason), color = MaterialTheme.colorScheme.error) }
        Text(
            lightSensor?.let { Tone.Light.sensor(it.name, plainNumber(it.resolutionLux.toDouble()), plainNumber(it.maximumLux.toDouble())) }
                ?: Tone.Light.NO_SENSOR,
            color = muted,
        )
        Text(
            Tone.Light.display(
                if (app.deviceProfile.isOppoA18) Tone.Light.PROFILE_A18 else Tone.Light.PROFILE_GENERIC,
                plainNumber(displayProfile.minNits),
                plainNumber(displayProfile.peakNits),
            ),
            color = muted,
        )
        Text(
            Tone.Light.warmFilter(
                when (warmFilter) {
                    true -> Tone.Light.STATE_ON
                    false -> Tone.Light.STATE_OFF
                    null -> Tone.Light.STATE_UNREADABLE
                },
            ),
            color = muted,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(Tone.Settings.status(Tone.Light.NOTIFICATION_PERMISSION, grantedText(notificationsGranted)), color = muted)
            if (!notificationsGranted) {
                OutlinedButton(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(Tone.Light.ALLOW_NOTIFICATION)
                }
                Text(Tone.Light.NOTIFICATION_NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }

        HorizontalDivider()
        SectionTitle(Tone.Settings.SLEEP)
        val latest = sleep.latest
        if (latest != null && latest.windowPersonalised) {
            val onsetMinute = Math.floorMod(latest.eveningWindowStartMinute + EveningWindow.LEAD_MINUTES, LocalClock.MINUTES_PER_DAY)
            Text(Tone.Settings.habitual(minuteText(onsetMinute), minuteText(latest.eveningWindowEndMinute), latest.windowNights))
            Text(
                Tone.Settings.eveningWindow(windowText(EveningWindow(latest.eveningWindowStartMinute, latest.eveningWindowEndMinute))),
                color = muted,
            )
        } else {
            Text(Tone.Settings.HABITUAL_PENDING, color = muted)
        }
        Text(Tone.Settings.screenTimeout(Tone.duration(screenTimeoutMs)), color = muted)
        Text(Tone.Settings.reports(sleep.reports), color = muted)

        HorizontalDivider()
        SectionTitle(Tone.Settings.CLASSIFIER)
        diagnostics.keyguardEventsSeen?.let { Text(Tone.Settings.unlockEvidence(it)) }
        val defaults = ClassifierConfig()
        Text(
            Tone.Settings.thresholds(defaults.glanceNoUnlockMaxMs / 1000, defaults.glanceUnlockedMaxMs / 1000, defaults.shortMaxMs / 60_000),
            color = muted,
        )
        Text(Tone.Settings.LOCKED_WAKE_PACKAGES, style = MaterialTheme.typography.titleSmall)
        if (diagnostics.lockedWakePackages.isEmpty()) Text(Tone.Settings.NONE_YET, color = muted)
        diagnostics.lockedWakePackages.forEach {
            Text(Tone.Settings.packageCount(it.packageName, it.n), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        OutlinedButton(onClick = vm::recomputeAll) { Text(Tone.Settings.RECOMPUTE_ALL) }

        HorizontalDivider()
        SectionTitle(Tone.Settings.DATA)
        OutlinedButton(onClick = { exportLauncher.launch("nocturne-raw-events-${LocalDate.now()}.csv") }) {
            Text(Tone.Settings.EXPORT_CSV)
        }
        OutlinedButton(
            onClick = { confirmWipe = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(Tone.Settings.WIPE) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(Tone.Settings.WIPE_TITLE) },
            text = { Text(Tone.Settings.WIPE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    vm.wipe()
                }) { Text(Tone.Settings.DELETE) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(Tone.Settings.CANCEL) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun grantedText(granted: Boolean) = if (granted) Tone.Settings.GRANTED else Tone.Settings.MISSING

private val runFormat = DateTimeFormatter.ofPattern("d MMM HH:mm")

private fun dateTime(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(runFormat)

/** 490, 2.5, 0.01: no trailing zeros. */
private fun plainNumber(value: Double): String =
    "%.3f".format(value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

private fun outcomeLabel(outcome: HarvestOutcome): String = when (outcome) {
    HarvestOutcome.OK -> Tone.HarvestOutcome.OK
    HarvestOutcome.USER_LOCKED -> Tone.HarvestOutcome.USER_LOCKED
    HarvestOutcome.NO_ACCESS -> Tone.HarvestOutcome.NO_ACCESS
    HarvestOutcome.FAILED -> Tone.HarvestOutcome.FAILED
}
