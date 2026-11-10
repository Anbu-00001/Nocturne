package io.github.anbu00001.nocturne.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.anbu00001.nocturne.MainActivity
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.R
import io.github.anbu00001.nocturne.core.mind.CheckIn
import io.github.anbu00001.nocturne.data.CheckIns
import io.github.anbu00001.nocturne.data.Digests
import io.github.anbu00001.nocturne.tone.Tone
import io.github.anbu00001.nocturne.ui.GlanceText
import io.github.anbu00001.nocturne.ui.formatNight
import kotlinx.coroutines.launch

/**
 * The home-screen widget: last night's verdict, "About right" in one tap, the week in a line, and the laptop's silence
 * when there is one. It exists because the app was opened once a week by its second month while its estimates sat below
 * the confidence the evening window and the sentinel need; the only cure for that is the user's word, and two time
 * pickers three taps deep were answered twice in 68 nights. Most looks at a tracker are glances of a few seconds
 * (Gouveia et al., UbiComp 2015), and a diary on the home screen was kept where one inside an app was not (SleepTight,
 * Choe et al., UbiComp 2015).
 *
 * It is not a notification (spec §7): it never lights the screen, sounds or appears anywhere the user did not put it.
 * Plain RemoteViews, not Glance: three lines of text and two buttons do not need a second Compose runtime in the APK.
 */
class NocturneWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        /** Redraws every placed widget from the database, off the main thread. Costs nothing when none is placed. */
        fun refresh(context: Context) {
            val app = context.applicationContext as NocturneApp
            val manager = AppWidgetManager.getInstance(app) ?: return
            val widget = ComponentName(app, NocturneWidget::class.java)
            if (manager.getAppWidgetIds(widget).isEmpty()) return
            app.appScope.launch {
                val now = System.currentTimeMillis()
                val offset = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds / 60
                manager.updateAppWidget(widget, views(app, app.digests.glance(now), CheckIns(app.database).today(now, offset)?.checkIn))
            }
        }

        internal fun views(context: Context, glance: Digests.Glance, today: CheckIn? = null): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nocturne)
            val night = glance.night
            views.setOnClickPendingIntent(R.id.widget_root, open(context, enterTimes = false, requestCode = 1))
            if (night == null) {
                views.setTextViewText(R.id.widget_title, Tone.APP_NAME)
                views.setTextViewText(R.id.widget_night, Tone.Widget.EMPTY)
                views.setViewVisibility(R.id.widget_actions, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_title, Tone.LastNight.nightTitle(formatNight(night.dateOfNight)))
                views.setTextViewText(R.id.widget_night, GlanceText.night(night, glance.report))
                val ask = GlanceText.canConfirm(night, glance.report)
                views.setViewVisibility(R.id.widget_actions, if (ask) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widget_confirm, Tone.Sleep.ABOUT_RIGHT)
                views.setTextViewText(R.id.widget_correct, Tone.Sleep.NOT_RIGHT)
                views.setOnClickPendingIntent(R.id.widget_confirm, confirm(context, night.dateOfNight))
                views.setOnClickPendingIntent(R.id.widget_correct, open(context, enterTimes = true, requestCode = 2))
            }
            line(views, R.id.widget_week, glance.week?.let(GlanceText::weekShort))
            line(views, R.id.widget_before, glance.week?.let(GlanceText::beforeShort))
            // The check-in: today's answer once given, else the button that opens Tonight, where the grid takes one tap.
            views.setTextViewText(R.id.widget_checkin, today?.let { Tone.Mind.today(it.pleasant, it.energy, it.tension) } ?: Tone.Mind.WIDGET_ASK)
            views.setOnClickPendingIntent(R.id.widget_checkin, open(context, enterTimes = false, requestCode = 4, lastNight = false))
            line(views, R.id.widget_laptop, glance.laptopQuietSince?.let(GlanceText::laptopQuiet))
            return views
        }

        private fun line(views: RemoteViews, id: Int, text: String?) {
            views.setViewVisibility(id, if (text == null) View.GONE else View.VISIBLE)
            if (text != null) views.setTextViewText(id, text)
        }

        /** Opens Nocturne on Last night, with the time pickers already up for "Not right". A fresh task, so the extras are read. */
        private fun open(context: Context, enterTimes: Boolean, requestCode: Int, lastNight: Boolean = true): PendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_SHOW_LAST_NIGHT, lastNight)
                .putExtra(MainActivity.EXTRA_ENTER_TIMES, enterTimes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun confirm(context: Context, nightDate: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, WidgetConfirmReceiver::class.java).putExtra(WidgetConfirmReceiver.EXTRA_NIGHT, nightDate),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * "About right" on the widget. Not exported: only a PendingIntent Nocturne made itself reaches it, so no other app on the
 * phone can mark a night as confirmed. The night's date rides in the intent, so a tap on a widget drawn before the next
 * night's verdict arrived still confirms the night it was showing.
 */
class WidgetConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val night = intent.getStringExtra(EXTRA_NIGHT) ?: return
        val app = context.applicationContext as NocturneApp
        val pending = goAsync()
        app.confirmSleepEstimate(night).invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val EXTRA_NIGHT = "night"
    }
}
