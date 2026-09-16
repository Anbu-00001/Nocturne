package io.github.anbu00001.nocturne.data

/** Streams raw_events as CSV in (timestamp, id) order, a page at a time. Returns the row count. */
suspend fun RawEventDao.writeCsv(out: Appendable): Long {
    out.append("id,timestamp_utc_ms,utc_offset_minutes,event_type,package_name,class_name\n")
    var afterTs = Long.MIN_VALUE
    var afterId = Long.MIN_VALUE
    var rows = 0L
    while (true) {
        val page = pageAfter(afterTs, afterId, 5_000)
        if (page.isEmpty()) return rows
        for (e in page) {
            out.append("${e.id},${e.timestamp},${e.utcOffsetMinutes},${e.eventType},${csvField(e.packageName)},${csvField(e.className)}\n")
        }
        rows += page.size
        afterTs = page.last().timestamp
        afterId = page.last().id
    }
}

internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
