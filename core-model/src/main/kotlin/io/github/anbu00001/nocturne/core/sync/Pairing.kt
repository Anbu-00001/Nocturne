package io.github.anbu00001.nocturne.core.sync

/**
 * Where to find the laptop and what to trust there, given once over adb (`nocturne_aw.py pair`). The phone connects to
 * [address] first (where it last saw the laptop) and then to what each of [hosts] resolves to, and accepts only a
 * certificate whose SHA-256 is [certSha256], so a wrong machine at any of those names gets nothing. [secretHex] signs
 * each request ([SyncAuth]).
 */
data class Pairing(
    val name: String,
    val hosts: List<String>,
    val address: String?,
    val port: Int,
    val certSha256: String,
    val secretHex: String,
) {
    init {
        require(NAME.matches(name)) { "unusable name" }
        require(hosts.isNotEmpty() && hosts.size <= MAX_HOSTS && hosts.all(NAME::matches)) { "needs one to $MAX_HOSTS usable host names" }
        require(address == null || PrivateAddress.isPrivateLiteral(address)) { "address is not a private network address" }
        require(port in 1..65535) { "port out of range" }
        require(HEX64.matches(certSha256)) { "certificate fingerprint is not 64 hex characters" }
        require(HEX64.matches(secretHex)) { "secret is not 64 hex characters" }
    }

    fun seenAt(address: String): Pairing = if (address == this.address) this else copy(address = address)

    companion object {
        val NAME = Regex("[A-Za-z0-9._-]{1,64}")
        val HEX64 = Regex("[0-9a-f]{64}")
        const val MAX_HOSTS = 4
    }
}

/** The text `content write .../pair` carries; a file breaking any rule is refused whole, naming the line. */
object PairingFormat {
    const val HEADER = "nocturne-pair,1"

    fun parse(text: String): Pairing {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == HEADER) { "not a $HEADER file" }
        var name: String? = null
        val hosts = ArrayList<String>()
        var address: String? = null
        var port: Int? = null
        var cert: String? = null
        var secret: String? = null
        fun once(current: Any?, key: String, line: Int) = require(current == null) { "line $line: $key twice" }
        lines.drop(1).forEachIndexed { index, line ->
            val at = index + 2
            val key = line.substringBefore(',')
            val value = line.substringAfter(',', "")
            when (key) {
                "name" -> { once(name, key, at); name = value }
                "host" -> hosts += value
                "address" -> { once(address, key, at); address = value }
                "port" -> { once(port, key, at); port = requireNotNull(value.toIntOrNull()) { "line $at: unreadable port" } }
                "cert" -> { once(cert, key, at); cert = value }
                "secret" -> { once(secret, key, at); secret = value }
                else -> throw IllegalArgumentException("line $at: unknown row $key")
            }
        }
        return Pairing(
            name = requireNotNull(name) { "no name row" },
            hosts = hosts,
            address = address,
            port = requireNotNull(port) { "no port row" },
            certSha256 = requireNotNull(cert) { "no cert row" }.lowercase(),
            secretHex = requireNotNull(secret) { "no secret row" }.lowercase(),
        )
    }
}

/** The phone's side of the body `lansync.parse_body` reads: its derived sessions and nights, as the adb bridge exports them. */
object SyncBody {
    const val HEADER = "nocturne-phone,1"

    fun encode(sentAt: Long, sessionsCsv: String, nightsCsv: String): String = buildString {
        append(HEADER).append('\n')
        append("sent,").append(sentAt).append('\n')
        append("[sessions]\n").append(sessionsCsv.trimEnd('\n')).append('\n')
        append("[nights]\n").append(nightsCsv.trimEnd('\n')).append('\n')
    }
}
