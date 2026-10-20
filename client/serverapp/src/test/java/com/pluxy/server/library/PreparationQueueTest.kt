package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test

class PreparationQueueTest {
    private data class Item(val id: String)

    @Test fun `cached files retries playback and replaced catalogs are handled`() {
        var now = 100L
        var busy = true
        val states = linkedMapOf<String, Long?>("cached" to null, "retry" to 150L, "new" to 0L)
        val calls = mutableListOf<String>()
        val queue = PreparationQueue<Item>({ it.id }, { states.getValue(it.id) }, {
            calls += it.id; states[it.id] = null
        }, { busy }, { now }, startWorker = false)
        try {
            queue.update(states.keys.map { Item(it) })
            queue.tick(); assertTrue(calls.isEmpty())
            busy = false
            queue.tick(); assertEquals(listOf("new"), calls)
            queue.tick(); assertEquals(listOf("new"), calls)
            now = 151
            queue.tick(); assertEquals(listOf("new", "retry"), calls)
            states["latest"] = 0
            queue.update(listOf(Item("latest")))
            queue.tick(); assertEquals("latest", calls.last())
            queue.close(); queue.update(listOf(Item("new"))); queue.tick()
            assertEquals(3, calls.size)
        } finally { queue.close() }
    }

    @Test fun `a failing source cannot block the next file or spin continuously`() {
        var now = 100L
        val attempts = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val queue = PreparationQueue<Item>({ it.id }, {
            attempts += it.id
            if (it.id == "broken") throw java.io.IOException("Offline source")
            if (calls.isEmpty()) 0L else null
        }, { calls += it.id }, { false }, { now }, startWorker = false)
        try {
            queue.update(listOf(Item("broken"), Item("good")))
            queue.tick(); queue.tick()
            assertEquals(listOf("good"), calls)
            assertEquals(1, attempts.count { it == "broken" })
            now = 60_101
            queue.tick(); assertEquals(2, attempts.count { it == "broken" })
        } finally { queue.close() }
    }

    @Test fun `all seven files are dispatched one at a time`() {
        val completed = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        val queue = PreparationQueue<Item>({ it.id }, { if (it.id in completed) null else 0L }, {
            calls += it.id; completed += it.id
        }, { false }, startWorker = false)
        try {
            queue.update((6 downTo 0).map { Item(it.toString()) })
            repeat(7) { queue.tick(); assertEquals(it + 1, calls.size) }
            queue.tick()
            assertEquals((6 downTo 0).map { it.toString() }, calls)
        } finally { queue.close() }
    }
}
