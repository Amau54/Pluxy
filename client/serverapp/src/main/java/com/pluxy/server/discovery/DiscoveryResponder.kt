package com.pluxy.server.discovery

import android.content.Context
import com.pluxy.server.BuildConfig
import com.pluxy.server.LanAddressPolicy
import com.pluxy.server.NetworkUtils
import com.pluxy.server.ServerPreferences
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/** Répond au même broadcast PLUXY_DISCOVERY_V1 que le serveur Windows. */
class DiscoveryResponder(
    private val context: Context,
    private val preferences: ServerPreferences,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        val datagram = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1_000
            bind(InetSocketAddress("0.0.0.0", preferences.discoveryPort))
        }
        socket = datagram
        running = true
        thread = Thread({ loop(datagram) }, "pluxy-discovery").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop(datagram: DatagramSocket) {
        val buffer = ByteArray(2_048)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                datagram.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                if (!running) break else continue
            } catch (_: Exception) {
                continue
            }
            val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            if (!request.startsWith(MAGIC)) continue
            if (!LanAddressPolicy.isTrusted(packet.address)) continue
            val ip = NetworkUtils.primaryLanIp(context) ?: continue
            val response = JSONObject().apply {
                put("app", "pluxy")
                put("name", preferences.serverName)
                put("ip", ip)
                put("port", preferences.httpPort)
                put("version", BuildConfig.VERSION_NAME)
                put("platform", "android")
            }.toString().toByteArray(Charsets.UTF_8)
            runCatching {
                datagram.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }
        }
    }

    companion object {
        private const val MAGIC = "PLUXY_DISCOVERY_V1"
    }
}
