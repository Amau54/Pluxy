package com.pluxy.server

import java.net.InetAddress

/** Frontiere reseau volontaire de Pluxy : LAN domestique ouvert, Internet refuse. */
object LanAddressPolicy {
    fun isTrusted(address: InetAddress?): Boolean {
        address ?: return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        val bytes = address.address
        // IPv6 Unique Local Address fc00::/7 (non couvert par isSiteLocalAddress).
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }
}
