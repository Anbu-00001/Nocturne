package io.github.anbu00001.nocturne.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.anbu00001.nocturne.NocturneApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The end of a focus block or break. Waits a few seconds for the block to be recorded, then lets the process go. */
class FocusAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NocturneApp
        val startTs = intent.getLongExtra(EXTRA_START_TS, -1).takeIf { it >= 0 }
        val work = app.focus.finish(startTs) ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(RECORD_BUDGET_MS) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_START_TS = "startTs"

        /** A broadcast has about 10 s; the recording carries on past this if the process lives. */
        private const val RECORD_BUDGET_MS = 8_000L
    }
}
