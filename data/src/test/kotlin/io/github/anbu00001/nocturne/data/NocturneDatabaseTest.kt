package io.github.anbu00001.nocturne.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.glance.WakeTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NocturneDatabaseTest {

    private lateinit var db: NocturneDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java)
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun reinsertingTheSameEventsAddsNothing() = runTest {
        val rows = listOf(
            RawEvent(timestamp = 1_000, utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
            RawEvent(
                timestamp = 1_500, utcOffsetMinutes = 330, eventType = 1,
                packageName = "com.whatsapp", className = "com.whatsapp.Main",
            ),
        )
        assertEquals(2, db.rawEvents().insertAll(rows).count { it != -1L })
        assertEquals(0, db.rawEvents().insertAll(rows).count { it != -1L })
        assertEquals(2L, db.rawEvents().count())
    }

    @Test
    fun twoActivitiesOfOneAppInTheSameMillisecondAreBothKept() = runTest {
        val a = RawEvent(timestamp = 2_000, utcOffsetMinutes = 0, eventType = 23, packageName = "com.example", className = "A")
        db.rawEvents().insertAll(listOf(a, a.copy(className = "B")))
        assertEquals(2L, db.rawEvents().count())
    }

    @Test
    fun afterElevenPmUsesEachSessionsOwnOffsetAcrossDstAndTravel() = runTest {
        fun utc(iso: String) = Instant.parse(iso).toEpochMilli()
        db.sessions().insertAll(
            listOf(
                session(utc("2026-10-24T22:30:00Z"), offset = 60), // 23:30 BST
                session(utc("2026-10-25T23:30:00Z"), offset = 0), // 23:30 GMT, after the change
                session(utc("2026-10-26T22:30:00Z"), offset = 0), // 22:30 GMT
                session(utc("2026-10-31T18:00:00Z"), offset = 330), // 23:30 IST
            ),
        )
        assertEquals(3, db.sessions().countStartingAtOrAfterLocal(23 * 60))
    }

    @Test
    fun nightTotalsCountOnlyGlancesTheUserMade() = runTest {
        db.sessions().insertAll(
            listOf(
                session(1_000_000, kind = SessionKind.GLANCE_NO_UNLOCK, countsAsGlance = true, durationMs = 3_000),
                // An alarm dismissed from the lock screen: a GLANCE_NO_UNLOCK that does not count.
                session(2_000_000, kind = SessionKind.GLANCE_NO_UNLOCK, countsAsGlance = false, durationMs = 5_000),
                session(3_000_000, kind = SessionKind.EXTENDED, durationMs = 600_000, evening = true),
            ),
        )
        assertEquals(
            NightTotals("2026-09-14", glances = 1, lockScreenGlances = 1, sessions = 3, eveningScreenMs = 600_000),
            db.sessions().observeNightTotals().first().single(),
        )
    }

    @Test
    fun deletingSessionsCascadesToTheirApps() = runTest {
        db.sessions().insertAll(listOf(session(5_000)))
        db.sessions().insertApps(listOf(SessionAppEntity(5_000, "com.example", 1_000)))
        db.sessions().deleteFrom(0)
        assertEquals(emptyList<SessionAppEntity>(), db.sessions().appsFor(5_000))
    }

    @Test
    fun csvExportWritesEveryRowInOrderAndQuotesWhenNeeded() = runTest {
        db.rawEvents().insertAll(
            listOf(
                RawEvent(timestamp = 20, utcOffsetMinutes = 330, eventType = 1, packageName = "b", className = "x,y"),
                RawEvent(timestamp = 10, utcOffsetMinutes = 330, eventType = 15, packageName = "android"),
            ),
        )
        val out = StringBuilder()
        assertEquals(2L, db.rawEvents().writeCsv(out))
        val lines = out.lines().filter { it.isNotEmpty() }
        assertEquals("id,timestamp_utc_ms,utc_offset_minutes,event_type,package_name,class_name", lines[0])
        assertEquals("2,10,330,15,android,", lines[1])
        assertEquals("1,20,330,1,b,\"x,y\"", lines[2])
    }

    @Test
    fun namesAreStoredOnceComeBackExactlyAndGoWithDeleteAll() = runTest {
        fun names() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM event_components").use { it.moveToFirst(); it.getInt(0) }
        val chat = RawEvent(timestamp = 1_000, utcOffsetMinutes = 330, eventType = 1, packageName = "com.whatsapp", className = "com.whatsapp.Main")
        db.rawEvents().insertAll(listOf(chat, chat.copy(timestamp = 2_000), RawEvent(timestamp = 1_500, utcOffsetMinutes = 330, eventType = 17, packageName = "android")))
        db.rawEvents().insertAll(listOf(chat.copy(timestamp = 3_000, className = "")))

        val page = db.rawEvents().pageAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10)
        assertEquals(listOf(1_000L, 1_500L, 2_000L, 3_000L), page.map { it.timestamp })
        assertEquals(listOf("com.whatsapp", "android", "com.whatsapp", "com.whatsapp"), page.map { it.packageName })
        assertEquals(listOf("com.whatsapp.Main", "", "com.whatsapp.Main", ""), page.map { it.className })
        assertEquals(3, names())

        db.rawEvents().deleteAll()
        assertEquals(0L, db.rawEvents().count())
        assertEquals(0, names())
    }

    private fun session(
        startTs: Long,
        offset: Int = 0,
        kind: SessionKind = SessionKind.SHORT,
        countsAsGlance: Boolean = false,
        durationMs: Long = 60_000,
        evening: Boolean = false,
    ) = SessionEntity(
        startTs = startTs,
        endTs = startTs + durationMs,
        kind = kind,
        unlocked = kind != SessionKind.GLANCE_NO_UNLOCK,
        trigger = WakeTrigger.UNKNOWN,
        countsAsGlance = countsAsGlance,
        dominantPackage = null,
        appCount = 0,
        endInferred = false,
        utcOffsetMinutes = offset,
        nightDate = "2026-09-14",
        inEveningWindow = evening,
        sleepOnsetOffsetMin = null,
    )
}
