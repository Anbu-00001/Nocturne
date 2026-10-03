package io.github.anbu00001.nocturne.core.sync

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The only addresses Nocturne's network code will connect to: a home or office network, never the internet. Spelled out
 * (10/8, 172.16/12, 192.168/16, link-local and loopback; IPv6 loopback, unique-local fc00::/7 and link-local fe80::/10)
 * so that neither a poisoned name nor a pairing file can point it at a public host. lansync.py's PRIVATE_NETWORKS is the
 * laptop's copy.
 */
object PrivateAddress {
    fun isPrivate(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) || b[0] == 127
            is Inet6Address -> address.isLoopbackAddress || (b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0x80)
            else -> false
        }
    }

    /** A literal address (never a name, so no lookup happens); false for anything else. */
    fun isPrivateLiteral(text: String): Boolean {
        val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(text) && text.split('.').all { it.toInt() <= 255 }
        val v6 = ':' in text && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!v4 && !v6) return false
        return runCatching { isPrivate(InetAddress.getByName(text)) }.getOrDefault(false)
    }
}
