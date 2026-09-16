package io.github.anbu00001.nocturne.data

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything needed to see what Nocturne saw on a phone, for someone without adb: one zip with every table as a CSV and
 * an `about.csv` describing the phone. Primary data (events, light, charging, sleep times entered, gap labels, focus
 * blocks, laptop use), what was derived from it (sessions, nights, regularity windows, the model runs that wrote them),
 * and the harvest log that shows whether the phone let Nocturne run.
 *
 * Left out on purpose: the text of gap notes (only whether a note exists), and Room's and SQLite's own bookkeeping.
 * Times are UTC epoch milliseconds with the offset beside them where the table has one. `raw_events.csv` has the same
 * columns as the single-table export, so the laptop's replay tools read either.
 *
 * The caller keeps harvests and recomputes out while this runs (Harvester.whileIdle), so sessions and nights agree.
 * Tables are read a page at a time in row order; rows the light service adds meanwhile can only land after what was read.
 */
object DataExport {

    /** One table's file, and the columns it takes: all of them unless named. */
    data class Table(val table: String, val columns: String = "*") {
        val file: String get() = "$table.csv"
    }

    data class Summary(val rows: Map<String, Long>) {
        val total: Long get() = rows.values.sum()
    }

    const val RAW_EVENTS = "raw_events"
    const val ABOUT = "about.csv"

    /** Every table besides raw_events, which is written with its names joined in. */
    val TABLES: List<Table> = listOf(
        Table("sessions"),
        Table("session_apps"),
        Table("nights"),
        Table("sleep_reports"),
        Table("power_samples"),
        Table("light_samples"),
        Table("reflections", "id, promptedAt, answeredAt, gapStartTs, gapEndTs, rating, note IS NOT NULL AS hasNote, dismissed, source"),
        Table("focus_blocks"),
        Table("zone_changes"),
        Table("harvest_runs"),
        Table("window_metrics"),
        Table("model_runs"),
        Table("laptop_spans"),
        Table("laptop_hosts"),
    )

    /** Tables deliberately not exported; DataExportTest fails when a new table is in neither list. */
    val NOT_EXPORTED: Set<String> = setOf("event_components", "room_master_table", "sqlite_sequence", "android_metadata")

    private const val PAGE = 5_000

    /** Writes the zip to [out], which it does not close. [about] leads `about.csv`, followed by each table's row count. */
    suspend fun writeZip(db: NocturneDatabase, out: OutputStream, about: List<Pair<String, String>>): Summary = withContext(Dispatchers.IO) {
        val zip = ZipOutputStream(out)
        val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered(64 * 1024)
        val rows = LinkedHashMap<String, Long>()

        zip.putNextEntry(ZipEntry("$RAW_EVENTS.csv"))
        rows[RAW_EVENTS] = db.rawEvents().writeCsv(writer)
        writer.flush()
        zip.closeEntry()

        for (table in TABLES) {
            zip.putNextEntry(ZipEntry(table.file))
            rows[table.table] = writeTable(db, table, writer)
            writer.flush()
            zip.closeEntry()
        }

        zip.putNextEntry(ZipEntry(ABOUT))
        writer.append("key,value\n")
        for ((key, value) in about) writer.append(csvField(key)).append(',').append(csvField(value)).append('\n')
        for ((table, count) in rows) writer.append("rows_$table,$count\n")
        writer.flush()
        zip.closeEntry()
        zip.finish()
        Summary(rows)
    }

    /** Keyset pages by rowid, so a long table is never re-read from its start and no row is read twice. */
    private fun writeTable(db: NocturneDatabase, table: Table, out: Appendable): Long {
        var after = Long.MIN_VALUE
        var count = 0L
        var header = false
        while (true) {
            val query = SimpleSQLiteQuery(
                "SELECT rowid AS export_rowid, ${table.columns} FROM ${table.table} WHERE rowid > ? ORDER BY rowid LIMIT $PAGE",
                arrayOf(after),
            )
            val read = db.query(query).use { cursor ->
                if (!header) {
                    out.append((1 until cursor.columnCount).joinToString(",") { cursor.getColumnName(it) }).append('\n')
                    header = true
                }
                var n = 0
                while (cursor.moveToNext()) {
                    after = cursor.getLong(0)
                    for (i in 1 until cursor.columnCount) {
                        if (i > 1) out.append(',')
                        out.append(field(cursor, i))
                    }
                    out.append('\n')
                    n++
                }
                n
            }
            count += read
            if (read < PAGE) return count
        }
    }

    private fun field(cursor: Cursor, i: Int): String = when (cursor.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> ""
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i).toString()
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i).toString()
        Cursor.FIELD_TYPE_STRING -> csvField(cursor.getString(i))
        else -> "" // No table stores blobs.
    }
}
