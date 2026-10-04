package com.pluxy.tv.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NetworkCallsTest {
    @Test
    fun cancellationCancelsSocketAndClosesLateResponse() = runBlocking {
        val call = TestCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            call.awaitResult { it.body!!.string() }
        }
        result.cancelAndJoin()
        assertTrue(call.isCanceled())

        val body = TrackingBody()
        call.callback!!.onResponse(call, response(body))
        assertTrue(body.closed)
    }

    @Test
    fun successfulParsingClosesResponseBody() = runBlocking {
        val call = TestCall()
        val body = TrackingBody()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            call.awaitResult { it.body!!.string() }
        }
        call.callback!!.onResponse(call, response(body))
        assertEquals("payload", result.await())
        assertTrue(body.closed)
    }

    @Test
    fun parsingFailureClosesResponseAndPreservesException() = runBlocking {
        val call = TestCall()
        val body = TrackingBody()
        val expected = IOException("Malformed payload")
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                call.awaitResult<String> { throw expected }
                null
            } catch (error: IOException) {
                error
            }
        }
        call.callback!!.onResponse(call, response(body))
        val actual = result.await() ?: throw AssertionError("Parsing must fail")
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        // Gradle enables JVM assertions: coroutine stacktrace recovery then copies
        // IOException and keeps the original as its cause. Verify the real origin.
        assertSame(expected, generateSequence<Throwable>(actual) { it.cause }.last())
        assertTrue(body.closed)
    }

    @Test
    fun cancellationInterruptsReadingAnIncompleteHttpBody() = runBlocking {
        val server = ServerSocket(0)
        val releaseServer = CountDownLatch(1)
        val readingStarted = CountDownLatch(1)
        val worker = thread(isDaemon = true, name = "pluxy-test-http") {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\npartial".toByteArray())
                    flush()
                }
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        val http = OkHttpClient()
        try {
            val call = http.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                call.awaitResult {
                    readingStarted.countDown()
                    it.body!!.string()
                }
            }
            assertTrue(readingStarted.await(3, TimeUnit.SECONDS))
            result.cancelAndJoin()
            assertTrue(call.isCanceled())
            withTimeout(3_000) {
                while (http.dispatcher.runningCallsCount() != 0) delay(10)
            }
        } finally {
            releaseServer.countDown()
            server.close()
            worker.join(1_000)
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun optionalNetworkFailureDoesNotSwallowCancellation() {
        assertNull(networkOrNull<String> { throw IOException("Offline") })
        try {
            networkOrNull<String> { throw CancellationException("Screen closed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: a cancelled screen must not update its UI with fallback data.
        }
    }

    private fun response(body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("http://127.0.0.1/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    private class TrackingBody : ResponseBody() {
        var closed = false
        private val buffered = object : ForwardingSource(Buffer().writeUtf8("payload")) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType() = null
        override fun contentLength() = 7L
        override fun source(): BufferedSource = buffered
    }

    private class TestCall : Call {
        var callback: Callback? = null
        private var cancelled = false

        override fun request() = Request.Builder().url("http://127.0.0.1/").build()
        override fun execute(): Response = error("Only async calls are expected")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = TestCall()
    }
}
