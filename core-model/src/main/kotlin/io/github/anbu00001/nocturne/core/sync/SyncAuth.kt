package io.github.anbu00001.nocturne.core.sync

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a request to the laptop proves (Phase 5): the phone holds the secret given at pairing. The HMAC covers the method,
 * path, device, time, a one-use nonce and the body's hash, so a stranger on the same Wi-Fi can neither pull the laptop's
 * use nor push false sessions, and a captured request is no use after five minutes or twice. tools/activitywatch/lansync.py
 * is the laptop's copy; both read core-model/src/test/resources/sync/vector.txt.
 */
object SyncAuth {
    const val PROTOCOL = "nocturne-sync-v1"
    const val PATH = "/v1/exchange"

    fun canonical(device: String, timeMs: Long, nonce: String, body: ByteArray): ByteArray =
        listOf(PROTOCOL, "POST", PATH, device, timeMs.toString(), nonce, sha256Hex(body)).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun sign(secretHex: String, device: String, timeMs: Long, nonce: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(secretHex), "HmacSHA256"))
        return toHex(mac.doFinal(canonical(device, timeMs, nonce, body)))
    }

    /** 16 random bytes as 32 hex characters. */
    fun newNonce(random: SecureRandom = SecureRandom()): String = toHex(ByteArray(16).also(random::nextBytes))
}

fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hexadecimal" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
