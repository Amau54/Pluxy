package com.pluxy.receiver.dlna

import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal data class SsdpResponse(
    val location: String,
    val usn: String?,
    val server: String?,
)

internal object SsdpParser {
    fun parse(payload: String, sourceAddress: InetAddress? = null): SsdpResponse? {
        val lines = payload.replace("\r\n", "\n").split('\n')
        if (!STATUS_OK.matches(lines.firstOrNull().orEmpty().trim())) return null
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (key.isNotBlank() && value.isNotBlank() && key !in headers) {
                headers[key] = value
            }
        }
        val location = headers["location"]?.takeIf { LocalEndpointPolicy.isTrusted(it) } ?: return null
        if (sourceAddress != null) {
            val advertised = runCatching { InetAddress.getByName(URI(location).host) }.getOrNull() ?: return null
            if (advertised != sourceAddress) return null
        }
        return SsdpResponse(location, headers["usn"], headers["server"])
    }

    private val STATUS_OK = Regex("^HTTP/1\\.[01]\\s+200(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
}

/** Empêche SSDP/UPnP de transformer le téléphone en client SSRF vers Internet. */
internal object LocalEndpointPolicy {
    fun isTrusted(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port !in 1..65_535) return false
        if (uri.rawFragment != null) return false
        // Un endpoint SSDP légitime annonce normalement une IP. Refuser les noms
        // supprime le risque de DNS rebinding entre la validation et l'appel HTTP.
        if (!isIpLiteral(host)) return false
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all {
            !it.isAnyLocalAddress && !it.isLoopbackAddress &&
                (it.isSiteLocalAddress || it.isLinkLocalAddress || it.isIpv6UniqueLocal())
        }
    }.getOrDefault(false)

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val pieces = host.split('.')
        return pieces.size == 4 && pieces.all { piece ->
            piece.isNotEmpty() && piece.all(Char::isDigit) && piece.toIntOrNull() in 0..255
        }
    }

    private fun InetAddress.isIpv6UniqueLocal(): Boolean =
        address.size == 16 && (address[0].toInt() and 0xfe) == 0xfc

}
