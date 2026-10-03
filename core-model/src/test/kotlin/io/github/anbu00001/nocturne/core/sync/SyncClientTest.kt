package io.github.anbu00001.nocturne.core.sync

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The phone's client against a real TLS server on this machine (a certificate made by the JDK's own keytool, so no key is
 * kept in the repository): what the client pins, what it signs, and what each way of going wrong looks like to the caller.
 */
class SyncClientTest {

    private data class Seen(val headers: Map<String, String>, val body: ByteArray)

    private val vector: Map<String, String> = checkNotNull(javaClass.getResource("/sync/vector.txt")).readText().lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private val body = hexToBytes(vector.getValue("bodyHex")).toString(Charsets.UTF_8)
    private val secret = vector.getValue("secret")

    private lateinit var directory: File
    private lateinit var server: HttpsServer
    private lateinit var fingerprint: String
    private var seen: Seen? = null
    private var respond: (com.sun.net.httpserver.HttpExchange) -> Unit = { it.reply(200, "nocturne-laptop,1\n") }
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun com.sun.net.httpserver.HttpExchange.reply(status: Int, text: String) {
        val bytes = text.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @BeforeTest
    fun start() {
        directory = Files.createTempDirectory("nocturne-sync-test").toFile()
        val keystore = File(directory, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val made = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "laptop", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=nocturne-test",
            "-validity", "30", "-storetype", "PKCS12", "-keystore", keystore.path, "-storepass", "changeit",
        ).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        check(made == 0) { "keytool failed" }
        val store = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        fingerprint = sha256Hex(store.getCertificate("laptop").encoded)
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress(loopback, 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext(SyncAuth.PATH, HttpHandler { exchange ->
                seen = Seen(exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, exchange.requestBody.readBytes())
                respond(exchange)
            })
            start()
        }
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        directory.deleteRecursively()
    }

    private val port get() = server.address.port

    private fun pairing(address: String? = "127.0.0.1", cert: String = fingerprint, port: Int = this.port) =
        Pairing("laptop", listOf("laptop.local"), address, port, cert, secret)

    private fun client(pairing: Pairing, resolve: (String) -> List<InetAddress> = { emptyList() }, readTimeoutMs: Int = 10_000) =
        SyncClient(
            pairing, now = { vector.getValue("time").toLong() }, nonce = { vector.getValue("nonce") },
            resolve = resolve, connectTimeoutMs = 1_500, readTimeoutMs = readTimeoutMs,
        )

    @Test
    fun `an exchange sends the signed body to the pinned laptop and returns its file`() {
        val outcome = client(pairing()).exchange("CPH2591", body)
        assertEquals(SyncClient.Outcome.Replied("nocturne-laptop,1\n", "127.0.0.1"), outcome)
        val request = checkNotNull(seen)
        assertEquals(body, request.body.toString(Charsets.UTF_8))
        // The same HMAC lansync.py computes for this body: the phone's real request builder against the shared vector.
        assertEquals(vector.getValue("signature"), request.headers["x-nocturne-auth"])
        assertEquals(vector.getValue("nonce"), request.headers["x-nocturne-nonce"])
        assertEquals(vector.getValue("time"), request.headers["x-nocturne-time"])
        assertEquals("CPH2591", request.headers["x-nocturne-device"])
    }

    @Test
    fun `a certificate other than the paired one gets nothing, whatever name it answers to`() {
        val outcome = client(pairing(cert = "ab".repeat(32))).exchange("CPH2591", body)
        assertEquals(SyncClient.Outcome.WrongCertificate("127.0.0.1"), outcome)
        assertEquals(null, seen, "the body must not reach a server that failed the pin")
    }

    @Test
    fun `a refusal carries its status and, where the platform hands it over, the laptop's reason`() {
        respond = { it.reply(403, "clock: the phone's and the laptop's times differ by more than five minutes") }
        assertEquals(
            SyncClient.Outcome.Refused(403, "clock: the phone's and the laptop's times differ by more than five minutes"),
            client(pairing()).exchange("CPH2591", body),
        )
        // The JDK's HttpURLConnection keeps the body of a 401 to itself (it expects a challenge); the status is what the app acts on.
        respond = { it.reply(401, "bad signature") }
        assertEquals(401, assertIs<SyncClient.Outcome.Refused>(client(pairing()).exchange("CPH2591", body)).status)
        respond = { it.reply(503, "the laptop could not answer: OSError: ActivityWatch is not running") }
        assertEquals(503, assertIs<SyncClient.Outcome.Refused>(client(pairing()).exchange("CPH2591", body)).status)
    }

    @Test
    fun `nothing listening is unreachable, not an error, and a dead hint falls through to the names`() {
        val closed = ServerSocket(0).use { it.localPort }
        val unreachable = client(pairing(port = closed)).exchange("CPH2591", body)
        assertIs<SyncClient.Outcome.Unreachable>(unreachable)

        // The remembered address is gone (nothing listens at 127.0.0.2), the name now resolves to the laptop.
        val outcome = client(pairing(address = "127.0.0.2"), resolve = { listOf(loopback) }).exchange("CPH2591", body)
        assertEquals(SyncClient.Outcome.Replied("nocturne-laptop,1\n", "127.0.0.1"), outcome)
    }

    @Test
    fun `a name that resolves to a public address is never connected to`() {
        val outcome = client(pairing(address = null), resolve = { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }).exchange("CPH2591", body)
        assertTrue(assertIs<SyncClient.Outcome.Unreachable>(outcome).detail.contains("not a private address"))
    }

    @Test
    fun `a name that is not found is unreachable`() {
        val outcome = client(pairing(address = null), resolve = { throw java.net.UnknownHostException(it) }).exchange("CPH2591", body)
        assertTrue(assertIs<SyncClient.Outcome.Unreachable>(outcome).detail.contains("not found"))
    }

    @Test
    fun `a laptop that takes too long to answer is a failure, not unreachable`() {
        respond = { Thread.sleep(1_500); it.reply(200, "late") }
        assertIs<SyncClient.Outcome.Failed>(client(pairing(), readTimeoutMs = 300).exchange("CPH2591", body))
    }

    @Test
    fun `something that does not speak TLS on the port is not the laptop`() {
        val plain = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
            createContext("/") { it.sendResponseHeaders(200, -1); it.close() }
            start()
        }
        try {
            assertIs<SyncClient.Outcome.Unreachable>(client(pairing(port = plain.address.port)).exchange("CPH2591", body))
        } finally {
            plain.stop(0)
        }
    }
}
