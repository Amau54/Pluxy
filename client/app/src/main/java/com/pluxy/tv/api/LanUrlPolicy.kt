package com.pluxy.tv.api

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/** Validation des adresses dans le modele assume "LAN domestique de confiance". */
object LanUrlPolicy {
    fun isTrustedAddress(address: InetAddress?): Boolean {
        address ?: return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    /** Résolution DNS qui refuse un basculement vers Internet. */
    fun resolveTrustedHost(hostname: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(hostname).toList()
        if (addresses.isEmpty() || addresses.any { !isTrustedAddress(it) }) {
            throw UnknownHostException("Serveur hors du réseau local")
        }
        return addresses
    }

    /** Normalise une saisie manuelle et verifie que son DNS reste entierement local. */
    fun normalizeServer(rawValue: String, defaultPort: Int = 8420): String {
        val raw = rawValue.trim()
        require(raw.isNotEmpty()) { "Adresse vide" }
        val candidate = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(candidate) }.getOrElse { throw IllegalArgumentException("Adresse invalide") }
        require(uri.scheme.equals("http", ignoreCase = true)) { "Seul HTTP local est pris en charge" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Adresse invalide" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Saisissez uniquement l'adresse du serveur" }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Hote invalide (mettre une IPv6 entre crochets)")
        val port = if (uri.port == -1) defaultPort else uri.port
        require(port in 1..65535) { "Port invalide" }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        require(addresses.isNotEmpty() && addresses.all(::isTrustedAddress)) {
            "Le serveur doit appartenir au reseau local"
        }
        return URI("http", null, host, port, null, null, null).toString()
    }

    /** Le serveur peut renvoyer une URL absolue, mais jamais changer d'origine. */
    fun resolveSameServer(baseUrl: String, value: String): String {
        val base = URI(baseUrl.trimEnd('/') + "/")
        val resolved = base.resolve(value)
        val basePort = if (base.port == -1) 80 else base.port
        val resolvedPort = if (resolved.port == -1) 80 else resolved.port
        require(resolved.scheme.equals("http", true)) { "Schema de flux interdit" }
        require(resolved.userInfo == null) { "URL de flux invalide" }
        require(resolved.host.equals(base.host, true) && resolvedPort == basePort) {
            "Le serveur a propose un flux hors du LAN Pluxy"
        }
        return resolved.toString()
    }

    fun isSameServer(baseUrl: String, value: String): Boolean = runCatching {
        resolveSameServer(baseUrl, value)
        true
    }.getOrDefault(false)

    fun safeTmdbImage(value: String?): String? = value?.takeIf {
        runCatching {
            val uri = URI(it)
            uri.scheme.equals("https", true) && uri.host.equals("image.tmdb.org", true)
        }.getOrDefault(false)
    }

    fun safeYoutubeTrailer(value: String?): String? = value?.takeIf {
        runCatching {
            val uri = URI(it)
            uri.scheme.equals("https", true) && uri.host?.lowercase() in setOf(
                "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
            )
        }.getOrDefault(false)
    }
}
