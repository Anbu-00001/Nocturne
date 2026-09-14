package io.github.anbu00001.nocturne

import android.app.Application
import io.github.anbu00001.nocturne.collector.AndroidPowerSource
import io.github.anbu00001.nocturne.collector.AndroidUsageEventSource
import io.github.anbu00001.nocturne.collector.CollectorHost
import io.github.anbu00001.nocturne.collector.DeviceProfile
import io.github.anbu00001.nocturne.collector.HarvestScheduler
import io.github.anbu00001.nocturne.collector.Harvester
import io.github.anbu00001.nocturne.core.glance.CLASSIFIER_VERSION
import io.github.anbu00001.nocturne.core.sleep.SLEEP_MODEL_VERSION
import io.github.anbu00001.nocturne.data.DerivedTables
import io.github.anbu00001.nocturne.data.NocturneDatabase
import io.github.anbu00001.nocturne.data.SleepReportEntity
import io.github.anbu00001.nocturne.ui.AppLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: one database, one harvester, shared by the UI, the worker and the receivers. */
class NocturneApp : Application(), CollectorHost {

    /** Outlives any screen, so a harvest or export started from the UI finishes if the user leaves. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NocturneDatabase by lazy { NocturneDatabase.open(this) }
    val labels: AppLabels by lazy { AppLabels(this) }
    val deviceProfile by lazy { DeviceProfile(this) }

    override val harvester: Harvester by lazy {
        Harvester(
            db = database,
            source = AndroidUsageEventSource(this),
            derived = DerivedTables(database),
            configFor = { keyguardSeen -> deviceProfile.classifierConfig(keyguardSeen) },
            sleepConfig = { deviceProfile.sleepConfig() },
            power = AndroidPowerSource(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        HarvestScheduler.ensureScheduled(this)
        rescoreIfModelChanged()
    }

    /** Spec §5: when the classifier or sleep model improves, the whole history is re-scored from raw_events. */
    private fun rescoreIfModelChanged() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val version = "$CLASSIFIER_VERSION.$SLEEP_MODEL_VERSION"
        if (prefs.getString(KEY_MODEL_VERSION, null) == version) return
        appScope.launch {
            harvester.recomputeAll()
            prefs.edit().putString(KEY_MODEL_VERSION, version).apply()
        }
    }

    fun harvestNow() {
        appScope.launch { harvester.harvest() }
    }

    /** The user's own sleep times for a night (spec §6.3): kept as entered, then every night is re-derived. */
    fun saveSleepReport(nightDate: String, onsetTs: Long, wakeTs: Long, utcOffsetMinutes: Int) {
        appScope.launch {
            database.sleep().upsertReport(SleepReportEntity(nightDate, onsetTs, wakeTs, utcOffsetMinutes, System.currentTimeMillis()))
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
