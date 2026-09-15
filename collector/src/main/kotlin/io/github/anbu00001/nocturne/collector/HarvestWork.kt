package io.github.anbu00001.nocturne.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.anbu00001.nocturne.data.HarvestOutcome
import io.github.anbu00001.nocturne.data.NocturneDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Implemented by the Application so workers, receivers and the light service reach the one database and Harvester. */
interface CollectorHost {
    val database: NocturneDatabase
    val harvester: Harvester
}

class HarvestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = (applicationContext as CollectorHost).harvester.harvest()
        // Each run is also a chance to restart the light sampler if ColorOS stopped it (spec §11 pitfall 10).
        LightService.ensureRunning(applicationContext)
        return if (result.outcome == HarvestOutcome.FAILED) Result.retry() else Result.success()
    }
}

object HarvestScheduler {
    private const val PERIODIC = "nocturne-harvest"
    private const val ONE_OFF = "nocturne-harvest-now"

    /**
     * Every 15 minutes, WorkManager's floor (spec §4.1). No constraints on purpose: it has to run on
     * battery and offline. Because the OS keeps 10 days, even days of missed runs lose nothing.
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<HarvestWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun harvestSoon(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<HarvestWorker>().build())
    }

    fun observePeriodic(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(PERIODIC)
}

/**
 * WorkManager already re-arms periodic work after reboot; re-enqueueing here is belt and braces for
 * ROMs that block its own receiver. The light sampler is a `specialUse` service, which Android 15 still
 * lets BOOT_COMPLETED start (a `dataSync` one it would not).
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                HarvestScheduler.ensureScheduled(context)
                LightService.ensureRunning(context)
            }
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val zoneId = intent.getStringExtra(Intent.EXTRA_TIMEZONE) ?: return
                val harvester = (context.applicationContext as CollectorHost).harvester
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        harvester.recordZone(System.currentTimeMillis(), zoneId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
