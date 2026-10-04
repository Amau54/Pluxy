package com.pluxy.server.http

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Long transfers cannot consume the workers reserved for discovery and control. */
internal class HttpWorkScheduler(controlThreads: Int = 8, transferThreads: Int = 4, pending: Int = 8) : Closeable {
    private val control = pool("control", controlThreads, pending)
    private val transfers = pool("transfer", transferThreads, pending)
    fun control(task: () -> Unit) = control.execute(task)
    fun transfer(task: () -> Unit) = transfers.execute(task)
    override fun close() {
        control.shutdownNow()
        transfers.shutdownNow()
    }
    private fun pool(label: String, threads: Int, pending: Int): ThreadPoolExecutor {
        val counter = AtomicInteger()
        return ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(pending),
            { task -> Thread(task, "pluxy-http-$label-${counter.incrementAndGet()}").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}

/** Updated after actual socket writes, rather than when bytes enter a user-space buffer. */
internal class ConnectionActivity(private val now: () -> Long = System::nanoTime) {
    @Volatile private var lastProgress = now()
    fun progress() { lastProgress = now() }
    fun expired(timeoutNanos: Long): Boolean = now() - lastProgress >= timeoutNanos
}
