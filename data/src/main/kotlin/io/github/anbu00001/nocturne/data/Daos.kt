package io.github.anbu00001.nocturne.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEventDao {
    /** Returns -1 for each row the natural key already holds: re-harvesting is a no-op by construction. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<RawEventEntity>): List<Long>

    /** Keyset page in (timestamp, id) order; id breaks same-millisecond ties in harvest order. */
    @Query(
        """SELECT * FROM raw_events
           WHERE timestamp > :afterTs OR (timestamp = :afterTs AND id > :afterId)
           ORDER BY timestamp, id LIMIT :limit""",
    )
    suspend fun pageAfter(afterTs: Long, afterId: Long, limit: Int): List<RawEventEntity>

    @Query("SELECT MAX(timestamp) FROM raw_events WHERE eventType IN (:types) AND timestamp < :beforeTs")
    suspend fun lastOfTypesBefore(types: List<Int>, beforeTs: Long): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM raw_events WHERE eventType = :type AND timestamp >= :sinceTs)")
    suspend fun anySince(type: Int, sinceTs: Long): Boolean

    @Query("SELECT COUNT(*) FROM raw_events")
    fun observeCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM raw_events")
    suspend fun count(): Long

    /** Only for the user's explicit "delete all data". */
    @Query("DELETE FROM raw_events")
    suspend fun deleteAll()
}

data class NightTotals(
    val nightDate: String,
    val glances: Int,
    val lockScreenGlances: Int,
    val sessions: Int,
    val eveningScreenMs: Long,
)

data class PackageCount(val packageName: String, val n: Int)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<SessionAppEntity>)

    /** session_apps rows go with them (ON DELETE CASCADE). */
    @Query("DELETE FROM sessions WHERE startTs >= :fromTs")
    suspend fun deleteFrom(fromTs: Long)

    @Query("SELECT MAX(startTs) FROM sessions WHERE startTs < :beforeTs")
    suspend fun lastStartBefore(beforeTs: Long): Long?

    @Query("SELECT * FROM sessions WHERE nightDate = :nightDate ORDER BY startTs")
    fun observeNight(nightDate: String): Flow<List<SessionEntity>>

    @Query("SELECT DISTINCT nightDate FROM sessions ORDER BY nightDate DESC")
    fun observeNightDates(): Flow<List<String>>

    @Query(
        """SELECT nightDate,
                  SUM(countsAsGlance) AS glances,
                  SUM(CASE WHEN countsAsGlance AND kind = 'GLANCE_NO_UNLOCK' THEN 1 ELSE 0 END) AS lockScreenGlances,
                  COUNT(*) AS sessions,
                  SUM(CASE WHEN inEveningWindow THEN endTs - startTs ELSE 0 END) AS eveningScreenMs
           FROM sessions GROUP BY nightDate ORDER BY nightDate""",
    )
    fun observeNightTotals(): Flow<List<NightTotals>>

    /** Local wall-clock filter using each session's own captured offset (spec §5, §10). */
    @Query(
        """SELECT COUNT(*) FROM sessions
           WHERE ((startTs + utcOffsetMinutes * 60000) % 86400000) >= :fromMinuteOfDay * 60000""",
    )
    suspend fun countStartingAtOrAfterLocal(fromMinuteOfDay: Int): Int

    /** Every app in the foreground on a never-unlocked wake, attributed or not, for tuning the package lists. */
    @Query(
        """SELECT a.packageName AS packageName, COUNT(*) AS n FROM session_apps a
           JOIN sessions s ON s.startTs = a.sessionStartTs
           WHERE s.unlocked = 0 AND s.startTs >= :sinceTs
           GROUP BY a.packageName ORDER BY n DESC LIMIT 20""",
    )
    suspend fun lockedWakePackages(sinceTs: Long): List<PackageCount>

    @Query("SELECT * FROM sessions ORDER BY startTs")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM session_apps WHERE sessionStartTs = :startTs ORDER BY foregroundMs DESC")
    suspend fun appsFor(startTs: Long): List<SessionAppEntity>

    @Query("SELECT COUNT(*) FROM sessions")
    fun observeCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Long
}

@Dao
interface HarvestDao {
    @Insert
    suspend fun insertRun(run: HarvestRunEntity): Long

    @Query("SELECT MAX(queryTo) FROM harvest_runs WHERE outcome = 'OK'")
    suspend fun cursor(): Long?

    @Query("SELECT * FROM harvest_runs ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestRun(): Flow<HarvestRunEntity?>

    @Query("SELECT MAX(finishedAt) FROM harvest_runs WHERE outcome = 'OK'")
    fun observeLastSuccess(): Flow<Long?>

    @Query("DELETE FROM harvest_runs")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertZone(zone: ZoneChangeEntity)

    @Query("SELECT * FROM zone_changes ORDER BY sinceTs")
    suspend fun zones(): List<ZoneChangeEntity>

    @Query("SELECT * FROM zone_changes ORDER BY sinceTs DESC LIMIT 1")
    suspend fun latestZone(): ZoneChangeEntity?
}
