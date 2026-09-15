package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema 2 (Phase 2) only adds: the sleep_reports and power_samples tables, sessions.lastActivityTs, and the
 * nights table's raw-inference and window columns. Schema 3 (Phase 2b) only adds light_samples' duration and
 * display-state columns and the nights table's light coverage columns. Schema 4 (analytics 2.6) only adds the
 * window_metrics and model_runs tables and nights.modelRunId. Additive changes are what Room's AutoMigration
 * handles; MigrationTest builds each earlier schema from its JSON and opens it, and each migration is run against
 * a copy of the database pulled from the phone before installing.
 */
@Database(
    entities = [
        RawEventEntity::class,
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
    ],
    version = 4,
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

    companion object {
        const val FILE_NAME = "nocturne.db"

        // Never fallbackToDestructiveMigration: raw_events is the only copy of any history older
        // than the OS's 10 days. Every schema change ships a real migration.
        fun open(context: Context): NocturneDatabase =
            Room.databaseBuilder(context.applicationContext, NocturneDatabase::class.java, FILE_NAME).build()
    }
}
