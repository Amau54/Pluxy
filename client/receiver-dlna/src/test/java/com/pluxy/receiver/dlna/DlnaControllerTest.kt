package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections

class DlnaControllerTest {
    private val device = ReceiverDevice("tv-one", "TV", ReceiverProtocol.DLNA, capabilities = ReceiverCapabilities(),
        attributes = mapOf("av_transport" to "http://192.168.1.20/control"))
    private val media = RemoteMedia("one", "Photo", "http://192.168.1.2/one", "image/jpeg", "photo", "jpeg")
    private fun document(state: String? = null) = SafeXml.parse(
        "<Envelope>${state?.let { "<CurrentTransportState>$it</CurrentTransportState>" }.orEmpty()}</Envelope>".toByteArray())

    @Test fun `closing during SetURI prevents its continuation from sending Play`() = runBlocking {
        supervisorScope {
            val dispatched = CompletableDeferred<Unit>()
            val finishResponse = CompletableDeferred<Unit>()
            val actions = Collections.synchronizedList(mutableListOf<String>())
            val controller = DlnaController(device, transport = { action, _ ->
                actions += action
                if (action == "SetAVTransportURI") { dispatched.complete(Unit); finishResponse.await() }
                document()
            })
            try {
                val loading = async { controller.load(media, true) }
                withTimeout(2000) { dispatched.await() }
                controller.close()
                finishResponse.complete(Unit)
                try { withTimeout(2000) { loading.await() }; fail("closed load must fail") }
                catch (_: IllegalStateException) { }
                assertEquals(listOf("SetAVTransportURI"), actions.toList())
            } finally { controller.close() }
        }
    }

    @Test fun `receiver STOPPED ends photo playback without requiring position support`() = runBlocking {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val controller = DlnaController(device, transport = { action, _ -> actions += action; document("STOPPED") }, pollIntervalMs = 1)
        try {
            controller.load(media, false)
            val ended = withTimeout(2000) { controller.status.first { it.phase == RemotePlaybackPhase.ENDED } }
            assertFalse(ended.active)
            assertEquals(listOf("SetAVTransportURI", "GetTransportInfo"), actions.toList())
        } finally { controller.close() }
    }

    @Test fun `unreachable receiver transitions to terminal error after bounded polling attempts`() = runBlocking {
        var attempts = 0
        val controller = DlnaController(device, transport = { action, _ ->
            if (action == "GetTransportInfo") { attempts++; throw IOException("TV offline") }
            document()
        }, pollIntervalMs = 1)
        try {
            controller.load(media, false)
            val failed = withTimeout(2000) { controller.status.first { it.phase == RemotePlaybackPhase.ERROR } }
            assertEquals(3, attempts)
            assertFalse(failed.active)
            assertTrue(failed.error.orEmpty().contains("ne répond plus"))
        } finally { controller.close() }
    }
}
