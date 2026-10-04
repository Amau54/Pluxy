package com.pluxy.tv.ui

import com.pluxy.tv.api.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerReconnectPolicyTest {
    private val selected = ServerInfo(name = "Salon", ip = "192.168.1.20")
    private val other = ServerInfo(name = "Bureau", ip = "192.168.1.21")

    @Test fun `discovery order cannot select a different library`() {
        assertEquals(selected, ServerReconnectPolicy.select(listOf(other, selected), selected.baseUrl, selected.name))
    }

    @Test fun `exact address wins even when names are duplicated`() {
        assertEquals(selected, ServerReconnectPolicy.select(listOf(other.copy(name = "Salon"), selected), selected.baseUrl, "Salon"))
    }

    @Test fun `unique custom name can recover a changed address`() {
        val moved = selected.copy(ip = "192.168.1.30")
        assertEquals(moved, ServerReconnectPolicy.select(listOf(other, moved), selected.baseUrl, selected.name))
    }

    @Test fun `ambiguous custom name requires explicit selection`() {
        assertNull(ServerReconnectPolicy.select(listOf(selected.copy(ip = "192.168.1.30"), other.copy(name = "Salon")), selected.baseUrl, "Salon"))
    }

    @Test fun `default name never authorizes a switch to another server`() {
        assertNull(ServerReconnectPolicy.select(listOf(other.copy(name = "Pluxy")), selected.baseUrl, "Pluxy"))
    }

    @Test fun `absent selected server never falls back to first result`() {
        assertNull(ServerReconnectPolicy.select(listOf(other), selected.baseUrl, selected.name))
    }
}
