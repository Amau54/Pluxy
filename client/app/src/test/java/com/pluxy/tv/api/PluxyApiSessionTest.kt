package com.pluxy.tv.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PluxyApiSessionTest {
    @Test fun failedProgressIsReportedAndOrderedPayloadReachesTheServer() = runBlocking {
        val status = AtomicInteger(503)
        val body = AtomicReference<String>()
        val session = AtomicReference<String>()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val payload = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < payload.size) {
                            val count = reader.read(payload, offset, payload.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        body.set(String(payload))
                        session.set(headers["x-pluxy-session"])
                        val response = "HTTP/1.1 ${status.get()} Test\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    }
                } catch (failure: Exception) { if (!server.isClosed) throw failure }
            }
        }.apply { isDaemon = true; start() }
        val api = PluxyApi("http://127.0.0.1:${server.localPort}", "test-session")
        try {
            val error = runCatching { api.setProgress("film", 60000, 300000, 2) }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(503, (error as ApiException).code)
            assertEquals("test-session", session.get())
            assertTrue(body.get().contains("\"session_id\":\"test-session\""))
            assertTrue(body.get().contains("\"sequence\":2"))
            status.set(200)
            api.setProgress("film", 60000, 300000, 2)
            status.set(503)
            val readError = runCatching { api.getProgress("film") }.exceptionOrNull()
            assertTrue(readError is ApiException)
            assertEquals(503, (readError as ApiException).code)
            status.set(404)
            assertEquals(PlaybackProgress(), api.getProgress("film"))
        } finally {
            api.close()
            server.close()
            worker.join(5000)
        }
    }

    @Test fun hlsPlaylistAndStopUseTheSameSessionWithoutChangingOtherUrls() {
        val api = PluxyApi("http://127.0.0.1:8420", "test-session")
        try {
            assertEquals("http://127.0.0.1:8420/stream/hls/film/index.m3u8?session=test-session",
                api.abs("/stream/hls/film/index.m3u8?session=old-session"))
            assertEquals("http://127.0.0.1:8420/stream/hls/film?session=test-session",
                api.abs("/stream/hls/film"))
            assertEquals("http://127.0.0.1:8420/stream/direct/film",
                api.abs("/stream/direct/film"))
        } finally { api.close() }
    }
}
