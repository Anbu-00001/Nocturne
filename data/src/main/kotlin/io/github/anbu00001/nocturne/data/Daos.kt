package io.github.anbu00001.nocturne.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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

    @Query("SELECT MIN(timestamp) FROM raw_events")
    suspend fun firstTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM raw_events")
    suspend fun lastTimestamp(): Long?

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

    @Query("SELECT utcOffsetMinutes FROM sessions WHERE startTs <= :ts ORDER BY startTs DESC LIMIT 1")
    suspend fun offsetAtOrBefore(ts: Long): Int?

    @Query("SELECT * FROM sessions WHERE startTs >= :fromTs ORDER BY startTs")
    suspend fun startingFrom(fromTs: Long): List<SessionEntity>

    @Query("SELECT DISTINCT nightDate FROM sessions ORDER BY nightDate")
    suspend fun nightDates(): List<String>

    @Query("SELECT * FROM sessions WHERE nightDate = :nightDate ORDER BY startTs")
    fun observeNight(nightDate: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE nightDate = :nightDate ORDER BY startTs")
    suspend fun forNight(nightDate: String): List<SessionEntity>

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

    /**
     * Tags one night's sessions against its evening window, half-open and possibly wrapping midnight (the
     * same rule as EveningWindow.contains, on each session's own offset), and against its sleep onset.
     */
    @Query(
        """UPDATE sessions SET
             inEveningWindow = CASE WHEN :startMinute <= :endMinute
                 THEN ((startTs + utcOffsetMinutes * 60000) % 86400000) / 60000 >= :startMinute
                      AND ((startTs + utcOffsetMinutes * 60000) % 86400000) / 60000 < :endMinute
                 ELSE ((startTs + utcOffsetMinutes * 60000) % 86400000) / 60000 >= :startMinute
                      OR ((startTs + utcOffsetMinutes * 60000) % 86400000) / 60000 < :endMinute
                 END,
             sleepOnsetOffsetMin = CASE WHEN :onsetTs IS NULL THEN NULL ELSE (startTs - :onsetTs) / 60000 END
           WHERE nightDate = :nightDate""",
    )
    suspend fun tagNight(nightDate: String, startMinute: Int, endMinute: Int, onsetTs: Long?)

    @Query("SELECT COALESCE(SUM(endTs - startTs), 0) FROM sessions WHERE nightDate = :nightDate AND inEveningWindow")
    suspend fun eveningScreenMs(nightDate: String): Long

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
interface SleepDao {
    @Upsert
    suspend fun upsertNights(nights: List<NightEntity>)

    @Query("DELETE FROM nights WHERE dateOfNight >= :fromDate")
    suspend fun deleteNightsFrom(fromDate: String)

    @Query("SELECT * FROM nights ORDER BY dateOfNight")
    suspend fun nights(): List<NightEntity>

    @Query("SELECT * FROM nights WHERE dateOfNight = :date")
    fun observeNight(date: String): Flow<NightEntity?>

    @Query("SELECT * FROM nights ORDER BY dateOfNight")
    fun observeNights(): Flow<List<NightEntity>>

    @Upsert
    suspend fun upsertReport(report: SleepReportEntity)

    @Query("DELETE FROM sleep_reports WHERE dateOfNight = :date")
    suspend fun deleteReport(date: String)

    @Query("SELECT * FROM sleep_reports ORDER BY dateOfNight")
    suspend fun reports(): List<SleepReportEntity>

    @Query("SELECT * FROM sleep_reports WHERE dateOfNight = :date")
    fun observeReport(date: String): Flow<SleepReportEntity?>

    @Query("SELECT COUNT(*) FROM sleep_reports")
    fun observeReportCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPowerSample(sample: PowerSampleEntity)

    @Query("SELECT * FROM power_samples WHERE timestamp >= :fromTs ORDER BY timestamp")
    suspend fun powerSamplesFrom(fromTs: Long): List<PowerSampleEntity>
}

@Dao
interface LightDao {
    @Insert
    suspend fun insert(sample: LightSampleEntity)

    /** Samples overlapping [fromTs, toTs). A sample lasts at most 30 s, so the index bound a minute early is safe. */
    @Query(
        """SELECT * FROM light_samples
           WHERE timestamp >= :fromTs - 60000 AND timestamp < :toTs AND timestamp + durationMs > :fromTs
           ORDER BY timestamp""",
    )
    suspend fun overlapping(fromTs: Long, toTs: Long): List<LightSampleEntity>

    @Query("SELECT timestamp + durationMs FROM light_samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastEnd(): Long?

    @Query("SELECT * FROM light_samples ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<LightSampleEntity?>

    @Query("SELECT COUNT(*) FROM light_samples WHERE timestamp >= :sinceTs")
    fun observeCountSince(sinceTs: Long): Flow<Int>
}

@Dao
interface MetricsDao {
    @Upsert
    suspend fun upsertWindows(rows: List<WindowMetricEntity>)

    @Query("DELETE FROM window_metrics WHERE endDate >= :fromDate")
    suspend fun deleteWindowsFrom(fromDate: String)

    @Query("SELECT * FROM window_metrics ORDER BY endDate, windowDays, metric")
    suspend fun allWindows(): List<WindowMetricEntity>

    @Query("SELECT * FROM window_metrics WHERE endDate = :endDate ORDER BY windowDays, metric")
    fun observeWindows(endDate: String): Flow<List<WindowMetricEntity>>

    @Insert
    suspend fun insertRun(run: ModelRunEntity): Long

    @Query("SELECT * FROM model_runs ORDER BY id DESC LIMIT 1")
    suspend fun latestRun(): ModelRunEntity?

    @Query("SELECT * FROM model_runs WHERE id = :id")
    suspend fun run(id: Long): ModelRunEntity?

    @Query("UPDATE model_runs SET lastUsedAt = :at WHERE id = :id")
    suspend fun touchRun(id: Long, at: Long)

    @Query("SELECT * FROM model_runs ORDER BY id DESC LIMIT 1")
    fun observeLatestRun(): Flow<ModelRunEntity?>
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
