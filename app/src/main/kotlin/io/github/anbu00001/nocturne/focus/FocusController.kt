package io.github.anbu00001.nocturne.focus

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.anbu00001.nocturne.MainActivity
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.focus.FocusTimer
import io.github.anbu00001.nocturne.core.focus.RunningTimer
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.FocusBlocks
import io.github.anbu00001.nocturne.tone.Tone
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The focus timer (spec §7). The timer lives in preferences, not in a service: its end is an alarm, which fires in Doze
 * and survives the process being killed. A reboot clears alarms, so the app finishes or re-arms the timer when it next
 * starts. Ending a focus block harvests first, so the unlocks recorded with it reach its last minute.
 *
 * The end is signalled by vibration marked as an alarm (the only kind Android lets a background app play since Android
 * 13) and the notification sound, which follows silent mode. A notification with the block's unlocks follows when
 * notifications are allowed; nothing depends on it.
 */
class FocusController(private val app: NocturneApp) {

    private val prefs = app.getSharedPreferences(NocturneApp.PREFS, Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val state = MutableStateFlow(load())
    private var ringtone: Ringtone? = null

    val running: StateFlow<RunningTimer?> = state.asStateFlow()

    var focusMinutes: Int
        get() = prefs.getInt(KEY_FOCUS_LENGTH, FocusTimer.DEFAULT_FOCUS_MINUTES)
        set(value) = prefs.edit { putInt(KEY_FOCUS_LENGTH, value) }

    var breakMinutes: Int
        get() = prefs.getInt(KEY_BREAK_LENGTH, FocusTimer.DEFAULT_BREAK_MINUTES)
        set(value) = prefs.edit { putInt(KEY_BREAK_LENGTH, value) }

    /** USE_EXACT_ALARM grants exact alarms from Android 13; before that SCHEDULE_EXACT_ALARM is granted at install. */
    val exactAlarms: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    @Synchronized
    fun start(kind: TimerKind) {
        if (state.value != null) return
        val minutes = if (kind == TimerKind.FOCUS) focusMinutes else breakMinutes
        val timer = RunningTimer(kind, System.currentTimeMillis(), minutes)
        prefs.edit(commit = true) {
            putString(KEY_KIND, kind.name)
            putLong(KEY_START, timer.startTs)
            putInt(KEY_MINUTES, minutes)
        }
        state.value = timer
        schedule(timer)
    }

    /** Stopped before its end: a focus block is recorded up to now, a break is not recorded. */
    fun stop() {
        val timer = take(null) ?: return
        alarms.cancel(alarmIntent(timer.startTs))
        val at = System.currentTimeMillis()
        if (timer.kind == TimerKind.FOCUS) app.appScope.launch { conclude(timer, minOf(at, timer.endTs), completed = at >= timer.endTs, notify = false) }
    }

    /**
     * The alarm for the timer started at [startTs] fired, or the app started after the timer's end. Returns the
     * recording still under way, or null when there was nothing to finish (a stale alarm, or already finished).
     * A timer that ended while the phone was off is recorded without a signal.
     */
    fun finish(startTs: Long?): Job? {
        val timer = take(startTs) ?: return null
        val onTime = System.currentTimeMillis() - timer.endTs <= SIGNAL_GRACE_MS
        if (onTime) signal()
        return app.appScope.launch { conclude(timer, timer.endTs, completed = true, notify = onTime) }
    }

    /** At process start: finish a timer whose end has passed, or put its alarm back (a reboot clears alarms). */
    fun resume() {
        val timer = state.value ?: return
        if (timer.finished(System.currentTimeMillis())) finish(timer.startTs) else schedule(timer)
    }

    private suspend fun conclude(timer: RunningTimer, endTs: Long, completed: Boolean, notify: Boolean) {
        val unlocks = if (timer.kind == TimerKind.FOCUS) {
            app.harvester.harvest()
            FocusBlocks(app.database).record(timer, endTs, completed).interruptionCount
        } else {
            0
        }
        if (notify) notifyEnded(timer, unlocks)
    }

    @Synchronized
    private fun take(startTs: Long?): RunningTimer? {
        val timer = state.value ?: return null
        if (startTs != null && timer.startTs != startTs) return null
        prefs.edit(commit = true) {
            remove(KEY_KIND)
            remove(KEY_START)
            remove(KEY_MINUTES)
        }
        state.value = null
        return timer
    }

    private fun load(): RunningTimer? {
        val start = prefs.getLong(KEY_START, -1)
        val kind = prefs.getString(KEY_KIND, null)?.let { name -> TimerKind.entries.firstOrNull { it.name == name } }
        return if (start < 0 || kind == null) null else RunningTimer(kind, start, prefs.getInt(KEY_MINUTES, FocusTimer.DEFAULT_FOCUS_MINUTES))
    }

    private fun schedule(timer: RunningTimer) {
        // Elapsed time, so a clock change during the block does not move its end.
        val at = SystemClock.elapsedRealtime() + timer.remainingMs(System.currentTimeMillis())
        val intent = alarmIntent(timer.startTs)
        try {
            if (exactAlarms) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
        }
    }

    /** One alarm at a time: the request code and target are fixed, so a new timer replaces the old alarm. */
    private fun alarmIntent(startTs: Long): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_ALARM,
        Intent(app, FocusAlarmReceiver::class.java).putExtra(EXTRA_START_TS, startTs),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun signal() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        ringtone = runCatching {
            RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        }.getOrNull()
    }

    private fun notifyEnded(timer: RunningTimer, unlocks: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Tone.Focus.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = Tone.Focus.CHANNEL_DESCRIPTION
            },
        )
        val duration = Tone.duration(timer.plannedMinutes * LocalClock.MINUTE_MS)
        val text = if (timer.kind == TimerKind.FOCUS) Tone.Focus.focusEnded(duration, unlocks) else Tone.Focus.breakEnded(duration)
        val open = PendingIntent.getActivity(
            app,
            REQUEST_OPEN,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(app.applicationInfo.icon)
                .setContentTitle(Tone.Focus.CHANNEL_NAME)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val EXTRA_START_TS = "startTs"
        private const val KEY_KIND = "focusKind"
        private const val KEY_START = "focusStart"
        private const val KEY_MINUTES = "focusMinutes"
        private const val KEY_FOCUS_LENGTH = "focusLength"
        private const val KEY_BREAK_LENGTH = "breakLength"
        private const val REQUEST_ALARM = 41
        private const val REQUEST_OPEN = 42
        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "focus"

        /** An alarm this late (the phone was off at the end, say) is recorded without buzzing. */
        private const val SIGNAL_GRACE_MS = 2 * LocalClock.MINUTE_MS
    }
}

/** The end of a focus block or break. Waits a few seconds for the block to be recorded, then lets the process go. */
class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NocturneApp
        val startTs = intent.getLongExtra(FocusController.EXTRA_START_TS, -1).takeIf { it >= 0 }
        val work = app.focus.finish(startTs) ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(RECORD_BUDGET_MS) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A broadcast has about 10 s; the recording carries on past this if the process lives. */
        const val RECORD_BUDGET_MS = 8_000L
    }
}
