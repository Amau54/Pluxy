package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class CatalogueRepositoryTest {
    @Test fun `profile switch isolates snapshots and server invalidation clears every profile`() = runBlocking {
        var profile = "default"
        val store = CatalogueStore(profile = { profile }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val shared = store.load("http://server:8420") { listOf(media("shared")) }
        profile = "a".repeat(32)
        assertNull(store.cached("http://server:8420"))
        val family = store.load("http://server:8420") { listOf(media("family")) }
        assertNotSame(shared, family)
        profile = "default"
        assertSame(shared, store.cached("http://server:8420"))
        store.invalidate("http://server:8420")
        assertNull(store.cached("http://server:8420"))
        profile = "a".repeat(32)
        assertNull(store.cached("http://server:8420"))
    }
    private fun store(clock: () -> Long = { 0L }) = CatalogueStore(
        clockMs = clock, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
    @Test fun `fresh snapshot is reused and isolated from caller mutation`() = runBlocking {
        val store = store()
        val items = mutableListOf(media("a"))
        val first = store.load("http://server:8420/") { items }
        items.clear()
        assertSame(first, store.load("http://SERVER:8420") { error("unexpected reload") })
        assertEquals(listOf("a"), first.items.map { it.id })
    }
    @Test fun `refresh retains revision for identical contents and advances on change`() = runBlocking {
        val store = store()
        val first = store.load("http://server:8420") { listOf(media("a")) }
        val same = store.load("http://server:8420", true) { listOf(media("a")) }
        val changed = store.load("http://server:8420", true) { listOf(media("b")) }
        assertEquals(first.revision, same.revision)
        assertTrue(changed.revision > same.revision)
    }
    @Test fun `expiry and server change require their own loads`() = runBlocking {
        val clock = AtomicLong()
        val store = store(clock::get)
        val a = store.load("http://a:8420") { listOf(media("a")) }
        val b = store.load("http://b:8420") { listOf(media("b")) }
        assertNotEquals(a.serverUrl, b.serverUrl)
        clock.set(30_001)
        assertEquals("updated", store.load("http://a:8420") { listOf(media("updated")) }.items.single().id)
    }
    @Test fun `parallel requests share one load and cancellation of one viewer does not stop another`() = runBlocking {
        val store = store()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            store.load("http://server:8420") { calls++; gate.await(); listOf(media("a")) }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            store.load("http://server:8420", true) { calls++; error("duplicate") }
        }
        first.cancelAndJoin()
        gate.complete(Unit)
        assertEquals("a", second.await().items.single().id)
        assertEquals(1, calls)
    }
    @Test fun `invalidation rejects a late response and permits a new load`() = runBlocking {
        val store = store()
        val gate = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            store.load("http://server:8420") {
                withContext(NonCancellable) { gate.await() }
                listOf(media("old"))
            }
        }
        store.invalidate("http://server:8420")
        val fresh = store.load("http://server:8420") { listOf(media("new")) }
        gate.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertSame(fresh, store.load("http://server:8420") { error("old response was published") })
    }
    @Test fun `failed refresh preserves the previous successful snapshot`() = runBlocking {
        val store = store()
        val first = store.load("http://server:8420") { listOf(media("a")) }
        assertTrue(runCatching { store.load("http://server:8420", true) { throw IOException("offline") } }.isFailure)
        assertSame(first, store.load("http://server:8420") { error("unexpected reload") })
    }
    @Test fun `cache retains at most two server catalogues`() = runBlocking {
        val store = store()
        store.load("http://a:8420") { listOf(media("a")) }
        store.load("http://b:8420") { listOf(media("b")) }
        store.load("http://c:8420") { listOf(media("c")) }
        assertEquals("reloaded", store.load("http://a:8420") { listOf(media("reloaded")) }.items.single().id)
    }
    @Test fun `cached snapshot renders after expiry without delaying fresh validation`() = runBlocking {
        val clock = AtomicLong()
        val store = store(clock::get)
        val old = store.load("http://server:8420") { listOf(media("old")) }
        clock.set(30_001)
        assertSame(old, store.cached("http://SERVER:8420/"))
        val gate = CompletableDeferred<Unit>()
        val validation = async(start = CoroutineStart.UNDISPATCHED) {
            store.load("http://server:8420") { gate.await(); listOf(media("new")) }
        }
        assertSame(old, store.cached("http://server:8420"))
        assertFalse(validation.isCompleted)
        gate.complete(Unit)
        assertEquals("new", validation.await().items.single().id)
    }
    @Test fun `reading cached snapshot updates eviction but never crosses server or invalidation`() = runBlocking {
        val store = store()
        val a = store.load("http://a:8420") { listOf(media("a")) }
        store.load("http://b:8420") { listOf(media("b")) }
        assertNull(store.cached("http://unconfigured:8420"))
        assertSame(a, store.cached("http://a:8420"))
        store.load("http://c:8420") { listOf(media("c")) }
        assertNull(store.cached("http://b:8420"))
        store.invalidate("http://a:8420")
        assertNull(store.cached("http://a:8420"))
    }
    @Test fun `cache rejects a URL with credentials query or path before its loader`() = runBlocking {
        val store = store()
        for (url in listOf("http://user:password@server:8420", "http://server:8420/items", "http://server:8420?token=1")) {
            assertTrue(runCatching { store.load(url) { error("loader must not run") } }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun `kinds keep independent snapshots and never poison the full catalogue`() = runBlocking {
        val store = CatalogueStore(clockMs = { 0L }, capacity = 3,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val all = store.load("http://server:8420") { listOf(media("movie"), media("track")) }
        val audio = store.load("http://SERVER:8420/", mediaKind = "audio") { listOf(media("track")) }
        val photos = store.load("http://server:8420", mediaKind = "photo") { listOf(media("image")) }
        assertSame(all, store.cached("http://server:8420"))
        assertSame(audio, store.load("http://server:8420", mediaKind = "audio") { error("unexpected load") })
        assertEquals("http://server:8420", photos.serverUrl)
        assertEquals(listOf("image"), photos.items.map { it.id })
    }
    @Test fun `same server different kinds do not share an in flight response`() = runBlocking {
        val store = store()
        val gate = CompletableDeferred<Unit>()
        val audio = async(start = CoroutineStart.UNDISPATCHED) {
            store.load("http://server:8420", mediaKind = "audio") { gate.await(); listOf(media("track")) }
        }
        val photo = store.load("http://server:8420", mediaKind = "photo") { listOf(media("image")) }
        assertEquals("image", photo.items.single().id)
        assertFalse(audio.isCompleted)
        gate.complete(Unit)
        assertEquals("track", audio.await().items.single().id)
    }
    @Test fun `server rescan invalidates every kind including pending loads`() = runBlocking {
        val store = store()
        store.load("http://server:8420", mediaKind = "audio") { listOf(media("track")) }
        val gate = CompletableDeferred<Unit>()
        val photo = async(start = CoroutineStart.UNDISPATCHED) {
            store.load("http://server:8420", mediaKind = "photo") {
                withContext(NonCancellable) { gate.await() }; listOf(media("old"))
            }
        }
        store.invalidate("http://SERVER:8420/")
        gate.complete(Unit)
        assertNull(store.cached("http://server:8420", "audio"))
        assertTrue(runCatching { photo.await() }.exceptionOrNull() is CancellationException)
        assertEquals("new", store.load("http://server:8420", mediaKind = "photo") { listOf(media("new")) }.items.single().id)
    }
    private fun media(id: String) = MediaItem(id, id, "$id.mp4", "mp4", 1, 10.0)
}
