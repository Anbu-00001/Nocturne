package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Schema 2 (Phase 2) only adds: the sleep_reports and power_samples tables, sessions.lastActivityTs, and the
 * nights table's raw-inference and window columns. Schema 3 (Phase 2b) only adds light_samples' duration and
 * display-state columns and the nights table's light coverage columns. Schema 4 (analytics 2.6) only adds the
 * window_metrics and model_runs tables and nights.modelRunId. Additive changes are what Room's AutoMigration
 * handles. Schema 5 (analytics 2.7) rebuilds raw_events with interned names, by hand ([MIGRATION_4_5]). Schema 6
 * (Phase 3a) adds reflections.source and drops schema 4's raw_events copy ([MIGRATION_5_6]). Schema 7 (Phase 4) adds
 * laptop use and each night's laptop minutes ([MIGRATION_6_7]).
 * MigrationTest builds each earlier schema from its JSON and opens it, and each migration is run against
 * a copy of the database pulled from the phone before installing.
 */
@Database(
    entities = [
        RawEventEntity::class,
        EventComponentEntity::class,
        LightSampleEntity::class,
        SessionEntity::class,
        SessionAppEntity::class,
        NightEntity::class,
        SleepReportEntity::class,
        PowerSampleEntity::class,
        ReflectionEntity::class,
        FocusBlockEntity::class,
        ZoneChangeEntity::class,
        HarvestRunEntity::class,
        WindowMetricEntity::class,
        ModelRunEntity::class,
        LaptopSpanEntity::class,
        LaptopHostEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class NocturneDatabase : RoomDatabase() {
    abstract fun rawEvents(): RawEventDao
    abstract fun sessions(): SessionDao
    abstract fun sleep(): SleepDao
    abstract fun light(): LightDao
    abstract fun metrics(): MetricsDao
    abstract fun harvest(): HarvestDao
    abstract fun reflections(): ReflectionDao
    abstract fun focus(): FocusDao
    abstract fun laptop(): LaptopDao

    companion object {
        const val FILE_NAME = "nocturne.db"

        /** Every hand-written migration; each builder of this database must add them. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        // Never fallbackToDestructiveMigration: raw_events is the only copy of any history older
        // than the OS's 10 days. Every schema change ships a real migration.
        fun open(context: Context): NocturneDatabase =
            Room.databaseBuilder(context.applicationContext, NocturneDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
