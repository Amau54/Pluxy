package com.pluxy.tv.api

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ServerAvailabilityTest {
    private val salon = ServerInfo(name = "Salon", ip = "192.168.1.20")
    private val bureau = ServerInfo(name = "Bureau", ip = "192.168.1.21")

    @Test fun `dead UDP announcements and non Pluxy services are excluded`() = runBlocking {
        val live = ServerAvailability.available(listOf(salon, bureau)) { url ->
            if (url == salon.baseUrl) salon else null
        }
        assertEquals(listOf(salon), live)
        assertTrue(ServerAvailability.available(listOf(bureau)) { bureau.copy(app = "other") }.isEmpty())
    }

    @Test fun `duplicate announcements are checked once and options have a stable order`() = runBlocking {
        val calls = AtomicInteger()
        val live = ServerAvailability.available(listOf(salon, bureau, salon)) { url ->
            calls.incrementAndGet()
            if (url == salon.baseUrl) salon else bureau
        }
        assertEquals(2, calls.get())
        assertEquals(listOf(bureau, salon), live)
    }

    @Test fun `network checks are bounded and cancelled with their screen`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val job = launch {
            ServerAvailability.available((1..20).map { salon.copy(port = 8400 + it) }) {
                active.incrementAndGet()
                entered.complete(Unit)
                try { awaitCancellation() } finally { active.decrementAndGet() }
            }
        }
        entered.await()
        yield()
        assertTrue(active.get() in 1..4)
        job.cancelAndJoin()
        assertEquals(0, active.get())
    }

    @Test fun `a silent HTTP server cannot block automatic discovery`() = runBlocking {
        ServerSocket(0).use { listener ->
            val worker = thread(isDaemon = true) {
                runCatching { listener.accept().use { it.getInputStream().readBytes() } }
            }
            val unavailable = withTimeout(5_000) { ServerAvailability.probe("http://127.0.0.1:${listener.localPort}") }
            assertNull(unavailable)
            worker.join(500)
        }
    }
}
