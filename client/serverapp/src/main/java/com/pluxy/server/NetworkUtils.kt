package com.pluxy.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun isWifiConnected(context: Context): Boolean {
        return lanNetworks(context).isNotEmpty() || allLanIps().isNotEmpty()
    }

    @Suppress("DEPRECATION")
    private fun lanNetworks(context: Context): List<android.net.Network> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.filter { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@filter false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }.sortedBy { if (it == cm.activeNetwork) 0 else 1 }
    }

    fun primaryLanIp(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in lanNetworks(context)) {
            val fromLan = cm.getLinkProperties(network)?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
            if (!fromLan.isNullOrBlank()) return fromLan
        }
        return allLanIps().firstOrNull()
    }

    fun allLanIps(): List<String> = runCatching {
        LanInterfaceAddressPolicy.addresses(NetworkInterface.getNetworkInterfaces().toList().map {
            LanInterfaceAddressPolicy.Interface(it.name, it.inetAddresses.toList(), it.isUp, it.isLoopback)
        })
    }.getOrDefault(emptyList())
}
