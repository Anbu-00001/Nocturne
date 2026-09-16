package io.github.anbu00001.nocturne.laptop

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.anbu00001.nocturne.NocturneApp
import io.github.anbu00001.nocturne.core.laptop.LaptopFileFormat
import io.github.anbu00001.nocturne.core.time.LocalClock
import io.github.anbu00001.nocturne.data.LaptopExport
import io.github.anbu00001.nocturne.data.LaptopImport
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate

/**
 * The laptop's way in and out (spec §9 Phase 4), over adb and without the network permission Nocturne does not have:
 *
 * - `adb shell content write --uri content://io.github.anbu00001.nocturne.laptop/import < laptop.csv` imports laptop use in
 *   [LaptopFileFormat] and re-derives the nights it moves;
 * - `adb shell content query --uri content://io.github.anbu00001.nocturne.laptop/import` says how the last import went;
 * - `adb shell content read --uri 'content://io.github.anbu00001.nocturne.laptop/sessions?from=<epoch ms>'` and
 *   `.../nights?from=<yyyy-mm-dd>` stream derived rows for ActivityWatch.
 *
 * Reading and writing both need android.permission.DUMP, which adb's shell holds and ordinary apps cannot, so nothing else
 * on the phone reads the history through this. The import runs after the write returns; the laptop polls the query.
 */
class LaptopBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/csv"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments.singleOrNull() != IMPORT) return null
        val status = ImportStatus.read(checkNotNull(context))
        return MatrixCursor(ImportStatus.COLUMNS).apply { addRow(status.row()) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = checkNotNull(context).applicationContext as NocturneApp
        val path = uri.pathSegments.singleOrNull()
        val writing = 'w' in mode
        return when {
            path == IMPORT && writing -> {
                val (source, sink) = ParcelFileDescriptor.createReliablePipe()
                val sequence = ImportStatus.begin(app)
                app.appScope.launch { import(app, source, sequence) }
                sink
            }
            path == SESSIONS && !writing -> {
                val from = uri.getQueryParameter("from")?.toLongOrNull() ?: (System.currentTimeMillis() - DEFAULT_DAYS * LocalClock.DAY_MS)
                stream(app) { LaptopExport.sessions(app.database, from, it) }
            }
            path == NIGHTS && !writing -> {
                val from = uri.getQueryParameter("from")?.let(LocalDate::parse) ?: LocalDate.now().minusDays(DEFAULT_DAYS)
                stream(app) { LaptopExport.nights(app.database, from.toString(), it) }
            }
            else -> throw FileNotFoundException("$uri ($mode)")
        }
    }

    /** Writes [body] into a pipe the caller reads; an error reaches the reader instead of a short file. */
    private fun stream(app: NocturneApp, body: suspend (Appendable) -> Int): ParcelFileDescriptor {
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        app.appScope.launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).bufferedWriter().use { body(it) }
            } catch (e: Exception) {
                Log.w(TAG, "export failed", e)
                runCatching { sink.closeWithError(e.message ?: e.javaClass.simpleName) }
            }
        }
        return source
    }

    private suspend fun import(app: NocturneApp, source: ParcelFileDescriptor, sequence: Long) {
        val status = try {
            val text = ParcelFileDescriptor.AutoCloseInputStream(source).use { it.readCapped(MAX_BYTES) }.toString(Charsets.UTF_8)
            val file = LaptopFileFormat.parse(text.lineSequence())
            var result: LaptopImport.Result? = null
            val nights = app.harvester.updateNights { LaptopImport(app.database).import(file).also { result = it }.changedFromTs }
            ImportStatus.succeeded(sequence, checkNotNull(result), nights)
        } catch (e: Exception) {
            Log.w(TAG, "laptop import failed", e)
            ImportStatus.failed(sequence, e.message ?: e.javaClass.simpleName)
        }
        status.save(app)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        const val TAG = "NocturneLaptop"
        const val IMPORT = "import"
        const val SESSIONS = "sessions"
        const val NIGHTS = "nights"
        const val DEFAULT_DAYS = 14L

        /** Years of laptop use come to well under a megabyte. */
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}

private fun InputStream.readCapped(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > max) throw IOException("laptop file over $max bytes")
        out.write(buffer, 0, n)
    }
}

/**
 * How the latest import went, kept in preferences so the laptop's query finds it even if the process restarted. Each
 * import takes the next [sequence]; the laptop waits for the one after the number it saw before writing.
 */
internal data class ImportStatus(
    val sequence: Long,
    val outcome: String,
    val finishedAt: Long?,
    val hosts: String = "",
    val spans: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val nights: Int = 0,
    val error: String? = null,
) {
    fun row(): Array<Any?> = arrayOf(sequence, outcome, finishedAt, hosts, spans, added, removed, nights, error)

    /** An older import finishing after a newer one began never takes the newer one's place. */
    fun save(context: Context) {
        synchronized(Companion) {
            if (prefs(context).getLong(K_SEQUENCE, 0) > sequence) return
            prefs(context).edit()
                .putLong(K_SEQUENCE, sequence).putString(K_OUTCOME, outcome).putLong(K_FINISHED, finishedAt ?: 0)
                .putString(K_HOSTS, hosts).putInt(K_SPANS, spans).putInt(K_ADDED, added).putInt(K_REMOVED, removed)
                .putInt(K_NIGHTS, nights).putString(K_ERROR, error)
                .commit()
        }
    }

    companion object {
        const val RUNNING = "running"
        const val OK = "ok"
        const val FAILED = "failed"
        val COLUMNS = arrayOf("sequence", "outcome", "finishedAt", "hosts", "spans", "added", "removed", "nights", "error")

        private const val PREFS = "laptop_import"
        private const val K_SEQUENCE = "sequence"
        private const val K_OUTCOME = "outcome"
        private const val K_FINISHED = "finishedAt"
        private const val K_HOSTS = "hosts"
        private const val K_SPANS = "spans"
        private const val K_ADDED = "added"
        private const val K_REMOVED = "removed"
        private const val K_NIGHTS = "nights"
        private const val K_ERROR = "error"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        @Synchronized
        fun begin(context: Context): Long {
            val next = prefs(context).getLong(K_SEQUENCE, 0) + 1
            ImportStatus(next, RUNNING, finishedAt = null).save(context)
            return next
        }

        fun read(context: Context): ImportStatus {
            val p = prefs(context)
            return ImportStatus(
                sequence = p.getLong(K_SEQUENCE, 0),
                outcome = p.getString(K_OUTCOME, null) ?: "none",
                finishedAt = p.getLong(K_FINISHED, 0).takeIf { it > 0 },
                hosts = p.getString(K_HOSTS, null).orEmpty(),
                spans = p.getInt(K_SPANS, 0),
                added = p.getInt(K_ADDED, 0),
                removed = p.getInt(K_REMOVED, 0),
                nights = p.getInt(K_NIGHTS, 0),
                error = p.getString(K_ERROR, null),
            )
        }

        fun succeeded(sequence: Long, result: LaptopImport.Result, nights: Int) = ImportStatus(
            sequence, OK, System.currentTimeMillis(), result.hosts.joinToString(" "), result.spans, result.added, result.removed, nights,
        )

        fun failed(sequence: Long, error: String) = ImportStatus(sequence, FAILED, System.currentTimeMillis(), error = error)
    }
}
