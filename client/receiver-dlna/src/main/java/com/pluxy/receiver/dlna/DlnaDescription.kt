package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.ReceiverCapabilities
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.ReceiverProtocol
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.ResponseBody

internal data class DlnaDescription(
    val id: String,
    val name: String,
    val model: String?,
    val avTransportControlUrl: String,
    val avTransportServiceType: String,
    val connectionManagerControlUrl: String?,
    val connectionManagerServiceType: String?,
)

private data class DlnaService(val type: String, val controlUrl: String)

internal object SafeXml {
    fun parse(bytes: ByteArray): Document {
        require(bytes.size <= MAX_XML_BYTES) { "Description UPnP trop volumineuse" }
        require(!containsForbiddenDeclaration(bytes)) { "DTD ou entité XML interdite" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            // Tous les parseurs Android ne publient pas les mêmes URI de fonctions.
            // Le filtre de déclaration ci-dessus reste donc le garde-fou fail-closed.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    /** Détecte les déclarations XML ASCII même en UTF-16/32 (octets NUL filtrés). */
    private fun containsForbiddenDeclaration(bytes: ByteArray): Boolean {
        val normalized = buildString(bytes.size) {
            bytes.forEach { raw ->
                val value = raw.toInt() and 0xff
                if (value != 0) append(value.toChar().uppercaseChar())
            }
        }
        return "<!DOCTYPE" in normalized || "<!ENTITY" in normalized
    }

    private const val MAX_XML_BYTES = 512 * 1024
    // URI standard JAXP utilisées en littéral : les stubs Android n'exposent pas
    // toujours les constantes XMLConstants correspondantes à la compilation.
    private const val ACCESS_EXTERNAL_DTD =
        "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"
}

internal fun ResponseBody.readBounded(maxBytes: Int, rejectOverflow: Boolean): ByteArray? {
    byteStream().use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                return if (rejectOverflow) null else output.toByteArray()
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal object DlnaDescriptionParser {
    fun parse(bytes: ByteArray, location: String): DlnaDescription? {
        val document = SafeXml.parse(bytes)
        val declaredBase = document.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim()
            ?.takeIf { it.isNotBlank() }
        val base = declaredBase ?: location
        if (!LocalEndpointPolicy.isTrusted(base) || !sameHost(location, base)) return null
        val devices = document.getElementsByTagNameNS("*", "device")
        for (index in 0 until devices.length) {
            val device = devices.item(index) as? Element ?: continue
            if (!device.childText("deviceType").orEmpty().contains("MediaRenderer", ignoreCase = true)) continue
            val av = device.service("AVTransport") ?: return null
            val connection = device.service("ConnectionManager")
            val id = device.childText("UDN")?.trim()?.takeIf { it.isNotBlank() }
                ?.take(MAX_ID_CHARS) ?: "dlna:${URI(location).host}:${URI(location).port}"
            val name = device.childText("friendlyName")?.trim()?.takeIf { it.isNotBlank() }
                ?.take(MAX_NAME_CHARS) ?: "Téléviseur DLNA"
            return DlnaDescription(
                id = id,
                name = name,
                model = listOfNotNull(device.childText("manufacturer"), device.childText("modelName"))
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                    .take(MAX_MODEL_CHARS)
                    .takeIf { it.isNotBlank() },
                avTransportControlUrl = resolve(base, av.controlUrl),
                avTransportServiceType = av.type,
                connectionManagerControlUrl = connection?.let { resolve(base, it.controlUrl) },
                connectionManagerServiceType = connection?.type,
            ).takeIf {
                LocalEndpointPolicy.isTrusted(it.avTransportControlUrl) &&
                    sameHost(location, it.avTransportControlUrl) &&
                    (it.connectionManagerControlUrl == null ||
                        (LocalEndpointPolicy.isTrusted(it.connectionManagerControlUrl) &&
                            sameHost(location, it.connectionManagerControlUrl)))
            }
        }
        return null
    }

    private fun Element.service(type: String): DlnaService? {
        val services = getElementsByTagNameNS("*", "service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? Element ?: continue
            val serviceType = service.childText("serviceType")?.trim().orEmpty()
            val expected = Regex(
                "^urn:schemas-upnp-org:service:${Regex.escape(type)}:[0-9]{1,3}$",
                RegexOption.IGNORE_CASE,
            )
            if (expected.matches(serviceType)) {
                val control = service.childText("controlURL")?.trim()?.takeIf { it.isNotBlank() }
                    ?: continue
                if (control.length > MAX_URL_CHARS) continue
                return DlnaService(serviceType, control)
            }
        }
        return null
    }

    private fun Element.childText(localName: String): String? {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.localName == localName || child.nodeName.substringAfter(':') == localName) {
                return child.textContent
            }
        }
        return null
    }

    private fun resolve(base: String, endpoint: String): String = URI(base).resolve(endpoint).toString()

    private fun sameHost(left: String, right: String): Boolean =
        URI(left).host.equals(URI(right).host, ignoreCase = true)

    private const val MAX_ID_CHARS = 256
    private const val MAX_NAME_CHARS = 120
    private const val MAX_MODEL_CHARS = 160
    private const val MAX_URL_CHARS = 2_048
}

internal object DlnaProtocolInfo {
    private val fallback = ReceiverCapabilities(
        containers = setOf("mp4", "mp3", "jpeg", "jpg"),
        videoCodecs = setOf("h264"),
        audioCodecs = setOf("aac", "mp3"),
        imageMimeTypes = setOf("image/jpeg"),
        maxVideoHeight = 1080,
        supportsHdr = false,
        source = "DLNA prudent",
    )

    fun capabilities(sink: String?): ReceiverCapabilities {
        if (sink.isNullOrBlank()) return fallback
        val containers = linkedSetOf<String>()
        val video = linkedSetOf<String>()
        val audio = linkedSetOf<String>()
        val images = linkedSetOf<String>()
        var hdr = false
        sink.split(',').map(String::trim).filter(String::isNotBlank).forEach { entry ->
            val parts = entry.split(':', limit = 4)
            val mime = parts.getOrNull(2)?.lowercase(Locale.ROOT).orEmpty()
            val profile = parts.getOrNull(3)?.uppercase(Locale.ROOT).orEmpty()
            when {
                mime.startsWith("image/") -> {
                    images += mime
                    containers += mime.substringAfter('/')
                }
                mime.startsWith("audio/") -> {
                    val codec = when {
                        mime.contains("mpeg") -> "mp3"
                        mime.contains("aac") || mime.contains("mp4") -> "aac"
                        mime.contains("flac") -> "flac"
                        mime.contains("wav") -> "pcm"
                        else -> mime.substringAfter('/')
                    }
                    audio += codec
                    containers += when {
                        mime.contains("mpeg") -> "mp3"
                        mime.contains("mp4") -> "m4a"
                        else -> mime.substringAfter('/')
                    }
                }
                mime.startsWith("video/") -> {
                    containers += when {
                        mime.contains("matroska") -> "mkv"
                        mime.contains("mp4") -> "mp4"
                        mime.contains("mpeg") -> "mpeg"
                        mime.contains("webm") -> "webm"
                        else -> mime.substringAfter('/')
                    }
                    when {
                        "HEVC" in profile || "H265" in profile -> video += "hevc"
                        "AV1" in profile -> video += "av1"
                        "VP9" in profile -> video += "vp9"
                        "AVC" in profile || "H264" in profile -> video += "h264"
                        mime == "video/mp4" -> video += "h264"
                    }
                    when {
                        "EAC3" in profile -> audio += "eac3"
                        "AC3" in profile -> audio += "ac3"
                        "AAC" in profile || mime == "video/mp4" -> audio += "aac"
                    }
                    if ("HDR" in profile || "HLG" in profile || "PQ" in profile) hdr = true
                }
            }
        }
        if (containers.isEmpty() && video.isEmpty() && audio.isEmpty() && images.isEmpty()) return fallback
        return ReceiverCapabilities(
            containers = containers,
            videoCodecs = video,
            audioCodecs = audio,
            imageMimeTypes = images,
            maxVideoHeight = null,
            supportsHdr = hdr,
            source = "DLNA GetProtocolInfo",
        )
    }

    fun device(description: DlnaDescription, sink: String?): ReceiverDevice = ReceiverDevice(
        id = description.id,
        name = description.name,
        protocol = ReceiverProtocol.DLNA,
        description = description.model,
        capabilities = capabilities(sink),
        attributes = buildMap {
            put("av_transport", description.avTransportControlUrl)
            put("av_transport_type", description.avTransportServiceType)
            description.connectionManagerControlUrl?.let { put("connection_manager", it) }
            description.connectionManagerServiceType?.let { put("connection_manager_type", it) }
        },
    )
}
