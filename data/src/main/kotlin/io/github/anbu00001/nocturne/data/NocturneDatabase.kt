package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RawEventEntity::class,
        LightSampleEntity::class,
        SessionEntity::class,
        SessionAppEntity::class,
        NightEntity::class,
        ReflectionEntity::class,
        FocusBlockEntity::class,
        ZoneChangeEntity::class,
        HarvestRunEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NocturneDatabase : RoomDatabase() {
    abstract fun rawEvents(): RawEventDao
    abstract fun sessions(): SessionDao
    abstract fun harvest(): HarvestDao

    companion object {
        const val FILE_NAME = "nocturne.db"

        // Never fallbackToDestructiveMigration: raw_events is the only copy of any history older
        // than the OS's 10 days. Every schema change ships a real migration.
        fun open(context: Context): NocturneDatabase =
            Room.databaseBuilder(context.applicationContext, NocturneDatabase::class.java, FILE_NAME).build()
    }
}
