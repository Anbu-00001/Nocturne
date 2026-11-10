package io.github.anbu00001.nocturne.widget

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.R
import io.github.anbu00001.nocturne.core.digest.PeriodSummary
import io.github.anbu00001.nocturne.core.mind.CheckIn
import io.github.anbu00001.nocturne.core.digest.WeekDigest
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.data.Digests
import io.github.anbu00001.nocturne.data.NightEntity
import io.github.anbu00001.nocturne.data.SleepReportEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The widget cannot be looked at from the laptop, and a layout RemoteViews refuses shows only "Problem loading widget" on
 * the phone. So the layout is inflated here and its text read back. A plain Application stands in for NocturneApp: drawing
 * needs no database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NocturneWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun draw(glance: Digests.Glance): View = NocturneWidget.views(context, glance).apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun anEstimateIsShownWithBothButtonsTheWeekAndTheQuietLaptop() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val widget = draw(Digests.Glance(night(SleepSource.INFERRED, 0.02f), report = null, week = week, laptopQuietSince = ist("2026-10-17T06:31")))
        assertEquals("Night of Mon 9 Nov", widget.text(R.id.widget_title))
        assertEquals("Asleep about 23:54 to 10:32 (low confidence)", widget.text(R.id.widget_night))
        assertEquals(true, widget.shown(R.id.widget_actions))
        assertEquals("About right", widget.text(R.id.widget_confirm))
        assertEquals("Not right", widget.text(R.id.widget_correct))
        assertEquals("7 nights: onset around 22:51, varying by 1h 28m", widget.text(R.id.widget_week))
        assertEquals("Four weeks before: 23:57, varying by 2h 17m", widget.text(R.id.widget_before))
        assertEquals("No laptop use has arrived since 17 Oct.", widget.text(R.id.widget_laptop))
        assertEquals("How was today", widget.text(R.id.widget_checkin))
    }

    @Test
    fun todaysCheckInIsShownOnceGiven() {
        val glance = Digests.Glance(night(SleepSource.INFERRED, 0.5f), report = null, week = null, laptopQuietSince = null)
        assertEquals("Today: pleasant 4 of 5, energy 2 of 5", NocturneWidget.views(context, glance, CheckIn(4, 2)).apply(context, FrameLayout(context)).text(R.id.widget_checkin))
    }

    @Test
    fun aConfirmedNightAsksNothingAndAFreshLaptopSaysNothing() {
        val confirmed = SleepReportEntity("2026-11-09", ist("2026-11-09T23:54"), ist("2026-11-10T10:32"), 330, 0, confirmed = true)
        val widget = draw(Digests.Glance(night(SleepSource.USER_REPORTED, 1f), confirmed, week = null, laptopQuietSince = null))
        assertEquals("Asleep about 23:54 to 10:32, as you confirmed", widget.text(R.id.widget_night))
        assertEquals(false, widget.shown(R.id.widget_actions))
        assertEquals(false, widget.shown(R.id.widget_week))
        assertEquals(false, widget.shown(R.id.widget_before))
        assertEquals(false, widget.shown(R.id.widget_laptop))
    }

    @Test
    fun beforeTheFirstVerdictThereIsOneLineAndNoButtons() {
        val widget = draw(Digests.Glance(night = null, report = null, week = null, laptopQuietSince = null))
        assertEquals("Nocturne", widget.text(R.id.widget_title))
        assertEquals("No night with a sleep estimate yet.", widget.text(R.id.widget_night))
        assertEquals(false, widget.shown(R.id.widget_actions))
    }

    /** The figures of the week ending 9 Nov 2026 on the phone, rounded. */
    private val week = WeekDigest(
        endDate = LocalDate.parse("2026-11-09"),
        week = PeriodSummary(7, 651.0, 88.2, 557.1, shortNights = 0, longNights = 5, noSleepNights = 0),
        baseline = PeriodSummary(28, 716.5, 137.7, 372.9, shortNights = 7, longNights = 0, noSleepNights = 0),
        lateApps = emptyList(),
    )

    private fun night(source: SleepSource, confidence: Float) = NightEntity(
        dateOfNight = "2026-11-09", estimatedSleepOnset = ist("2026-11-09T23:54"), estimatedWakeTime = ist("2026-11-10T10:32"),
        confidence = confidence, source = source, eveningScreenMinutes = 0, postOnsetInterruptions = 0, modelledSuppressionPct = null,
        suppressionLowPct = null, suppressionHighPct = null, modelledPhaseShiftMin = null, melanopicDoseLuxHours = null, utcOffsetMinutes = 330,
    )

    private fun ist(time: String): Long = LocalDateTime.parse(time).toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
}
