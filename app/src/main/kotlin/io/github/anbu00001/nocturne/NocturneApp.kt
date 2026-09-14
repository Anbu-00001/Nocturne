package io.github.anbu00001.nocturne

import android.app.Application
import io.github.anbu00001.nocturne.collector.AndroidUsageEventSource
import io.github.anbu00001.nocturne.collector.CollectorHost
import io.github.anbu00001.nocturne.collector.DeviceProfile
import io.github.anbu00001.nocturne.collector.HarvestScheduler
import io.github.anbu00001.nocturne.collector.Harvester
import io.github.anbu00001.nocturne.core.glance.CLASSIFIER_VERSION
import io.github.anbu00001.nocturne.data.NocturneDatabase
import io.github.anbu00001.nocturne.data.SessionRecomputer
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
    private val deviceProfile by lazy { DeviceProfile(this) }

    override val harvester: Harvester by lazy {
        Harvester(
            db = database,
            source = AndroidUsageEventSource(this),
            recomputer = SessionRecomputer(database),
            configFor = { keyguardSeen -> deviceProfile.classifierConfig(keyguardSeen) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        HarvestScheduler.ensureScheduled(this)
        rescoreIfClassifierChanged()
    }

    /** Spec §5: when the model improves, the whole history is re-scored from raw_events. */
    private fun rescoreIfClassifierChanged() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getInt(KEY_CLASSIFIER_VERSION, 0) == CLASSIFIER_VERSION) return
        appScope.launch {
            harvester.recomputeAll()
            prefs.edit().putInt(KEY_CLASSIFIER_VERSION, CLASSIFIER_VERSION).apply()
        }
    }

    fun harvestNow() {
        appScope.launch { harvester.harvest() }
    }

    companion object {
        const val PREFS = "nocturne"
        private const val KEY_CLASSIFIER_VERSION = "classifierVersion"
    }
}
