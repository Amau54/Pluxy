package com.pluxy.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerCommandQueueTest {
    @Test fun commandsWaitForRestoredIndexAndKeepArrivalOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readingIndex = CompletableDeferred<Unit>()
        val indexReady = CompletableDeferred<Unit>()
        val handled = Channel<String>(Channel.UNLIMITED)
        val queue = ServerCommandQueue(
            scope,
            initialize = { readingIndex.complete(Unit); indexReady.await() },
            handle = { command: String -> handled.send(command) },
            onFailure = { throw AssertionError(it) },
        )
        try {
            assertTrue(queue.submit("START"))
            assertTrue(queue.submit("SCAN"))
            withTimeout(2_000) { readingIndex.await() }
            assertTrue(handled.tryReceive().isFailure)
            indexReady.complete(Unit)
            withTimeout(2_000) {
                assertEquals("START", handled.receive())
                assertEquals("SCAN", handled.receive())
            }
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun stopWhileDiskReadIsBlockedCannotPublishOrStartLater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val workerFinished = CompletableDeferred<Unit>()
        val published = AtomicBoolean(false)
        val handled = AtomicBoolean(false)
        val queue = ServerCommandQueue(
            scope,
            initialize = {
                try {
                    withContext(Dispatchers.IO) {
                        readEntered.countDown()
                        check(releaseRead.await(2, TimeUnit.SECONDS))
                    }
                    published.set(true)
                } finally {
                    workerFinished.complete(Unit)
                }
            },
            handle = { _: String -> handled.set(true) },
            onFailure = { throw AssertionError(it) },
        )
        try {
            assertTrue(queue.submit("START"))
            assertTrue(readEntered.await(2, TimeUnit.SECONDS))
            queue.close()
            assertFalse(queue.submit("SCAN"))
            releaseRead.countDown()
            withTimeout(2_000) { workerFinished.await() }
            assertFalse(published.get())
            assertFalse(handled.get())
        } finally {
            releaseRead.countDown()
            queue.close()
            scope.cancel()
        }
    }

    @Test fun failedInitializationRejectsCommandsAndReportsCause() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reported = CompletableDeferred<Exception>()
        val handled = AtomicBoolean(false)
        lateinit var queue: ServerCommandQueue<String>
        queue = ServerCommandQueue(
            scope,
            initialize = { throw IOException("Index indisponible") },
            handle = { handled.set(true) },
            onFailure = { queue.close(); reported.complete(it) },
        )
        try {
            assertTrue(queue.submit("START"))
            val error = withTimeout(2_000) { reported.await() }
            assertEquals("Index indisponible", error.message)
            assertFalse(queue.submit("START"))
            assertFalse(handled.get())
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test fun closeBeforeStartNeverReadsIndex() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val read = AtomicBoolean(false)
        val queue = ServerCommandQueue(
            scope,
            initialize = { read.set(true) },
            handle = { _: String -> },
            onFailure = { throw AssertionError(it) },
        )
        queue.close()
        assertFalse(queue.submit("START"))
        assertFalse(read.get())
        scope.cancel()
    }
}
