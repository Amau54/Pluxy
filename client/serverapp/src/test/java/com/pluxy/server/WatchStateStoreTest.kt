package com.pluxy.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WatchStateStoreTest {
    private val records = mutableMapOf<String, WatchRecord>()
    private fun store(persist: (String, WatchRecord) -> Unit = { id, record -> records[id] = record }) =
        WatchStateStore({ records[it] ?: WatchRecord() }, persist) { 42L }

    @Test fun `stale duplicates and retired sessions never write`() {
        var writes = 0
        val store = store { id, record -> writes++; records[id] = record }
        val first = store.update("a", 200, 1000, "session_A", 2)
        assertEquals(first, store.update("a", 100, 1000, "session_A", 1))
        assertEquals(first, store.update("a", 300, 1000, "session_A", 2))
        val second = store.update("a", 400, 1000, "session_B", 0)
        assertEquals(second, store.update("a", 900, 1000, "session_A", 99))
        assertEquals(2, writes)
        assertEquals(2L, store.revision)
        assertEquals(second, store().get("a")) // Ordering survives process reconstruction.
    }

    @Test fun `legacy updates remain writable and retain ordering metadata`() {
        val store = store()
        store.update("a", 200, 1000, "session_A", 2)
        val legacy = store.update("a", 300, 1000, null, null)
        assertEquals(300L, legacy.state.positionMs)
        assertEquals("session_A", legacy.sessionId)
        assertEquals(2L, legacy.sequence)
        assertEquals(legacy, store.update("a", 100, 1000, "session_A", 1))
        assertEquals(400L, store.update("a", 400, 1000, "session_A", 3).state.positionMs)
    }

    @Test fun `failed durable write does not publish or consume sequence`() {
        var fail = false
        lateinit var store: WatchStateStore
        store = store { id, record ->
            assertNotEquals(record, store.get(id))
            if (fail) throw IOException("disk full")
            records[id] = record
        }
        val old = store.update("a", 200, 1000, "session_A", 1)
        fail = true
        assertThrows(IOException::class.java) { store.update("a", 300, 1000, "session_A", 2) }
        assertEquals(old, store.get("a"))
        assertEquals(old, records["a"])
        assertEquals(1L, store.revision)
        fail = false
        assertEquals(300L, store.update("a", 300, 1000, "session_A", 2).state.positionMs)
        assertEquals(2L, store.revision)
    }

    @Test fun `protocol validates pair token and nonnegative integer`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.update("a", 0, 0, "session_A", null) }
        assertThrows(IllegalArgumentException::class.java) { store.update("a", 0, 0, null, 0) }
        assertThrows(IllegalArgumentException::class.java) { store.update("a", 0, 0, "short", 0) }
        assertThrows(IllegalArgumentException::class.java) { store.update("a", 0, 0, "session_A", -1) }
        assertEquals(0L, store.update("a", -20, -1, "session_A", 0).state.positionMs)
        assertTrue(store.update("a", 2000, 1000, "session_A", 1).state.watched)
    }

    @Test fun `silent storage publication failure is reported before advancing memory`() {
        var published = "old".toByteArray()
        var renameWorks = false
        val store = store { _, record ->
            val attempted = record.sequence.toString().toByteArray()
            // A successful sync/finish can still leave the old destination after rename failure.
            if (renameWorks) published = attempted
            verifyWatchPublication(attempted) { published }
        }
        assertThrows(IOException::class.java) { store.update("a", 200, 1000, "session_A", 1) }
        assertEquals(-1L, store.get("a").sequence)
        renameWorks = true
        assertEquals(1L, store.update("a", 200, 1000, "session_A", 1).sequence)
    }

    @Test fun `concurrent submissions converge to greatest sequence`() {
        val store = store()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val work = (99L downTo 0).map { seq -> pool.submit { store.update("a", seq, 10000, "session_A", seq) } }
            work.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(99L, store.get("a").sequence)
            assertEquals(99L, store.get("a").state.positionMs)
        } finally { pool.shutdownNow() }
    }

    @Test fun `retired history is bounded`() {
        val store = store()
        repeat(140) { store.update("a", 0, 0, "session_${it.toString().padStart(3, '0')}", 0) }
        assertEquals(128, store.get("a").retiredSessions.size)
        assertFalse("session_000" in store.get("a").retiredSessions)
        assertTrue("session_138" in store.get("a").retiredSessions)
    }
}
