package io.github.anbu00001.nocturne.core.sync

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * The phone's half of Wi-Fi sync (Phase 5): one request to the laptop, which answers with its own use. The phone starts
 * every conversation, so it opens no port; the laptop answers only while it is on, and a missed attempt is the ordinary
 * outcome, reported as [Outcome.Unreachable] without fuss.
 *
 * Trust is the pairing's: TLS with a certificate pinned by its SHA-256 (the name in it is not looked at), a signed request
 * ([SyncAuth]), and connections only to private addresses ([PrivateAddress]). This is the only code in the app that
 * touches the network; NetworkSurfaceTest keeps it so.
 */
class SyncClient(
    private val pairing: Pairing,
    private val now: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { SyncAuth.newNonce(SecureRandom()) },
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val connectTimeoutMs: Int = 2_500,
    private val readTimeoutMs: Int = 30_000,
) {
    sealed interface Outcome {
        /** The laptop's file, from the [address] that answered. */
        data class Replied(val text: String, val address: String) : Outcome

        /** Nothing answered at any address the pairing names: the laptop is off or on another network. */
        data class Unreachable(val detail: String) : Outcome

        /** Something answered at [address] with a certificate that is not the paired laptop's. */
        data class WrongCertificate(val address: String) : Outcome

        /** The laptop answered with an error: [status] and the short [reason] it gave. */
        data class Refused(val status: Int, val reason: String) : Outcome

        /** The laptop was reached and the conversation broke (a timeout while it worked, a reset). */
        data class Failed(val detail: String) : Outcome
    }

    fun exchange(device: String, body: String): Outcome {
        require(Pairing.NAME.matches(device)) { "unusable device name" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        var wrong: String? = null
        val notes = ArrayList<String>()
        for (address in candidates(notes)) {
            when (val outcome = attempt(address, device, bytes)) {
                is Attempt.Done -> return outcome.outcome
                is Attempt.Wrong -> wrong = address
                is Attempt.Silent -> notes += "$address: ${outcome.why}"
            }
        }
        return wrong?.let { Outcome.WrongCertificate(it) } ?: Outcome.Unreachable(notes.joinToString("; ").ifEmpty { "no address to try" })
    }

    /** Where the laptop was last seen first, then what each of its names resolves to now; private addresses only. */
    private fun candidates(notes: MutableList<String>): Sequence<String> = sequence {
        pairing.address?.let { yield(it) }
        for (host in pairing.hosts) {
            val found = try {
                resolve(host)
            } catch (e: UnknownHostException) {
                notes += "$host: not found"
                continue
            }
            for (a in found) {
                val text = a.hostAddress ?: continue
                // A scoped link-local address (fe80::1%wlan0) cannot go in a URL.
                if ('%' in text) continue
                if (!PrivateAddress.isPrivate(a)) {
                    notes += "$host: $text is not a private address"
                    continue
                }
                yield(text)
            }
        }
    }.distinct()

    private sealed interface Attempt {
        data class Done(val outcome: Outcome) : Attempt
        data class Wrong(val address: String) : Attempt
        data class Silent(val why: String) : Attempt
    }

    private fun attempt(address: String, device: String, body: ByteArray): Attempt {
        val time = now()
        val nonce = nonce()
        val host = if (':' in address) "[$address]" else address
        val connection = URL("https://$host:${pairing.port}${SyncAuth.PATH}").openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pairing.certSha256)), SecureRandom()) }.socketFactory
            // The pin is the identity check; the certificate's own name is a placeholder.
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("X-Nocturne-Device", device)
            connection.setRequestProperty("X-Nocturne-Time", time.toString())
            connection.setRequestProperty("X-Nocturne-Nonce", nonce)
            connection.setRequestProperty("X-Nocturne-Auth", SyncAuth.sign(pairing.secretHex, device, time, nonce, body))
            try {
                connection.connect()
            } catch (e: SSLException) {
                return if (e.pinMismatch()) Attempt.Wrong(address) else Attempt.Silent(e.message ?: "TLS failed")
            } catch (e: ConnectException) {
                return Attempt.Silent("no connection")
            } catch (e: SocketTimeoutException) {
                return Attempt.Silent("timed out")
            } catch (e: NoRouteToHostException) {
                return Attempt.Silent("no route")
            }
            return try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readCapped(MAX_REPLY) }
                    ?.toString(Charsets.UTF_8).orEmpty()
                Attempt.Done(if (status in 200..299) Outcome.Replied(text, address) else Outcome.Refused(status, text.trim().take(200)))
            } catch (e: SSLException) {
                if (e.pinMismatch()) Attempt.Wrong(address) else Attempt.Done(Outcome.Failed(e.message ?: "TLS failed"))
            } catch (e: IOException) {
                Attempt.Done(Outcome.Failed(e.message ?: e.javaClass.simpleName))
            }
        } catch (e: IOException) {
            return Attempt.Silent(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /** Accepts the one certificate the pairing names, whoever signed it, while it is within its dates. */
    private class PinnedTrust(private val sha256: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("no client certificates")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val seen = toHex(MessageDigest.getInstance("SHA-256").digest(leaf.encoded))
            if (!MessageDigest.isEqual(seen.toByteArray(), sha256.toByteArray())) throw PinMismatch()
            leaf.checkValidity()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class PinMismatch : CertificateException(PIN_MISMATCH)

    /** By type down the cause chain, or by message where a platform's TLS layer keeps the text of the failure and drops its cause. */
    private fun Throwable.pinMismatch(): Boolean =
        generateSequence(this) { it.cause }.any { it is PinMismatch || it.message?.contains(PIN_MISMATCH) == true }

    private companion object {
        const val PIN_MISMATCH = "not the paired laptop's certificate"

        /** A week of laptop use comes to a few kilobytes. */
        const val MAX_REPLY = 8 * 1024 * 1024
    }
}

private fun java.io.InputStream.readCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > max) throw IOException("reply over $max bytes")
        out.write(buffer, 0, n)
    }
}
