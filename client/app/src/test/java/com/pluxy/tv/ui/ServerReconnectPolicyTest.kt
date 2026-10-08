package com.pluxy.tv.ui

import com.pluxy.tv.api.ServerInfo
import com.pluxy.tv.api.ApiException
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ServerReconnectPolicyTest {
    private val selected = ServerInfo(name = "Salon", ip = "192.168.1.20")
    private val other = ServerInfo(name = "Bureau", ip = "192.168.1.21")

    @Test fun `one available server connects even when its identity changed`() {
        assertEquals(other, ServerReconnectPolicy.select(listOf(other)))
    }

    @Test fun `multiple servers always require a choice in either discovery order`() {
        assertNull(ServerReconnectPolicy.select(listOf(selected, other)))
        assertNull(ServerReconnectPolicy.select(listOf(other, selected)))
    }

    @Test fun `duplicate announcements do not create a false choice`() {
        assertEquals(selected, ServerReconnectPolicy.select(listOf(selected, selected)))
    }

    @Test fun `identical names on different addresses still require a choice`() {
        assertNull(ServerReconnectPolicy.select(listOf(selected, other.copy(name = "Salon"))))
    }

    @Test fun `default name is allowed when only one server is available`() {
        assertEquals(other.copy(name = "Pluxy"), ServerReconnectPolicy.select(listOf(other.copy(name = "Pluxy"))))
    }

    @Test fun `no available server keeps discovery running`() {
        assertNull(ServerReconnectPolicy.select(emptyList()))
    }

    @Test fun `network failures trigger recovery but HTTP and catalogue errors do not`() {
        assertTrue(ServerReconnectPolicy.isConnectionFailure(ConnectException()))
        assertTrue(ServerReconnectPolicy.isConnectionFailure(SocketTimeoutException()))
        assertFalse(ServerReconnectPolicy.isConnectionFailure(ApiException(503, "Busy")))
        assertFalse(ServerReconnectPolicy.isConnectionFailure(ApiException(409, "Changed")))
        assertFalse(ServerReconnectPolicy.isConnectionFailure(IllegalStateException("Invalid catalogue")))
    }
}
