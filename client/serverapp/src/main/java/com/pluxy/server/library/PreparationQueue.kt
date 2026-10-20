package com.pluxy.server.library

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One outstanding background item; playback requests keep their own executor queue. */
internal class PreparationQueue<T : Any>(
    private val key: (T) -> String,
    private val readyAt: (T) -> Long?,
    private val submit: (T) -> Unit,
    private val busy: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val startWorker: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private val items = linkedMapOf<String, T>()
    private val due = mutableMapOf<String, Long>()
    private val signal = Semaphore(0)
    @Volatile private var closed = false
    private var thread: Thread? = null

    fun update(values: List<T>) {
        synchronized(lock) {
            if (closed) return
            items.clear(); values.forEach { items[key(it)] = it }; due.clear()
            if (thread == null && startWorker) thread = Thread({
                while (!closed) {
                    try { signal.tryAcquire(30, TimeUnit.SECONDS); signal.drainPermits() }
                    catch (_: InterruptedException) { return@Thread }
                    if (!closed) tick()
                }
            }, "pluxy-preparation").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        }
        wake()
    }

    fun wake() { signal.release() }

    fun tick() {
        val snapshot = synchronized(lock) { items.toList() }
        for ((id, item) in snapshot) {
            if (closed || busy()) return
            if (synchronized(lock) { items[id] !== item || (due[id] ?: 0) > clock() }) continue
            try {
                val ready = readyAt(item)
                if (ready == null) {
                    synchronized(lock) { if (items[id] === item) { items.remove(id); due.remove(id) } }
                } else if (ready > clock()) {
                    synchronized(lock) { due[id] = ready }
                } else {
                    if (busy()) return
                    submit(item)
                    return
                }
            } catch (_: Exception) { synchronized(lock) { due[id] = clock() + 60_000 } }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; items.clear(); due.clear() }
        wake(); thread?.interrupt()
    }
}
