package com.pluxy.receiver.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.ReceiverDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class DlnaDiscovery(context: Context) : ReceiverDiscovery {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDevices = MutableStateFlow<List<ReceiverDevice>>(emptyList())
    override val devices = mutableDevices.asStateFlow()
    private var discoveryJob: Job? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Synchronized
    override fun start() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            while (isActive) {
                runCatching { discoverOnce() }
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    override fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        http.dispatcher.cancelAll()
    }

    override fun close() {
        stop()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private suspend fun discoverOnce() {
        val lock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("Pluxy:DlnaDiscovery")
            .apply { setReferenceCounted(false) }
        val locations = linkedMapOf<String, SsdpResponse>()
        try {
            runCatching { lock.acquire() }
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                val query = M_SEARCH.toByteArray(StandardCharsets.US_ASCII)
                val target = InetAddress.getByName(SSDP_HOST)
                repeat(2) {
                    socket.send(DatagramPacket(query, query.size, target, SSDP_PORT))
                    Thread.yield()
                }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISCOVERY_WINDOW_MS)
                while (System.nanoTime() < deadline && locations.size < MAX_DEVICES) {
                    currentCoroutineContext().ensureActive()
                    val bytes = ByteArray(MAX_PACKET_BYTES)
                    val packet = DatagramPacket(bytes, bytes.size)
                    val response = runCatching {
                        socket.receive(packet)
                        String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1)
                    }.getOrNull() ?: continue
                    SsdpParser.parse(response, packet.address)?.let { locations[it.location] = it }
                }
            }
        } finally {
            if (lock.isHeld) runCatching { lock.release() }
        }

        val found = locations.values.mapNotNull { response ->
            currentCoroutineContext().ensureActive()
            runCatching { resolveDevice(response) }.getOrNull()
        }.distinctBy(ReceiverDevice::id).sortedBy { it.name.lowercase() }
        currentCoroutineContext().ensureActive()
        mutableDevices.value = found
    }

    private fun resolveDevice(response: SsdpResponse): ReceiverDevice? {
        val request = Request.Builder().url(response.location).get().build()
        val bytes = http.newCall(request).execute().use { responseBody ->
            if (!responseBody.isSuccessful) return null
            responseBody.body?.readBounded(MAX_DESCRIPTION_BYTES, rejectOverflow = true) ?: return null
        }
        val description = DlnaDescriptionParser.parse(bytes, response.location) ?: return null
        val sink = description.connectionManagerControlUrl?.let { endpoint ->
            fetchProtocolInfo(
                endpoint,
                description.connectionManagerServiceType ?: CONNECTION_MANAGER,
            )
        }
        return DlnaProtocolInfo.device(description, sink)
    }

    private fun fetchProtocolInfo(endpoint: String, serviceType: String): String? {
        if (!LocalEndpointPolicy.isTrusted(endpoint)) return null
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body><u:GetProtocolInfo xmlns:u="$serviceType" /></s:Body>
            </s:Envelope>
        """.trimIndent()
        val request = Request.Builder()
            .url(endpoint)
            .header("SOAPAction", "\"$serviceType#GetProtocolInfo\"")
            .post(envelope.toRequestBody(XML_MEDIA_TYPE))
            .build()
        val bytes = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.readBounded(MAX_DESCRIPTION_BYTES, rejectOverflow = true) ?: return null
        }
        val document = SafeXml.parse(bytes)
        return document.getElementsByTagNameNS("*", "Sink").item(0)?.textContent
    }

    companion object {
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_PACKET_BYTES = 8 * 1024
        private const val MAX_DESCRIPTION_BYTES = 512 * 1024
        private const val MAX_DEVICES = 32
        private const val RECEIVE_TIMEOUT_MS = 450
        private const val DISCOVERY_WINDOW_MS = 3_500L
        private const val DISCOVERY_INTERVAL_MS = 30_000L
        private const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
        private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
        private val M_SEARCH = listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: $SSDP_HOST:$SSDP_PORT",
            "MAN: \"ssdp:discover\"",
            "MX: 2",
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
            "",
            "",
        ).joinToString("\r\n")
    }
}
