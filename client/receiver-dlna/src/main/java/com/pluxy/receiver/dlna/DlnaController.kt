package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.ReceiverController
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.RemoteMedia
import com.pluxy.receiver.core.RemotePlaybackPhase
import com.pluxy.receiver.core.RemotePlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import java.util.Locale
import java.util.concurrent.TimeUnit

class DlnaController internal constructor(
    private val device: ReceiverDevice,
    private val transport: (suspend (action: String, body: String) -> Document)? = null,
    private val pollIntervalMs: Long = 2_000L,
) : ReceiverController {
    constructor(device: ReceiverDevice) : this(device, null, 2_000L)
    private val endpoint = requireNotNull(device.attributes["av_transport"]) {
        "Point de contrôle AVTransport absent"
    }.also { require(LocalEndpointPolicy.isTrusted(it)) { "Point DLNA hors réseau privé" } }
    private val serviceType = device.attributes["av_transport_type"] ?: DEFAULT_AV_TRANSPORT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(RemotePlaybackStatus(device = device))
    override val status = mutableStatus.asStateFlow()
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private var pollJob: Job? = null
    private val operationMutex = Mutex()
    private val polling = DlnaPollPolicy()
    @Volatile private var closed = false

    override suspend fun load(media: RemoteMedia, playWhenReady: Boolean) = operationMutex.withLock {
        check(!closed) { "Session DLNA fermée" }
        require(LocalEndpointPolicy.isTrusted(media.url)) { "Le média n'est pas servi par le réseau privé" }
        pollJob?.cancel()
        pollJob = null
        polling.success()
        mutableStatus.value = DlnaPlaybackState.connecting(device, media)
        try {
            command(
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${xml(media.url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${xml(DlnaDidl.metadata(media))}</CurrentURIMetaData>",
            )
            update(phase = RemotePlaybackPhase.PAUSED)
            if (playWhenReady) playLocked() else startPolling()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            throw error
        }
    }

    override suspend fun play() = operationMutex.withLock {
        check(!closed) { "Session DLNA fermée" }
        playLocked()
    }

    private suspend fun playLocked() {
        try {
            check(!closed) { "Session DLNA fermée" }
            command("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            update(phase = RemotePlaybackPhase.PLAYING, error = null)
            startPolling()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            throw error
        }
    }

    override suspend fun pause() = operationMutex.withLock {
        check(!closed) { "Session DLNA fermée" }
        try {
            command("Pause", "<InstanceID>0</InstanceID>")
            update(phase = RemotePlaybackPhase.PAUSED, error = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            throw error
        }
    }

    override suspend fun seekTo(positionMs: Long) = operationMutex.withLock {
        check(!closed) { "Session DLNA fermée" }
        require(device.capabilities.supportsSeek) { "Ce récepteur n'annonce pas le seek" }
        try {
            command(
                "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${DlnaTime.format(positionMs)}</Target>",
            )
            update(positionMs = positionMs.coerceAtLeast(0), error = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            throw error
        }
    }

    override suspend fun stop() = operationMutex.withLock {
        if (closed) return@withLock
        pollJob?.cancel()
        pollJob = null
        try {
            command("Stop", "<InstanceID>0</InstanceID>")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Local cleanup must still complete when a receiver has left the LAN.
        }
        update(phase = RemotePlaybackPhase.ENDED, positionMs = 0, error = null)
    }

    override fun close() {
        closed = true
        pollJob?.cancel()
        scope.cancel()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private suspend fun command(action: String, body: String): Document = withContext(Dispatchers.IO) {
        check(!closed) { "Session DLNA fermée" }
        transport?.let {
            val response = it(action, body)
            check(!closed) { "Session DLNA fermée" }
            return@withContext response
        }
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body><u:$action xmlns:u="$serviceType">$body</u:$action></s:Body>
            </s:Envelope>
        """.trimIndent()
        val request = Request.Builder()
            .url(endpoint)
            .header("SOAPAction", "\"$serviceType#$action\"")
            .post(envelope.toRequestBody(XML_MEDIA_TYPE))
            .build()
        val call = http.newCall(request)
        if (closed) { call.cancel(); error("Session DLNA fermée") }
        call.execute().use { response ->
            check(!closed) { "Session DLNA fermée" }
            val bytes = response.body?.readBounded(MAX_RESPONSE_BYTES, rejectOverflow = false)
                ?: ByteArray(0)
            if (!response.isSuccessful) {
                val detail = runCatching { SafeXml.parse(bytes).textContent.trim() }.getOrNull()
                throw IllegalStateException(detail?.take(180) ?: "Commande DLNA $action refusée (${response.code})")
            }
            SafeXml.parse(bytes.takeIf { it.isNotEmpty() } ?: EMPTY_ENVELOPE)
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                operationMutex.withLock {
                    if (closed) return@launch
                    val phase = try {
                        val transport = command("GetTransportInfo", "<InstanceID>0</InstanceID>")
                        polling.phase(transport.value("CurrentTransportState") ?: error("État DLNA absent"))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (polling.failed()) {
                            fail(IllegalStateException("La télévision ne répond plus. Reconnectez-la.", error))
                            return@launch
                        }
                        update(phase = RemotePlaybackPhase.BUFFERING, error = "Reconnexion à la télévision…")
                        return@withLock
                    }
                    polling.success()
                    if (phase == RemotePlaybackPhase.ENDED) {
                        update(phase = phase, error = null)
                        pollJob = null
                        return@launch
                    }
                    val document = try { command("GetPositionInfo", "<InstanceID>0</InstanceID>") }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null } // Some photo renderers omit position reporting.
                    update(phase = phase,
                        positionMs = document?.value("RelTime")?.let(DlnaTime::parse) ?: mutableStatus.value.positionMs,
                        durationMs = document?.value("TrackDuration")?.let(DlnaTime::parse) ?: mutableStatus.value.durationMs,
                        error = null)
                }
            }
        }
    }

    private fun Document.value(name: String): String? =
        getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun update(
        media: RemoteMedia? = mutableStatus.value.media,
        phase: RemotePlaybackPhase = mutableStatus.value.phase,
        positionMs: Long = mutableStatus.value.positionMs,
        durationMs: Long = mutableStatus.value.durationMs,
        error: String? = mutableStatus.value.error,
    ) {
        mutableStatus.value = RemotePlaybackStatus(
            device = device,
            media = media,
            phase = phase,
            positionMs = positionMs,
            durationMs = durationMs.takeIf { it > 0 } ?: media?.durationMs.orZero(),
            error = error,
        )
    }

    private fun fail(error: Exception) {
        pollJob?.cancel()
        pollJob = null
        update(
            phase = RemotePlaybackPhase.ERROR,
            error = error.message?.take(220) ?: "Erreur DLNA",
        )
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun Long?.orZero() = this ?: 0L

    companion object {
        private const val DEFAULT_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
        private val EMPTY_ENVELOPE = "<Envelope/>".toByteArray()
    }
}

internal object DlnaDidl {
    fun metadata(media: RemoteMedia): String {
        val upnpClass = when (media.kind.lowercase(Locale.ROOT)) {
            "photo" -> "object.item.imageItem.photo"
            "audio" -> "object.item.audioItem.musicTrack"
            else -> "object.item.videoItem.movie"
        }
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="${xml(media.id)}" parentID="0" restricted="1">
                <dc:title>${xml(media.title)}</dc:title><upnp:class>$upnpClass</upnp:class>
                <res protocolInfo="http-get:*:${xml(media.mimeType)}:*">${xml(media.url)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

internal object DlnaTime {
    fun format(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        return "%02d:%02d:%02d".format(
            Locale.US,
            seconds / 3_600,
            (seconds % 3_600) / 60,
            seconds % 60,
        )
    }

    fun parse(value: String): Long? {
        if (value == "NOT_IMPLEMENTED") return null
        val pieces = value.substringBefore('.').split(':')
        if (pieces.size != 3) return null
        val hours = pieces[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val minutes = pieces[1].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        val seconds = pieces[2].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        val tailSeconds = minutes * 60 + seconds
        if (hours > (Long.MAX_VALUE / 1_000 - tailSeconds) / 3_600) return null
        return (hours * 3_600 + tailSeconds) * 1_000
    }
}
