package io.github.anbu00001.nocturne.checkin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.anbu00001.nocturne.MainActivity
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.R
import io.github.anbu00001.nocturne.core.mind.CheckIn
import io.github.anbu00001.nocturne.core.mind.CheckInReminder
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.CheckIns
import io.github.anbu00001.nocturne.tone.Tone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The evening check-in notification ([CheckInReminder] says when). Silent: a low-importance channel and setSilent, so
 * no sound, no vibration and no heads-up; it sits in the shade until answered, dismissed or 04:00. Expanded, it holds
 * the same five by five field as Tonight, and a tap there is saved through [NocturneApp.saveCheckIn], the path Tonight
 * uses; the notification then offers the optional tension row and goes. Collapsed, a tap opens Tonight.
 */
object CheckInNotification {
    private const val CHANNEL_ID = "checkin"
    private const val NOTIFICATION_ID = 44
    private const val KEY_ENABLED = "checkInReminder"
    private const val KEY_POSTED_DAY = "checkInPostedDay"
    private const val REQUEST_OPEN = 60
    private const val REQUEST_SKIP = 70

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) cancel(context)
    }

    /** After each background harvest. Posts at most once a check-in day, and only when [CheckInReminder] allows it. */
    suspend fun maybePost(app: NocturneApp, now: Long = System.currentTimeMillis()) {
        val offset = offsetMinutes(now)
        val day = CheckIn.dayOf(now, offset)
        val localMinute = (Math.floorMod(LocalClock.localMillis(now, offset), LocalClock.DAY_MS) / LocalClock.MINUTE_MS).toInt()
        val posted = prefs(app).getString(KEY_POSTED_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val answered = CheckIns(app.database).today(now, offset) != null
        if (!CheckInReminder.shouldPost(localMinute, day, posted, answered, enabled(app))) return
        if (post(app, now)) prefs(app).edit().putString(KEY_POSTED_DAY, day.toString()).apply()
    }

    /** The five by five field. False when notifications are not allowed, so the day is not marked as asked. */
    fun post(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!allowed(context)) return false
        val big = frame(context, Tone.Mind.CHECK_IN_HINT)
        big.setTextViewText(R.id.checkin_top, Tone.Mind.AXIS_ENERGY_HIGH)
        big.setTextViewText(R.id.checkin_bottom, Tone.Mind.NOTIFY_AXES)
        for (energy in 5 downTo 1) {
            val row = RemoteViews(context.packageName, R.layout.notification_checkin_row)
            for (pleasant in 1..5) row.addView(R.id.checkin_row, tile(context, "", tap(context, pleasant * 10 + energy, pleasant, energy, null)))
            big.addView(R.id.checkin_rows, row)
        }
        notify(context, now, Tone.Mind.NOTIFY_TEXT, big)
        return true
    }

    /** After a square: the optional tension row, 1 to 5, and Skip. */
    fun postTension(context: Context, picked: CheckIn) {
        if (!allowed(context)) return
        val big = frame(context, Tone.Mind.NOTIFY_TENSION)
        big.setViewVisibility(R.id.checkin_top, View.GONE)
        big.setViewVisibility(R.id.checkin_bottom, View.GONE)
        val row = RemoteViews(context.packageName, R.layout.notification_checkin_row)
        for (tension in 1..5) {
            row.addView(R.id.checkin_row, tile(context, tension.toString(), tap(context, 100 + tension, picked.pleasant, picked.energy, tension)))
        }
        row.addView(R.id.checkin_row, tile(context, Tone.Mind.NOTIFY_SKIP, skip(context)))
        big.addView(R.id.checkin_rows, row)
        notify(context, System.currentTimeMillis(), Tone.Mind.NOTIFY_TENSION, big)
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    private fun notify(context: Context, now: Long, text: String, big: RemoteViews) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Tone.Mind.NOTIFY_CHANNEL, NotificationManager.IMPORTANCE_LOW).apply {
                description = Tone.Mind.NOTIFY_CHANNEL_DESCRIPTION
                setSound(null, null)
                enableVibration(false)
            },
        )
        // Gone at 04:00, when the check-in day it asks about ends.
        val offset = offsetMinutes(now)
        val dayEnd = CheckIn.dayStartUtc(CheckIn.dayOf(now, offset).plusDays(1), offset)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(Tone.Mind.CHECK_IN_TITLE)
                .setContentText(text)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(big)
                .setContentIntent(openTonight(context))
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setTimeoutAfter(maxOf(dayEnd - now, LocalClock.MINUTE_MS))
                .build(),
        )
    }

    private fun frame(context: Context, hint: String) = RemoteViews(context.packageName, R.layout.notification_checkin).apply {
        setTextViewText(R.id.checkin_hint, hint)
    }

    private fun tile(context: Context, text: String, onTap: PendingIntent) = RemoteViews(context.packageName, R.layout.notification_checkin_tile).apply {
        setTextViewText(R.id.checkin_tile, text)
        setOnClickPendingIntent(R.id.checkin_tile, onTap)
    }

    private fun tap(context: Context, requestCode: Int, pleasant: Int, energy: Int, tension: Int?): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, CheckInTapReceiver::class.java)
            .putExtra(CheckInTapReceiver.EXTRA_PLEASANT, pleasant)
            .putExtra(CheckInTapReceiver.EXTRA_ENERGY, energy)
            .putExtra(CheckInTapReceiver.EXTRA_TENSION, tension ?: 0),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun skip(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_SKIP,
        Intent(context, CheckInTapReceiver::class.java).putExtra(CheckInTapReceiver.EXTRA_SKIP, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openTonight(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_SHOW_LAST_NIGHT, false),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(NocturneApp.PREFS, Context.MODE_PRIVATE)

    private fun offsetMinutes(now: Long) = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60
}

/**
 * A square or a tension tap in the check-in notification. Not exported: only PendingIntents Nocturne made reach it.
 * The square's two values ride along with the tension tap, so the second tap replaces the day's answer with all three.
 */
class CheckInTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pleasant = intent.getIntExtra(EXTRA_PLEASANT, 0)
        val energy = intent.getIntExtra(EXTRA_ENERGY, 0)
        if (intent.getBooleanExtra(EXTRA_SKIP, false) || pleasant !in 1..5 || energy !in 1..5) {
            CheckInNotification.cancel(context)
            return
        }
        val tension = intent.getIntExtra(EXTRA_TENSION, 0).takeIf { it in 1..5 }
        val checkIn = CheckIn(pleasant, energy, tension)
        val app = context.applicationContext as NocturneApp
        val pending = goAsync()
        app.saveCheckIn(checkIn).invokeOnCompletion { failure ->
            if (failure == null && tension == null) CheckInNotification.postTension(context, checkIn)
            pending.finish()
        }
    }

    companion object {
        const val EXTRA_PLEASANT = "pleasant"
        const val EXTRA_ENERGY = "energy"
        const val EXTRA_TENSION = "tension"
        const val EXTRA_SKIP = "skip"
    }
}
