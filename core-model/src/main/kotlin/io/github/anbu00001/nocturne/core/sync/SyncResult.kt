package io.github.anbu00001.nocturne.core.sync

/** What an exchange with the laptop came to, as the short codes Settings words and the adb `pair` query reports. */
object SyncResult {
    const val OK = "ok"
    const val UNREACHABLE = "unreachable"
    const val OFF_WIFI = "off_wifi"
    const val WRONG_CERTIFICATE = "wrong_certificate"
    const val REJECTED = "rejected"
    const val CLOCK = "clock"
    const val NO_ACTIVITYWATCH = "no_activitywatch"
    const val FAILED = "failed"
    const val NONE = "none"

    /** A code and a detail for what [outcome] says; a reply is [OK] here, and what the phone then does with its file can still fail. */
    fun classify(outcome: SyncClient.Outcome): Pair<String, String?> = when (outcome) {
        is SyncClient.Outcome.Replied -> OK to null
        is SyncClient.Outcome.Unreachable -> UNREACHABLE to outcome.detail
        is SyncClient.Outcome.WrongCertificate -> WRONG_CERTIFICATE to outcome.address
        is SyncClient.Outcome.Failed -> FAILED to outcome.detail
        is SyncClient.Outcome.Refused -> when {
            outcome.status == 401 -> REJECTED
            outcome.status == 403 && outcome.reason.startsWith("clock") -> CLOCK
            outcome.status == 503 -> NO_ACTIVITYWATCH
            else -> FAILED
        } to "${outcome.status} ${outcome.reason}".trim()
    }
}
