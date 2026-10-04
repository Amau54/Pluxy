package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Collections
import java.util.Locale

/** A small, server-scoped catalogue cache shared by the catalogue and its viewers. */
internal class CatalogueStore(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val freshnessMs: Long = 30_000,
    private val capacity: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class Snapshot(val serverUrl: String, val revision: Long, val items: List<MediaItem>)
    private data class Entry(val snapshot: Snapshot, val validatedAtMs: Long)
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private class Pending(val result: CompletableDeferred<Snapshot>, var job: Job? = null)
    private val inFlight = mutableMapOf<String, Pending>()
    private var revision = 0L

    /** Rendering an existing snapshot must not wait for validation or extend its freshness. */
    suspend fun cached(serverUrl: String): Snapshot? = mutex.withLock {
        val key = catalogueServerKey(serverUrl)
        val entry = entries.remove(key) ?: return@withLock null
        entries[key] = entry
        entry.snapshot
    }

    suspend fun load(
        serverUrl: String,
        forceRefresh: Boolean = false,
        loader: suspend () -> List<MediaItem>,
    ): Snapshot {
        val key = catalogueServerKey(serverUrl)
        val result = mutex.withLock {
            val previous = entries[key]
            if (!forceRefresh && previous != null && clockMs() - previous.validatedAtMs < freshnessMs) {
                entries.remove(key)
                entries[key] = previous
                return@withLock CompletableDeferred(previous.snapshot)
            }
            inFlight[key]?.let { return@withLock it.result }
            val pending = Pending(CompletableDeferred())
            inFlight[key] = pending
            // A viewer's cancellation must not cancel a load still awaited by another screen.
            pending.job = scope.launch {
                try {
                    val items: List<MediaItem> = Collections.unmodifiableList(ArrayList(loader()))
                    mutex.withLock {
                        if (inFlight[key] !== pending) throw CancellationException("Catalogue invalidated")
                        val old = entries[key]?.snapshot
                        val fresh = if (old?.items == items) old else Snapshot(key, ++revision, items)
                        entries.remove(key)
                        entries[key] = Entry(fresh, clockMs())
                        while (entries.size > capacity.coerceAtLeast(1)) entries.remove(entries.keys.first())
                        inFlight.remove(key)
                        pending.result.complete(fresh)
                    }
                } catch (failure: Throwable) {
                    pending.result.completeExceptionally(failure)
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { if (inFlight[key] === pending) inFlight.remove(key) }
                    }
                }
            }
            pending.result
        }
        return result.await()
    }

    suspend fun invalidate(serverUrl: String) = mutex.withLock {
        val key = catalogueServerKey(serverUrl)
        entries.remove(key)
        inFlight.remove(key)?.let {
            it.result.cancel(CancellationException("Catalogue invalidated"))
            it.job?.cancel()
        }
        Unit
    }
}

internal fun catalogueServerKey(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme.equals("http", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
        (uri.port == -1 || uri.port in 1..65535)) { "Invalid catalogue server URL" }
    return URI("http", null, uri.host.lowercase(Locale.ROOT), uri.port, null, null, null).toString()
}

internal object CatalogueRepository {
    private val store = CatalogueStore()

    suspend fun cached(serverUrl: String): CatalogueStore.Snapshot? = store.cached(serverUrl)

    suspend fun load(
        serverUrl: String,
        forceRefresh: Boolean = false,
        loader: suspend () -> List<MediaItem>,
    ): CatalogueStore.Snapshot = store.load(serverUrl, forceRefresh, loader)

    suspend fun invalidate(serverUrl: String) = store.invalidate(serverUrl)
}
