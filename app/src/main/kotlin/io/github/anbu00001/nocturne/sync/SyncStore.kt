package io.github.anbu00001.nocturne.sync

import android.content.Context
import io.github.anbu00001.nocturne.core.sync.Pairing
import io.github.anbu00001.nocturne.core.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncState(
    val pairing: Pairing? = null,
    val lastAttemptAt: Long? = null,
    val lastOkAt: Long? = null,
    val result: String = SyncResult.NONE,
    val detail: String? = null,
    val spans: Int = 0,
    val nights: Int = 0,
    val running: Boolean = false,
)

/**
 * The pairing and the latest outcome, in preferences (app-private, and `allowBackup` is off). The secret is only as safe as
 * the app's sandbox; anyone who can read it can already read the database, which holds more.
 */
class SyncStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val pairing: Pairing? get() = _state.value.pairing

    /** Replaces the pairing and clears what the last laptop's exchanges said. */
    @Synchronized
    fun pair(pairing: Pairing) {
        prefs.edit().clear()
            .putString(K_NAME, pairing.name).putString(K_HOSTS, pairing.hosts.joinToString(" ")).putString(K_ADDRESS, pairing.address)
            .putInt(K_PORT, pairing.port).putString(K_CERT, pairing.certSha256).putString(K_SECRET, pairing.secretHex)
            .commit()
        _state.value = read()
    }

    /** Where the laptop answered this time, so the next attempt starts there. */
    @Synchronized
    fun remember(address: String) {
        val current = pairing ?: return
        if (current.address == address) return
        prefs.edit().putString(K_ADDRESS, address).commit()
        _state.value = read()
    }

    @Synchronized
    fun forget() {
        prefs.edit().clear().commit()
        _state.value = read()
    }

    @Synchronized
    fun running(now: Boolean) {
        _state.value = _state.value.copy(running = now)
    }

    @Synchronized
    fun record(attemptedAt: Long, result: String, detail: String? = null, spans: Int = 0, nights: Int = 0) {
        if (pairing == null) return
        val edit = prefs.edit().putLong(K_ATTEMPT, attemptedAt).putString(K_RESULT, result).putString(K_DETAIL, detail)
        if (result == SyncResult.OK) edit.putLong(K_OK, attemptedAt).putInt(K_SPANS, spans).putInt(K_NIGHTS, nights)
        edit.commit()
        _state.value = read().copy(running = _state.value.running)
    }

    private fun read(): SyncState {
        val pairing = runCatching {
            Pairing(
                name = prefs.getString(K_NAME, null) ?: return@runCatching null,
                hosts = prefs.getString(K_HOSTS, "").orEmpty().split(' ').filter { it.isNotEmpty() },
                address = prefs.getString(K_ADDRESS, null),
                port = prefs.getInt(K_PORT, 0),
                certSha256 = prefs.getString(K_CERT, "").orEmpty(),
                secretHex = prefs.getString(K_SECRET, "").orEmpty(),
            )
        }.getOrNull()
        return SyncState(
            pairing = pairing,
            lastAttemptAt = prefs.getLong(K_ATTEMPT, 0).takeIf { it > 0 },
            lastOkAt = prefs.getLong(K_OK, 0).takeIf { it > 0 },
            result = prefs.getString(K_RESULT, null) ?: SyncResult.NONE,
            detail = prefs.getString(K_DETAIL, null),
            spans = prefs.getInt(K_SPANS, 0),
            nights = prefs.getInt(K_NIGHTS, 0),
        )
    }

    private companion object {
        const val PREFS = "lan_sync"
        const val K_NAME = "name"
        const val K_HOSTS = "hosts"
        const val K_ADDRESS = "address"
        const val K_PORT = "port"
        const val K_CERT = "cert"
        const val K_SECRET = "secret"
        const val K_ATTEMPT = "attemptedAt"
        const val K_OK = "okAt"
        const val K_RESULT = "result"
        const val K_DETAIL = "detail"
        const val K_SPANS = "spans"
        const val K_NIGHTS = "nights"
    }
}

/**
 * How the latest `content write .../pair` went, kept apart from the pairing so that forgetting a laptop does not forget that
 * a pairing was refused. Each write takes the next [sequence]; the laptop waits for the one after the number it saw.
 */
class PairLog(context: Context) {
    private val prefs = context.getSharedPreferences("lan_pair", Context.MODE_PRIVATE)

    data class Entry(val sequence: Long, val outcome: String, val error: String?)

    @Synchronized
    fun begin(): Long {
        val next = prefs.getLong("sequence", 0) + 1
        prefs.edit().putLong("sequence", next).putString("outcome", RUNNING).putString("error", null).commit()
        return next
    }

    @Synchronized
    fun finish(sequence: Long, error: String?) {
        if (prefs.getLong("sequence", 0) != sequence) return
        prefs.edit().putString("outcome", if (error == null) OK else FAILED).putString("error", error).commit()
    }

    fun read() = Entry(prefs.getLong("sequence", 0), prefs.getString("outcome", null) ?: "none", prefs.getString("error", null))

    companion object {
        const val RUNNING = "running"
        const val OK = "ok"
        const val FAILED = "failed"
    }
}
