package io.github.anbu00001.nocturne.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.anbu00001.nocturne.MainActivity
import io.github.anbu00001.nocturne.core.focus.RunningTimer

/**
 * The one alarm that ends the running timer.
 *
 * An alarm clock, not `setExactAndAllowWhileIdle`: on the A18 (ColorOS 15) an app's exact alarm was stored with a window
 * of 75% of the time left, Android's rule for inexact alarms, and a 2 min test block ended 90 s late. Alarm clocks keep
 * a zero window there (a 2 min block ended 2 ms late) and wake the phone from Doze. The price is the alarm icon in the
 * status bar while a timer runs. Without exact-alarm permission it falls back to an inexact alarm, and the Focus tab says so.
 */
internal class FocusAlarms(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** USE_EXACT_ALARM grants exact alarms from Android 13; before that SCHEDULE_EXACT_ALARM is granted at install. */
    val exact: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun set(timer: RunningTimer) {
        val operation = operation(timer.startTs)
        try {
            if (exact) {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(timer.endTs, openNocturne(context)), operation)
                Log.i(TAG, "alarm clock in ${timer.remainingMs(System.currentTimeMillis())} ms")
                return
            }
            Log.w(TAG, "exact alarms not allowed; inexact alarm")
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm clock refused; inexact alarm", e)
        }
        // Elapsed time, so a clock change during the block does not move its end.
        val at = SystemClock.elapsedRealtime() + timer.remainingMs(System.currentTimeMillis())
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
    }

    fun cancel(timer: RunningTimer) = alarms.cancel(operation(timer.startTs))

    /** The request code and target are fixed, so a new timer's alarm replaces the old one; the start time says which timer fired. */
    private fun operation(startTs: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ALARM,
        Intent(context, FocusAlarmReceiver::class.java).putExtra(FocusAlarmReceiver.EXTRA_START_TS, startTs),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "NocturneFocus"
        const val REQUEST_ALARM = 41
    }
}

/** Opens Nocturne: from the alarm icon's "next alarm" entry and from the end-of-block notification. */
internal fun openNocturne(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    REQUEST_OPEN,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE,
)

private const val REQUEST_OPEN = 42
