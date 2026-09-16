package io.github.anbu00001.nocturne.focus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.anbu00001.nocturne.core.focus.RunningTimer
import io.github.anbu00001.nocturne.core.focus.TimerKind
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.tone.Tone

/**
 * How the end of a timer is made known. At once: a vibration marked as an alarm, the only kind Android plays for an app
 * in the background since Android 13 (seen on the A18: usage ALARM, played in full), and the notification sound, which
 * follows silent mode. Once the block is recorded: a quiet notification with its unlocks, when notifications are allowed.
 */
internal class FocusSignals(private val context: Context) {

    /** Held so the sound is not collected while it plays. */
    private var ringtone: Ringtone? = null

    fun ended() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        }.getOrNull()
    }

    fun recorded(timer: RunningTimer, unlocks: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Tone.Focus.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = Tone.Focus.CHANNEL_DESCRIPTION
            },
        )
        val duration = Tone.duration(timer.plannedMinutes * LocalClock.MINUTE_MS)
        val text = if (timer.kind == TimerKind.FOCUS) Tone.Focus.focusEnded(duration, unlocks) else Tone.Focus.breakEnded(duration)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(Tone.Focus.CHANNEL_NAME)
                .setContentText(text)
                .setContentIntent(openNocturne(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 43
        const val CHANNEL_ID = "focus"
    }
}
