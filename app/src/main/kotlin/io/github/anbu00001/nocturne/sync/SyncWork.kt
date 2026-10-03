package io.github.anbu00001.nocturne.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.anbu00001.nocturne.NocturneApp
import java.util.concurrent.TimeUnit

/** A try at the paired laptop. Never retries: the next period is the retry, and a laptop that is off is the ordinary case. */
class LanSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        (applicationContext as NocturneApp).lanSync.run(requireWifi = true)
        return Result.success()
    }
}

object LanSyncScheduler {
    private const val PERIODIC = "nocturne-lan-sync"

    /**
     * Every 15 minutes, WorkManager's floor, on an unmetered network only (the phone's home Wi-Fi; never mobile data). The
     * harvest runs on the same period and proved reliable on this phone: 34 to 53 runs a day, every gap over 4 hours overnight.
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<LanSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}
