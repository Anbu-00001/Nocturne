package io.github.anbu00001.nocturne.collector

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import io.github.anbu00001.nocturne.core.event.UsageEvent

interface UsageEventSource {
    fun hasUsageAccess(): Boolean

    /** UsageStatsService returns nothing until the first unlock after boot, so check before querying. */
    fun isUserUnlocked(): Boolean

    /** Events in [fromTs, toTs), oldest first. */
    fun query(fromTs: Long, toTs: Long): List<UsageEvent>
}

class AndroidUsageEventSource(private val context: Context) : UsageEventSource {

    private val usageStats = context.getSystemService(UsageStatsManager::class.java)

    override fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    override fun isUserUnlocked(): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

    override fun query(fromTs: Long, toTs: Long): List<UsageEvent> {
        // Null means no permission or a locked user; either way this window was not read, so fail
        // the run rather than let the cursor move past it.
        val events = usageStats.queryEvents(fromTs, toTs) ?: error("queryEvents returned null")
        val out = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            out += UsageEvent(event.timeStamp, event.eventType, event.packageName.orEmpty(), event.className)
        }
        return out
    }
}
