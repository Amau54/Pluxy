package com.pluxy.server.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal class HttpFailure(
    val status: Int,
    override val message: String,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

/** Bounded HTTP framing, independent of Android so malformed LAN requests can be tested. */
internal object HttpRequestParser {
    private val token = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val decimal = Regex("^[0-9]+$")
    private const val MAX_LINE_BYTES = 8_192
    private const val MAX_HEADER_BYTES = 65_536
    private const val MAX_BODY_BYTES = 1_048_576L

    fun parse(input: InputStream): HttpRequest? {
        val requestLine = readLine(input, 414) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !token.matches(parts[0]) ||
            parts[2] !in setOf("HTTP/1.0", "HTTP/1.1") ||
            !parts[1].startsWith('/') || parts[1].contains('#') ||
            parts[1].any { it.code <= 32 || it.code == 127 }
        ) throw HttpFailure(400, "Requête invalide")

        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, 431) ?: throw HttpFailure(400, "En-têtes incomplets")
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw HttpFailure(431, "En-têtes trop volumineux")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            val name = line.substring(0, separator.coerceAtLeast(0))
            if (separator <= 0 || !token.matches(name)) throw HttpFailure(400, "En-tête invalide")
            val value = line.substring(separator + 1).trim(' ', '\t')
            if (value.any { (it.code < 32 && it != '\t') || it.code == 127 }) {
                throw HttpFailure(400, "Valeur d'en-tête invalide")
            }
            val key = name.lowercase(Locale.ROOT)
            val previous = headers[key]
            headers[key] = if (previous == null) value else "$previous,$value"
        }

        if (parts[2] == "HTTP/1.1" && headers["host"].isNullOrBlank()) {
            throw HttpFailure(400, "Host manquant")
        }
        if (headers["host"]?.contains(',') == true) throw HttpFailure(400, "Host ambigu")
        if (headers.containsKey("transfer-encoding")) {
            if (headers.containsKey("content-length")) throw HttpFailure(400, "Framing HTTP ambigu")
            throw HttpFailure(501, "Transfer-Encoding non pris en charge")
        }
        if (headers.containsKey("expect")) throw HttpFailure(417, "Expectation non prise en charge")

        val length = contentLength(headers["content-length"])
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw HttpFailure(400, "Corps incomplet")
            if (count == 0) {
                val value = input.read()
                if (value < 0) throw HttpFailure(400, "Corps incomplet")
                body[offset++] = value.toByte()
            } else offset += count
        }
        val target = parts[1]
        // '+' is literal in a path; only query values use form-style decoding.
        val path = decode(target.substringBefore('?').replace("+", "%2B"))
        val query = target.substringAfter('?', "").split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }.toMap()
        return HttpRequest(parts[0].uppercase(Locale.ROOT), path, query, headers, body)
    }

    private fun contentLength(raw: String?): Int {
        if (raw == null) return 0
        val values = raw.split(',').map { value ->
            val digits = value.trim()
            if (!decimal.matches(digits)) throw HttpFailure(400, "Content-Length invalide")
            digits.toLongOrNull() ?: throw HttpFailure(413, "Corps trop volumineux")
        }
        if (values.distinct().size != 1) throw HttpFailure(400, "Content-Length contradictoires")
        val length = values.first()
        if (length > MAX_BODY_BYTES) throw HttpFailure(413, "Corps trop volumineux")
        return length.toInt()
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw HttpFailure(400, "URL invalide")
    }

    private fun readLine(input: InputStream, overflowStatus: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw HttpFailure(400, "Ligne HTTP incomplète")
            }
            if (value == '\n'.code) return bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\r'.code) {
                if (input.read() != '\n'.code) throw HttpFailure(400, "Fin de ligne HTTP invalide")
                return bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (bytes.size() >= MAX_LINE_BYTES) throw HttpFailure(overflowStatus, "Ligne HTTP trop longue")
            bytes.write(value)
        }
    }
}
