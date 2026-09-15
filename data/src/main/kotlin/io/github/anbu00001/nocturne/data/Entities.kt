package io.github.anbu00001.nocturne.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import io.github.anbu00001.nocturne.core.sleep.SleepSource
import io.github.anbu00001.nocturne.core.time.EveningWindow

/**
 * Append-only and sacred (spec §5): every other table is rebuilt from this one.
 *
 * The natural key adds className to the spec's (timestamp, packageName, eventType), and both text
 * columns are non-null. SQLite treats NULLs as distinct inside a UNIQUE index, so a nullable key
 * column would let INSERT OR IGNORE re-insert those rows on every overlapping harvest.
 * The unique index leads with timestamp and so also serves range scans; a separate timestamp
 * index would only cost space.
 */
@Entity(
    tableName = "raw_events",
    indices = [Index(value = ["timestamp", "eventType", "packageName", "className"], unique = true)],
)
data class RawEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis, UTC. */
    val timestamp: Long,
    /** Offset in force at the event's own instant, never the offset at harvest or read time. */
    val utcOffsetMinutes: Int,
    val eventType: Int,
    /** "android" for SCREEN_* and KEYGUARD_* events (observed on device; the spec expected null). */
    val packageName: String,
    val className: String = "",
)

/**
 * Written by the light service (spec §4.2), one row per 30 s window with the screen on. Recorded, not derived:
 * what the sensor and the display showed cannot be rebuilt later. The foreground app stays null, because
 * sessions already know it exactly from raw_events.
 */
@Entity(tableName = "light_samples", indices = [Index("timestamp")])
data class LightSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Start of the window. */
    val timestamp: Long,
    /** Median over the window; null when the sensor sent nothing. */
    val ambientLux: Float?,
    /** 0..1, normalised against the device's real maximum. */
    val screenBrightness: Float?,
    val screenOn: Boolean,
    val foregroundPackage: String?,
    /** Schema 3. Shorter than 30 s when the screen went off inside the window. */
    @ColumnInfo(defaultValue = "30000") val durationMs: Long = 30_000,
    /** Schema 3. Settings.System.SCREEN_BRIGHTNESS as stored, so a corrected display profile can re-read history. */
    val brightnessSetting: Int? = null,
    val darkUi: Boolean? = null,
    /** Null when the ROM does not expose its warm filter. */
    val warmFilter: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val sensorEvents: Int = 0,
)

/** Derived and rebuildable. Keyed by the wake time, which is unique because raw_events is. */
@Entity(tableName = "sessions", indices = [Index("nightDate")])
data class SessionEntity(
    @PrimaryKey val startTs: Long,
    val endTs: Long,
    val kind: SessionKind,
    val unlocked: Boolean,
    val trigger: WakeTrigger,
    val countsAsGlance: Boolean,
    val dominantPackage: String?,
    val appCount: Int,
    val endInferred: Boolean,
    val utcOffsetMinutes: Int,
    /** ISO date of the evening this session's night starts on. */
    val nightDate: String,
    /** Against the night's evening window: personalised once 7 nights have sleep estimates, provisional before. */
    val inEveningWindow: Boolean,
    /** Minutes relative to the night's sleep onset, negative before it; null when the night has no onset. */
    val sleepOnsetOffsetMin: Int?,
    /** Schema 2. Last event showing use; lets sleep inference see past a long screen-off timeout. */
    @ColumnInfo(defaultValue = "0") val lastActivityTs: Long = 0,
)

/** Per-app foreground time inside a session, for copy like "mostly Instagram". Derived. */
@Entity(
    tableName = "session_apps",
    primaryKeys = ["sessionStartTs", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["startTs"],
            childColumns = ["sessionStartTs"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionAppEntity(
    val sessionStartTs: Long,
    val packageName: String,
    val foregroundMs: Long,
)

/** Derived: one row per night, rebuilt by NightRecomputer from sessions, sleep reports and charging samples. */
@Entity(tableName = "nights")
data class NightEntity(
    /** "2026-09-14" is the night starting that evening. */
    @PrimaryKey val dateOfNight: String,
    /** The user's report where there is one, else the inference with any corrective offset applied. */
    val estimatedSleepOnset: Long?,
    val estimatedWakeTime: Long?,
    val confidence: Float,
    val source: SleepSource,
    val eveningScreenMinutes: Int,
    val postOnsetInterruptions: Int,
    /** Phase 2b onwards: modelled, always shown as the low..high band. */
    val modelledSuppressionPct: Float?,
    val suppressionLowPct: Float?,
    val suppressionHighPct: Float?,
    /** Negative is a delay. Phase 3. */
    val modelledPhaseShiftMin: Float?,
    val melanopicDoseLuxHours: Float?,
    /** Schema 2. The raw inference, kept beside any report so reports can teach corrective offsets. */
    val inferredSleepOnset: Long? = null,
    val inferredWakeTime: Long? = null,
    @ColumnInfo(defaultValue = "0") val inferredConfidence: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val utcOffsetMinutes: Int = 0,
    /** The evening window this night's sessions were tagged with (spec §6.3). */
    @ColumnInfo(defaultValue = "1260") val eveningWindowStartMinute: Int = EveningWindow.PROVISIONAL.startMinute,
    @ColumnInfo(defaultValue = "420") val eveningWindowEndMinute: Int = EveningWindow.PROVISIONAL.endMinute,
    @ColumnInfo(defaultValue = "0") val windowPersonalised: Boolean = false,
    /** Nights the personalised window rests on; 0 while provisional. */
    @ColumnInfo(defaultValue = "0") val windowNights: Int = 0,
    /** Schema 3. Minutes of the light interval with the screen on, and how many of those a light sample covered. */
    @ColumnInfo(defaultValue = "0") val lightScreenMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val lightMeasuredMinutes: Int = 0,
    /** The modelled exposure ran outside the 30 min to 4 h Giménez fitted, so the nearest limit was used. */
    @ColumnInfo(defaultValue = "0") val suppressionDurationClamped: Boolean = false,
    /** Schema 3. No sleep this night, entered by the user or found by the inference; the estimated times are then null. */
    @ColumnInfo(defaultValue = "0") val noSleep: Boolean = false,
    /** Schema 3. The raw inference found no sleep, whatever the user entered; the inferred times are then the quiet stretch that lost. */
    @ColumnInfo(defaultValue = "0") val inferredNoSleep: Boolean = false,
)

/** "I slept about X to Y", or "I did not sleep" (spec §6.3). User data: kept through every recompute, removed only by the user. */
@Entity(tableName = "sleep_reports")
data class SleepReportEntity(
    @PrimaryKey val dateOfNight: String,
    /** Both 0 when [noSleep]. */
    val onsetTs: Long,
    val wakeTs: Long,
    val utcOffsetMinutes: Int,
    val reportedAt: Long,
    /** Schema 3. The user did not sleep this night. */
    @ColumnInfo(defaultValue = "0") val noSleep: Boolean = false,
    /**
     * Schema 3. How long falling asleep took, banded as the Pittsburgh Sleep Quality Index scores its item 2: 0 is
     * 15 min or less, 1 is 16 to 30, 2 is 31 to 60, 3 is over 60. Optional. Collected from Phase 2 because the
     * personal sensitivity fit (spec §6.2, Phase 3c) needs 30 nights of it.
     */
    val sleepLatencyScore: Int? = null,
)

/** Charging state at each harvester run, which corroborates sleep (spec §6.3). Recorded, not derived. */
@Entity(tableName = "power_samples")
data class PowerSampleEntity(
    @PrimaryKey val timestamp: Long,
    /** Plugged in, whether or not the battery is still filling. */
    val charging: Boolean,
    /** -1 when the battery level was not reported. */
    val batteryPercent: Int,
)

/** Phase 3. User data, not derived: never wiped by recompute. */
@Entity(tableName = "reflections")
data class ReflectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val promptedAt: Long,
    val answeredAt: Long?,
    val gapStartTs: Long?,
    val gapEndTs: Long?,
    /** Single tap, 1..4. Never required. */
    val rating: Int?,
    val note: String?,
    val dismissed: Boolean,
)

/** Phase 3. User data. */
@Entity(tableName = "focus_blocks")
data class FocusBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTs: Long,
    val endTs: Long,
    val plannedMinutes: Int,
    val completed: Boolean,
    /** Unlocks during the block. */
    val interruptionCount: Int,
    val label: String?,
)

/** Time zones observed on the device, so each event's offset can be resolved for its own instant. */
@Entity(tableName = "zone_changes")
data class ZoneChangeEntity(
    @PrimaryKey val sinceTs: Long,
    val zoneId: String,
)

enum class HarvestOutcome { OK, USER_LOCKED, NO_ACCESS, FAILED }

/** One row per harvester run: the health log behind "days since last successful harvest". */
@Entity(tableName = "harvest_runs", indices = [Index("startedAt")])
data class HarvestRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: HarvestOutcome,
    /** Query window; the cursor is the latest queryTo of an OK run. */
    val queryFrom: Long?,
    val queryTo: Long?,
    val eventsSeen: Int,
    val eventsInserted: Int,
    val error: String?,
)
