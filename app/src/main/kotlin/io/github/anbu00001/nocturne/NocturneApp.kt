package io.github.anbu00001.nocturne

import android.app.Application
import io.github.anbu00001.nocturne.collector.AndroidPowerSource
import io.github.anbu00001.nocturne.collector.AndroidUsageEventSource
import io.github.anbu00001.nocturne.collector.CollectorHost
import io.github.anbu00001.nocturne.collector.DeviceProfile
import io.github.anbu00001.nocturne.collector.HarvestScheduler
import io.github.anbu00001.nocturne.collector.Harvester
import io.github.anbu00001.nocturne.core.glance.CLASSIFIER_VERSION
import io.github.anbu00001.nocturne.core.metrics.METRICS_VERSION
import io.github.anbu00001.nocturne.core.sleep.SLEEP_MODEL_VERSION
import io.github.anbu00001.nocturne.focus.FocusController
import io.github.anbu00001.nocturne.data.DerivedTables
import io.github.anbu00001.nocturne.data.NocturneDatabase
import io.github.anbu00001.nocturne.data.Reflections
import io.github.anbu00001.nocturne.data.SleepReportEntity
import io.github.anbu00001.nocturne.ui.AppLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: one database, one harvester, shared by the UI, the worker, the receivers and the light service. */
class NocturneApp : Application(), CollectorHost {

    /** Outlives any screen, so a harvest or export started from the UI finishes if the user leaves. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val database: NocturneDatabase by lazy { NocturneDatabase.open(this) }
    val labels: AppLabels by lazy { AppLabels(this) }
    val deviceProfile by lazy { DeviceProfile(this) }
    val reflections by lazy { Reflections(database) }
    val focus by lazy { FocusController(this) }

    override val harvester: Harvester by lazy {
        Harvester(
            db = database,
            source = AndroidUsageEventSource(this),
            derived = DerivedTables(database, deviceProfile.displayProfile()),
            configFor = { keyguardSeen -> deviceProfile.classifierConfig(keyguardSeen) },
            sleepConfig = { deviceProfile.sleepConfig() },
            power = AndroidPowerSource(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        HarvestScheduler.ensureScheduled(this)
        rescoreIfModelChanged()
        focus.resume()
    }

    /**
     * Spec §5: when the classifier, sleep model or regularity metrics improve, the whole history is re-scored from
     * raw_events, and the rows it writes carry a new model run (analytics §6.2).
     */
    private fun rescoreIfModelChanged() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val version = "$CLASSIFIER_VERSION.$SLEEP_MODEL_VERSION.$METRICS_VERSION"
        if (prefs.getString(KEY_MODEL_VERSION, null) == version) return
        appScope.launch {
            harvester.recomputeAll()
            prefs.edit().putString(KEY_MODEL_VERSION, version).apply()
        }
    }

    fun harvestNow(): Job = appScope.launch { harvester.harvest() }

    /** The user's own sleep times for a night (spec §6.3): kept as entered, then every night is re-derived. */
    fun saveSleepReport(nightDate: String, onsetTs: Long, wakeTs: Long, utcOffsetMinutes: Int, sleepLatencyScore: Int? = null) {
        appScope.launch {
            database.sleep().upsertReport(
                SleepReportEntity(nightDate, onsetTs, wakeTs, utcOffsetMinutes, System.currentTimeMillis(), sleepLatencyScore = sleepLatencyScore),
            )
            harvester.recomputeNights()
        }
    }

    /** "I did not sleep": the night stays sleepless through every recompute and teaches no corrective offset. */
    fun saveNoSleepReport(nightDate: String, utcOffsetMinutes: Int) {
        appScope.launch {
            database.sleep().upsertReport(SleepReportEntity(nightDate, 0, 0, utcOffsetMinutes, System.currentTimeMillis(), noSleep = true))
            harvester.recomputeNights()
        }
    }

    fun removeSleepReport(nightDate: String) {
        appScope.launch {
            database.sleep().deleteReport(nightDate)
            harvester.recomputeNights()
        }
    }

    companion object {
        const val PREFS = "nocturne"
        private const val KEY_MODEL_VERSION = "modelVersion"
    }
}
