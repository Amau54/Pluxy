package com.pluxy.server

import java.net.Inet4Address
import java.net.InetAddress

object LanInterfaceAddressPolicy {
    data class Interface(val name: String, val addresses: List<InetAddress>, val up: Boolean = true, val loopback: Boolean = false)

    fun addresses(interfaces: List<Interface>): List<String> = interfaces.asSequence()
        .filter { it.up && !it.loopback }
        .filter { !it.name.lowercase().matches(Regex("^(tun|tap|ppp|wg|vpn|ipsec|xfrm|tailscale|rmnet|ccmni|v4-rmnet).*")) }
        .sortedBy { if (it.name.lowercase().startsWith("wlan") || it.name.lowercase().startsWith("eth")) 0 else 1 }
        .flatMap { it.addresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .toList()
}
