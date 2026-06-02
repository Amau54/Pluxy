package com.pluxy.tv.api

import android.content.Context
import android.net.wifi.WifiManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Découverte automatique du serveur Pluxy sur le LAN.
 *
 * Envoie un broadcast UDP "PLUXY_DISCOVERY_V1" et collecte les réponses des
 * serveurs pendant une courte fenêtre. Le client n'a donc pas besoin de
 * connaître l'IP à l'avance ; en cas de serveurs multiples, l'UI propose un choix.
 */
object Discovery {

    private const val MAGIC = "PLUXY_DISCOVERY_V1"
    private const val DEFAULT_PORT = 8421
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    suspend fun discover(
        ctx: Context,
        udpPort: Int = DEFAULT_PORT,
        timeoutMs: Int = 1800,
    ): List<ServerInfo> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, ServerInfo>()
        // Verrou multicast (défensif) pour fiabiliser la réception sur certains TV.
        val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("pluxy-discovery")?.apply { setReferenceCounted(false); acquire() }

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 250
            }
            val payload = MAGIC.toByteArray()
            // Broadcast global + broadcast de sous-réseau calculé.
            val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
            subnetBroadcast(ctx)?.let { targets.add(it) }
            targets.forEach { addr ->
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, addr, udpPort))
                }
            }

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue   // timeout de lecture -> on reboucle jusqu'au deadline
                }
                val json = String(packet.data, 0, packet.length)
                runCatching {
                    moshi.adapter(ServerInfo::class.java).fromJson(json)
                }.getOrNull()?.let { info ->
                    // Utilise l'IP réellement vue sur le réseau si l'auto-report diffère.
                    val real = info.copy(ip = packet.address.hostAddress ?: info.ip)
                    if (real.app == "pluxy") found[real.baseUrl] = real
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
            lock?.let { runCatching { it.release() } }
        }
        found.values.toList()
    }

    private fun subnetBroadcast(ctx: Context): InetAddress? = runCatching {
        @Suppress("DEPRECATION")
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo ?: return null
        val bc = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val bytes = ByteArray(4) { i -> (bc shr (8 * i) and 0xFF).toByte() }
        InetAddress.getByAddress(bytes)
    }.getOrNull()
}
