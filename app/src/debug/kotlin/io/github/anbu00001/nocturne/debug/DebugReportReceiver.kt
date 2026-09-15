package io.github.anbu00001.nocturne.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.anbu00001.nocturne.NocturneApp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Debug builds only: enters sleep times from the laptop without driving the phone's screen, which would itself become
 * events in the night being measured. The receiver requires DUMP, which adb's shell holds and apps cannot, so only
 * `adb shell` can send it. Times are local, in the phone's current zone.
 *
 *   adb shell am broadcast -n io.github.anbu00001.nocturne/.debug.DebugReportReceiver \
 *       --es night 2026-09-09 --es onset 2026-09-10T01:59 --es wake 2026-09-10T10:48
 *   ... --es night 2026-09-14 --ez noSleep true      a night without sleep
 *   ... --es night 2026-09-14 --ez remove true       take an entry back
 */
class DebugReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NocturneApp
        val result = runCatching {
            val night = LocalDate.parse(requireNotNull(intent.getStringExtra("night")) { "--es night YYYY-MM-DD is missing" })
            val zone = ZoneId.systemDefault()
            when {
                intent.getBooleanExtra("remove", false) -> {
                    app.removeSleepReport(night.toString())
                    "removed $night"
                }
                intent.getBooleanExtra("noSleep", false) -> {
                    app.saveNoSleepReport(night.toString(), night.atTime(12, 0).atZone(zone).offset.totalSeconds / 60)
                    "no sleep on $night"
                }
                else -> {
                    val onset = LocalDateTime.parse(requireNotNull(intent.getStringExtra("onset")) { "--es onset is missing" }).atZone(zone)
                    val wake = LocalDateTime.parse(requireNotNull(intent.getStringExtra("wake")) { "--es wake is missing" }).atZone(zone)
                    require(wake.isAfter(onset)) { "wake must be after onset" }
                    app.saveSleepReport(night.toString(), onset.toInstant().toEpochMilli(), wake.toInstant().toEpochMilli(), onset.offset.totalSeconds / 60)
                    "saved $night: $onset to $wake"
                }
            }
        }
        // `am broadcast` sends in order and prints this back.
        setResult(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, result.getOrElse { it.message }, null)
    }
}
