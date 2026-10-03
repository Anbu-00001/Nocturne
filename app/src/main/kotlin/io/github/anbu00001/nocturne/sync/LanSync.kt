package io.github.anbu00001.nocturne.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.laptop.LaptopFileFormat
import io.github.anbu00001.nocturne.core.sync.Pairing
import io.github.anbu00001.nocturne.core.sync.SyncBody
import io.github.anbu00001.nocturne.core.sync.SyncClient
import io.github.anbu00001.nocturne.core.sync.SyncResult
import io.github.anbu00001.nocturne.data.LaptopExport
import io.github.anbu00001.nocturne.data.LaptopImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One exchange with the paired laptop (Phase 5): the phone's sessions and nights go out, the laptop's use comes back and is
 * imported exactly as the adb path imports it (LaptopImport, replacing what its coverage holds), and the nights it moves
 * are re-derived. Sending the same data twice changes nothing, so a repeat, an overlap with a manual run or a retry after
 * a dropped connection is harmless; that is what lets the schedule be as plain as "about every 15 minutes".
 */
class LanSync(
    private val app: NocturneApp,
    private val store: SyncStore,
    private val clientFor: (Pairing) -> SyncClient = { SyncClient(it) },
    private val onWifi: () -> Boolean = { isOnWifi(app) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun run(requireWifi: Boolean = true): SyncState = mutex.withLock {
        val pairing = store.pairing ?: return store.state.value
        val startedAt = now()
        if (requireWifi && !onWifi()) {
            store.record(startedAt, SyncResult.OFF_WIFI)
            return store.state.value
        }
        store.running(true)
        try {
            exchange(pairing, startedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "exchange failed", e)
            store.record(startedAt, SyncResult.FAILED, e.message ?: e.javaClass.simpleName)
        } finally {
            store.running(false)
        }
        store.state.value
    }

    private suspend fun exchange(pairing: Pairing, startedAt: Long) {
        // Holds still while the derived tables are read, so sessions and nights agree; released before the network.
        val (sessions, nights) = app.harvester.whileIdle {
            Pair(
                StringBuilder().also { LaptopExport.sessions(app.database, 0, it) }.toString(),
                StringBuilder().also { LaptopExport.nights(app.database, "2000-01-01", it) }.toString(),
            )
        }
        val body = SyncBody.encode(startedAt, sessions, nights)
        val outcome = withContext(Dispatchers.IO) { clientFor(pairing).exchange(deviceName(), body) }
        if (outcome is SyncClient.Outcome.Replied) {
            import(outcome, startedAt)
        } else {
            val (code, detail) = SyncResult.classify(outcome)
            store.record(startedAt, code, detail)
        }
    }

    private suspend fun import(reply: SyncClient.Outcome.Replied, startedAt: Long) {
        val file = try {
            LaptopFileFormat.parse(reply.text.lineSequence())
        } catch (e: IllegalArgumentException) {
            store.record(startedAt, SyncResult.FAILED, "the laptop's file was refused: ${e.message}")
            return
        }
        var result: LaptopImport.Result? = null
        val nights = app.harvester.updateNights { LaptopImport(app.database).import(file).also { result = it }.changedFromTs }
        store.remember(reply.address)
        store.record(startedAt, SyncResult.OK, spans = checkNotNull(result).spans, nights = nights)
    }

    private companion object {
        const val TAG = "NocturneSync"

        /** The model name, as adb's `getprop ro.product.model` reports it, so the laptop's buckets match across both paths. */
        fun deviceName(): String = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifEmpty { "phone" }

        fun isOnWifi(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }
}
