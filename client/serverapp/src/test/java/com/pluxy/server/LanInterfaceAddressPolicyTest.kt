package com.pluxy.server

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class LanInterfaceAddressPolicyTest {
    private fun iface(name: String, vararg addresses: String, up: Boolean = true) =
        LanInterfaceAddressPolicy.Interface(name, addresses.map(InetAddress::getByName), up)

    @Test fun activeVpnDoesNotBecomeTheAdvertisedLanAddress() {
        val interfaces = listOf(
            iface("tun0", "172.18.11.218"),
            iface("wlan0", "192.168.1.104", "fe80::1"),
            iface("rmnet_data0", "10.1.2.3"),
        )
        assertEquals(listOf("192.168.1.104"), LanInterfaceAddressPolicy.addresses(interfaces))
    }

    @Test fun ethernetRemainsAvailableWhenWifiIsDisconnected() {
        assertEquals(listOf("192.168.1.12"), LanInterfaceAddressPolicy.addresses(listOf(
            iface("wlan0", "192.168.1.104", up = false),
            iface("eth0", "192.168.1.12", "192.168.1.12"),
            iface("wg0", "10.1.1.2"),
            iface("lo", "127.0.0.1"),
        )))
    }
}
