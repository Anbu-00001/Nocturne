package io.github.anbu00001.nocturne.collector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anbu00001.nocturne.core.event.EventType
import io.github.anbu00001.nocturne.core.event.UsageEvent
import io.github.anbu00001.nocturne.core.glance.ClassifierConfig
import io.github.anbu00001.nocturne.core.glance.SessionKind
import io.github.anbu00001.nocturne.core.time.EveningWindow
import io.github.anbu00001.nocturne.data.HarvestOutcome
import io.github.anbu00001.nocturne.data.NocturneDatabase
import io.github.anbu00001.nocturne.data.SessionRecomputer
import io.github.anbu00001.nocturne.data.toEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HarvesterTest {

    private lateinit var db: NocturneDatabase
    private lateinit var harvester: Harvester
    private val source = FakeSource()
    private var clock = BASE

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NocturneDatabase::class.java)
            .build()
        harvester = Harvester(
            db = db,
            source = source,
            recomputer = SessionRecomputer(db),
            configFor = { ClassifierConfig() },
            now = { clock },
            currentZoneId = { ZONE },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun rerunningOverTheSameWindowAddsZeroRows() = runTest {
        clock = at(2_000)
        assertEquals(EVENTS.size, harvester.harvest().eventsInserted)
        val sessionsBefore = db.sessions().all()

        db.harvest().deleteAll() // forget the cursor, so the next run re-reads the whole backfill window
        val again = harvester.harvest()
        assertEquals(HarvestOutcome.OK, again.outcome)
        assertEquals(EVENTS.size, again.eventsSeen)
        assertEquals(0, again.eventsInserted)
        assertEquals(EVENTS.size.toLong(), db.rawEvents().count())
        assertEquals(sessionsBefore, db.sessions().all())
    }

    @Test
    fun sessionsSplitAcrossHarvestsMatchAFullRecompute() = runTest {
        clock = at(200) // mid-way through the extended session
        harvester.harvest()
        assertEquals(listOf(SessionKind.GLANCE_NO_UNLOCK), db.sessions().all().map { it.kind })

        clock = at(1_100) // mid-way through the unlocked glance
        harvester.harvest()
        clock = at(2_000)
        harvester.harvest()
        val incremental = db.sessions().all()
        assertEquals(
            listOf(SessionKind.GLANCE_NO_UNLOCK, SessionKind.EXTENDED, SessionKind.GLANCE_UNLOCKED),
            incremental.map { it.kind },
        )

        SessionRecomputer(db).recomputeAll(ClassifierConfig(), EveningWindow.PROVISIONAL, ZONE)
        assertEquals(incremental, db.sessions().all())
    }

    @Test
    fun beforeTheFirstUnlockNothingIsReadAndTheCursorStays() = runTest {
        clock = at(2_000)
        source.userUnlocked = false
        assertEquals(HarvestOutcome.USER_LOCKED, harvester.harvest().outcome)
        assertNull(db.harvest().cursor())
        assertEquals(0L, db.rawEvents().count())

        source.userUnlocked = true
        assertEquals(EVENTS.size, harvester.harvest().eventsInserted)
    }

    @Test
    fun withoutUsageAccessTheRunIsLoggedAndNothingIsRead() = runTest {
        clock = at(2_000)
        source.usageAccess = false
        assertEquals(HarvestOutcome.NO_ACCESS, harvester.harvest().outcome)
        assertNull(db.harvest().cursor())
    }

    @Test
    fun aFailedQueryDoesNotAdvanceTheCursor() = runTest {
        clock = at(2_000)
        source.failNext = true
        assertEquals(HarvestOutcome.FAILED, harvester.harvest().outcome)
        assertNull(db.harvest().cursor())

        assertEquals(HarvestOutcome.OK, harvester.harvest().outcome)
        assertEquals(at(2_000), db.harvest().cursor())
    }

    @Test
    fun rowsLeftByARunThatDiedHalfwayStillGetSessions() = runTest {
        db.rawEvents().insertAll(EVENTS.map { it.toEntity(utcOffsetMinutes = 330) })
        clock = at(2_000)
        val result = harvester.harvest()
        assertEquals(0, result.eventsInserted)
        assertEquals(3, db.sessions().all().size)
    }

    private class FakeSource : UsageEventSource {
        var usageAccess = true
        var userUnlocked = true
        var failNext = false

        override fun hasUsageAccess() = usageAccess
        override fun isUserUnlocked() = userUnlocked
        override fun query(fromTs: Long, toTs: Long): List<UsageEvent> {
            if (failNext) {
                failNext = false
                error("queryEvents returned null")
            }
            return EVENTS.filter { it.timestamp in fromTs until toTs }
        }
    }

    private companion object {
        const val BASE = 1_789_423_200_000L
        const val ZONE = "Asia/Kolkata"
        const val INSTAGRAM = "com.instagram.android"
        const val LAUNCHER = "com.android.launcher"

        fun at(seconds: Long) = BASE + seconds * 1000
        fun ev(seconds: Long, type: Int, pkg: String = "android") = UsageEvent(at(seconds), type, pkg)

        val EVENTS = listOf(
            ev(0, EventType.KEYGUARD_SHOWN),
            // lock-screen glance
            ev(10, EventType.SCREEN_INTERACTIVE),
            ev(12, EventType.SCREEN_NON_INTERACTIVE),
            // extended session
            ev(100, EventType.SCREEN_INTERACTIVE),
            ev(101, EventType.KEYGUARD_HIDDEN),
            ev(102, EventType.ACTIVITY_RESUMED, INSTAGRAM),
            ev(400, EventType.ACTIVITY_PAUSED, INSTAGRAM),
            ev(400, EventType.SCREEN_NON_INTERACTIVE),
            ev(401, EventType.KEYGUARD_SHOWN),
            // unlocked glance
            ev(1_000, EventType.SCREEN_INTERACTIVE),
            ev(1_001, EventType.KEYGUARD_HIDDEN),
            ev(1_002, EventType.ACTIVITY_RESUMED, LAUNCHER),
            ev(1_010, EventType.SCREEN_NON_INTERACTIVE),
            ev(1_011, EventType.KEYGUARD_SHOWN),
        )
    }
}
