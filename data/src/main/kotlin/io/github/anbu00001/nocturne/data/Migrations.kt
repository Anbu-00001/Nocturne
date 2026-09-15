package io.github.anbu00001.nocturne.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema 4's raw_events, renamed and kept by [MIGRATION_4_5] for one release. The next schema change drops it. */
const val SCHEMA_FOUR_RAW_EVENTS = "raw_events_v4"

/**
 * Schema 5 (analytics 2.7): raw_events' package and class names move into event_components, one row per pair, and each
 * event keeps an integer id instead. Room cannot generate a table rebuild, so this is by hand.
 *
 * - Every event keeps its id, so (timestamp, id) order, and with it harvest order within a millisecond, is unchanged.
 * - Schema 4's table is renamed, not dropped, and stays for one release in case anything here is wrong. Its index
 *   keeps its old name and goes with it; the new index has a different name, so nothing collides.
 * - Before finishing, every old row must have an identical new one. If not, the migration throws, the upgrade
 *   transaction rolls back and the database stays at schema 4 with nothing lost.
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_components` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `className` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_components_packageName_className` " +
                "ON `event_components` (`packageName`, `className`)",
        )
        db.execSQL(
            "INSERT INTO event_components (packageName, className) " +
                "SELECT packageName, className FROM raw_events GROUP BY packageName, className ORDER BY MIN(id)",
        )
        db.execSQL("ALTER TABLE raw_events RENAME TO $SCHEMA_FOUR_RAW_EVENTS")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `raw_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `utcOffsetMinutes` INTEGER NOT NULL, `eventType` INTEGER NOT NULL, " +
                "`componentId` INTEGER NOT NULL)",
        )
        db.execSQL(
            """INSERT INTO raw_events (id, timestamp, utcOffsetMinutes, eventType, componentId)
               SELECT r.id, r.timestamp, r.utcOffsetMinutes, r.eventType, c.id
               FROM $SCHEMA_FOUR_RAW_EVENTS r
               JOIN event_components c ON c.packageName = r.packageName AND c.className = r.className
               ORDER BY r.id""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_events_timestamp_eventType_componentId` " +
                "ON `raw_events` (`timestamp`, `eventType`, `componentId`)",
        )
        val lost = db.query(
            """SELECT COUNT(*) FROM $SCHEMA_FOUR_RAW_EVENTS o WHERE NOT EXISTS (
                   SELECT 1 FROM raw_events n JOIN event_components c ON c.id = n.componentId
                   WHERE n.id = o.id AND n.timestamp = o.timestamp AND n.utcOffsetMinutes = o.utcOffsetMinutes
                   AND n.eventType = o.eventType AND c.packageName = o.packageName AND c.className = o.className)""",
        ).use { it.moveToFirst(); it.getLong(0) }
        check(lost == 0L) { "$lost raw events have no identical row after interning; staying at schema 4" }
    }
}
