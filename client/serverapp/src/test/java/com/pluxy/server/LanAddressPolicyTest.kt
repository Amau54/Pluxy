package com.pluxy.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanAddressPolicyTest {
    @Test
    fun acceptsHomeNetworks() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.8.9", "192.168.1.50", "169.254.2.3", "fd12::42")
            .forEach { assertTrue(it, LanAddressPolicy.isTrusted(InetAddress.getByName(it))) }
    }

    @Test
    fun rejectsInternetAndUnspecifiedAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "0.0.0.0")
            .forEach { assertFalse(it, LanAddressPolicy.isTrusted(InetAddress.getByName(it))) }
    }
}
