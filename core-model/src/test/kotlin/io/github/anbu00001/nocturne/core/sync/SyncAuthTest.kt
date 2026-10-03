package io.github.anbu00001.nocturne.core.sync

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncAuthTest {

    /** The file tools/activitywatch/test_lansync.py checks against lansync.py: the two ends cannot drift apart unseen. */
    private val vector: Map<String, String> = checkNotNull(javaClass.getResource("/sync/vector.txt")) { "missing /sync/vector.txt" }
        .readText().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private val body = hexToBytes(vector.getValue("bodyHex"))

    @Test
    fun `the signature is the one the laptop computes`() {
        assertEquals(vector.getValue("bodySha256"), sha256Hex(body))
        assertEquals(
            vector.getValue("canonicalHex"),
            toHex(SyncAuth.canonical(vector.getValue("device"), vector.getValue("time").toLong(), vector.getValue("nonce"), body)),
        )
        assertEquals(
            vector.getValue("signature"),
            SyncAuth.sign(vector.getValue("secret"), vector.getValue("device"), vector.getValue("time").toLong(), vector.getValue("nonce"), body),
        )
    }

    @Test
    fun `the signature covers every part`() {
        val v = vector
        val base = SyncAuth.sign(v.getValue("secret"), "CPH2591", 1791000000000, v.getValue("nonce"), body)
        assertNotEquals(base, SyncAuth.sign(v.getValue("secret"), "other", 1791000000000, v.getValue("nonce"), body))
        assertNotEquals(base, SyncAuth.sign(v.getValue("secret"), "CPH2591", 1791000000001, v.getValue("nonce"), body))
        assertNotEquals(base, SyncAuth.sign(v.getValue("secret"), "CPH2591", 1791000000000, "f".repeat(32), body))
        assertNotEquals(base, SyncAuth.sign(v.getValue("secret"), "CPH2591", 1791000000000, v.getValue("nonce"), body + 10))
        assertNotEquals(base, SyncAuth.sign("11".repeat(32), "CPH2591", 1791000000000, v.getValue("nonce"), body))
    }

    @Test
    fun `nonces are 32 hex characters and not repeated`() {
        val nonces = List(50) { SyncAuth.newNonce() }
        assertTrue(nonces.all { Regex("[0-9a-f]{32}").matches(it) })
        assertEquals(50, nonces.toSet().size)
    }

    @Test
    fun `the body is what the laptop's parser reads`() {
        val text = body.toString(Charsets.UTF_8)
        val sessions = text.substringAfter("[sessions]\n").substringBefore("[nights]\n")
        val nights = text.substringAfter("[nights]\n")
        assertEquals(text, SyncBody.encode(1791000000000, sessions, nights))
        // A block's trailing blank lines do not multiply.
        assertEquals(text, SyncBody.encode(1791000000000, sessions + "\n\n", nights + "\n"))
    }

    @Test
    fun `only private networks are addressable`() {
        fun private(text: String) = PrivateAddress.isPrivate(InetAddress.getByName(text))
        for (ok in listOf("192.168.1.102", "10.0.0.5", "172.16.3.4", "172.31.255.255", "127.0.0.1", "169.254.1.1", "::1", "fd12:3456::1", "fe80::1", "::ffff:192.168.1.5")) {
            assertTrue(private(ok), ok)
        }
        for (bad in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "203.0.113.9", "100.64.0.1", "0.0.0.0", "2001:db8::1", "2606:4700::1111")) {
            assertFalse(private(bad), bad)
        }
        // Literals only: a name is never looked up by the check.
        assertTrue(PrivateAddress.isPrivateLiteral("192.168.1.105"))
        assertFalse(PrivateAddress.isPrivateLiteral("example.com"))
        assertFalse(PrivateAddress.isPrivateLiteral("192.168.1.999"))
        assertFalse(PrivateAddress.isPrivateLiteral("8.8.8.8"))
        assertFalse(PrivateAddress.isPrivateLiteral(""))
    }

    private val cert = "cd".repeat(32)
    private val secret = "ab".repeat(32)
    private fun file(vararg rows: String) = (listOf(PairingFormat.HEADER) + rows).joinToString("\n") + "\n"

    @Test
    fun `a pairing file reads into where to look and what to trust`() {
        // The text nocturne_aw.py's pairing_text writes (its test checks the same rows).
        val pairing = PairingFormat.parse(
            file("name,workbook", "host,workbook.local", "host,workbook", "address,192.168.1.105", "port,47650", "cert,$cert", "secret,$secret"),
        )
        assertEquals(Pairing("workbook", listOf("workbook.local", "workbook"), "192.168.1.105", 47650, cert, secret), pairing)
        assertEquals(null, PairingFormat.parse(file("name,w", "host,w", "port,1", "cert,$cert", "secret,$secret")).address)
        assertEquals("192.168.1.7", pairing.seenAt("192.168.1.7").address)
        assertEquals(pairing, pairing.seenAt("192.168.1.105"))
    }

    @Test
    fun `a pairing file breaking any rule is refused whole`() {
        val good = listOf("name,workbook", "host,workbook.local", "port,47650", "cert,$cert", "secret,$secret")
        fun parse(vararg rows: String) = PairingFormat.parse(file(*rows))
        parse(*good.toTypedArray())
        assertFailsWith<IllegalArgumentException> { PairingFormat.parse("nocturne-pair,2\n" + good.joinToString("\n")) }
        assertFailsWith<IllegalArgumentException> { parse(*(good + "address,8.8.8.8").toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*(good + "address,example.com").toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*(good + "colour,blue").toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*(good + "port,1").toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*(good - "name,workbook").toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*(good - "host,workbook.local").toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*good.map { if (it.startsWith("port")) "port,70000" else it }.toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*good.map { if (it.startsWith("cert")) "cert,abcd" else it }.toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*good.map { if (it.startsWith("secret")) "secret,${"z".repeat(64)}" else it }.toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*good.map { if (it.startsWith("host")) "host,bad name" else it }.toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { parse(*(good + List(4) { "host,h$it" }).toTypedArray()) }
    }
}

class SyncResultTest {
    @Test
    fun `each outcome of an exchange has a code Settings can word`() {
        fun code(outcome: SyncClient.Outcome) = SyncResult.classify(outcome).first
        assertEquals(SyncResult.OK, code(SyncClient.Outcome.Replied("nocturne-laptop,1\n", "192.168.1.105")))
        assertEquals(SyncResult.UNREACHABLE, code(SyncClient.Outcome.Unreachable("no connection")))
        assertEquals(SyncResult.WRONG_CERTIFICATE, code(SyncClient.Outcome.WrongCertificate("192.168.1.105")))
        assertEquals(SyncResult.FAILED, code(SyncClient.Outcome.Failed("reset")))
        assertEquals(SyncResult.REJECTED, code(SyncClient.Outcome.Refused(401, "")))
        assertEquals(SyncResult.CLOCK, code(SyncClient.Outcome.Refused(403, "clock: the phone's and the laptop's times differ")))
        assertEquals(SyncResult.FAILED, code(SyncClient.Outcome.Refused(403, "private networks only")))
        assertEquals(SyncResult.NO_ACTIVITYWATCH, code(SyncClient.Outcome.Refused(503, "the laptop could not answer: OSError")))
        assertEquals(SyncResult.FAILED, code(SyncClient.Outcome.Refused(429, "too many failed requests")))
        assertEquals("429 too many failed requests", SyncResult.classify(SyncClient.Outcome.Refused(429, "too many failed requests")).second)
    }
}
