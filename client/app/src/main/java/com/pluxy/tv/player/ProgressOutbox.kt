package com.pluxy.tv.player

import android.content.Context
import android.util.AtomicFile
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.PlaybackProgress
import com.pluxy.tv.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong

internal data class PendingProgress(
    val serverUrl: String,
    val itemId: String,
    val sessionId: String,
    val sequence: Long,
    val positionMs: Long,
    val durationMs: Long,
    val createdAtMs: Long,
    val intentOrder: Long = createdAtMs,
    val profileId: String = "default",
) {
    val key: String get() = "$serverUrl\n$profileId\n$itemId"
}

/** A success may acknowledge only the exact update sent, never a newer user seek. */
internal class ProgressPendingState {
    private val entries = LinkedHashMap<String, PendingProgress>()
    // A delayed coroutine can arrive after the latest send was acknowledged.
    // Retain its watermark beyond acknowledgement for this process lifetime.
    private val latest = LinkedHashMap<String, PendingProgress>()
    fun add(update: PendingProgress) {
        val old = latest[update.key]
        if (old != null && old.sessionId == update.sessionId && old.sequence >= update.sequence) return
        if (old != null && old.sessionId != update.sessionId && old.intentOrder > update.intentOrder) return
        latest.remove(update.key)
        latest[update.key] = update
        // Same upper bound as the paginated catalogue, separate from the 128
        // updates retained on disk. Old tasks die when the process is recreated.
        while (latest.size > 100_000) latest.remove(latest.keys.first())
        entries.remove(update.key)
        entries[update.key] = update
        while (entries.size > 128) entries.remove(entries.keys.first())
    }
    fun acknowledge(update: PendingProgress) {
        if (entries[update.key] == update) entries.remove(update.key)
    }
    fun snapshot(): List<PendingProgress> = entries.values.toList()
}

/** Durable, coalescing queue. Disk publication and HTTP errors are never silently acknowledged. */
object ProgressOutbox {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pending = ProgressPendingState()
    private val retryAt = HashMap<String, Long>()
    private var file: AtomicFile? = null
    private var loaded = false
    private var started = false
    private val intentOrder = AtomicLong(System.currentTimeMillis() * 1000L)

    @Synchronized
    fun start(context: Context) {
        if (started) return
        file = AtomicFile(File(context.applicationContext.filesDir, "playback-progress-outbox.json"))
        started = true
        scope.launch { drain() }
        wake.trySend(Unit)
    }

    fun enqueue(context: Context, api: PluxyApi, itemId: String, positionMs: Long, durationMs: Long, sequence: Long) {
        start(context)
        // Capture before dispatch: activity/API/media can change while IO is pending.
        val update = PendingProgress(api.baseUrl, itemId, api.sessionId, sequence,
            positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), System.currentTimeMillis(),
            intentOrder.incrementAndGet(), api.profileId)
        scope.launch {
            try {
                mutex.withLock {
                    readOnce()
                    pending.add(update)
                    retryAt.remove(update.key)
                    persist()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logger.log("progress", "Reprise conservée en attente d'une sauvegarde locale")
                // Keep the in-memory update even if the local journal is temporarily locked.
                mutex.withLock { pending.add(update) }
            }
            wake.trySend(Unit)
        }
    }

    /** Pending local intent is newer than an unacknowledged server snapshot. */
    suspend fun pendingResume(serverUrl: String, itemId: String): PlaybackProgress? = withContext(Dispatchers.IO) {
        mutex.withLock {
            // An unreadable journal is not proof that there is no local resume.
            // Propagate that failure instead of replacing it with an older server value.
            readOnce()
            pending.snapshot().firstOrNull { it.serverUrl == serverUrl && it.itemId == itemId && it.profileId == com.pluxy.tv.ui.FamilyProfileSelection.selected(serverUrl) }
                ?.let { update ->
                    val watched = update.durationMs > 0 && update.positionMs > update.durationMs * .95
                    PlaybackProgress(if (watched) 0 else update.positionMs, update.durationMs, watched)
                }
        }
    }

    private fun readOnce() {
        if (loaded) return
        val journal = file ?: return
        val bytes = try { journal.readFully() } catch (error: FileNotFoundException) {
            if (journal.baseFile.exists()) throw error
            ByteArray(0)
        }
        val restored = try {
            val array = if (bytes.isEmpty()) JSONArray() else {
                require(bytes.size <= 256 * 1024)
                JSONArray(String(bytes, Charsets.UTF_8)).also { require(it.length() <= 128) }
            }
            // Decode the entire journal before publishing: a malformed later entry
            // must not leave partially restored state or block every future retry.
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                PendingProgress(entry.getString("server"), entry.getString("item"),
                    entry.getString("session"), entry.getLong("sequence"), entry.getLong("position"),
                    entry.getLong("duration"), entry.getLong("created"),
                    entry.optLong("order", entry.getLong("created")), entry.optString("profile", "default"))
            }
        } catch (_: Exception) {
            // Preserve corrupt bytes for recovery, but allow new resumptions to be saved.
            val recovery = File(journal.baseFile.parentFile,
                "playback-progress-corrupt-${System.currentTimeMillis()}.json")
            check(journal.baseFile.renameTo(recovery))
            Logger.log("progress", "Ancien journal invalide conservé pour récupération")
            emptyList()
        }
        val now = System.currentTimeMillis()
        for (update in restored) {
            if (update.sequence >= 0 && update.positionMs >= 0 && update.durationMs > 0 &&
                update.serverUrl.length <= 2048 &&
                runCatching { java.net.URI(update.serverUrl).let {
                    it.scheme == "http" && it.host != null && it.userInfo == null
                } }.getOrDefault(false) &&
                update.itemId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                update.profileId.matches(Regex("(?:default|[0-9a-f]{32})")) &&
                update.sessionId.matches(Regex("[A-Za-z0-9_-]{8,64}")) &&
                now - update.createdAtMs in 0..7 * 24 * 60 * 60 * 1000L
            ) pending.add(update)
            while (true) {
                val currentOrder = intentOrder.get()
                if (currentOrder >= update.intentOrder || intentOrder.compareAndSet(currentOrder, update.intentOrder)) break
            }
        }
        loaded = true
    }

    private fun persist() {
        check(loaded) // A refused read must never overwrite an unread journal.
        val journal = checkNotNull(file)
        val values = JSONArray()
        for (entry in pending.snapshot()) values.put(JSONObject().apply {
            put("server", entry.serverUrl); put("item", entry.itemId); put("session", entry.sessionId)
            put("sequence", entry.sequence); put("position", entry.positionMs)
            put("duration", entry.durationMs); put("created", entry.createdAtMs)
            put("order", entry.intentOrder); put("profile", entry.profileId)
        })
        val bytes = values.toString().toByteArray(Charsets.UTF_8)
        val stream = journal.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            journal.finishWrite(stream)
            check(journal.readFully().contentEquals(bytes)) { "Journal non publié" }
        } catch (error: Exception) {
            journal.failWrite(stream)
            throw error
        }
    }

    private suspend fun drain() {
        var connection: PluxyApi? = null
        while (true) {
            val now = System.currentTimeMillis()
            val update = try {
                mutex.withLock {
                    readOnce()
                    persist()
                    pending.snapshot().firstOrNull { (retryAt[it.key] ?: 0) <= now }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logger.log("progress", "Journal de reprise temporairement indisponible")
                withTimeoutOrNull(10_000) { wake.receive() }
                continue
            }
            if (update == null) {
                val hasPending = mutex.withLock { pending.snapshot().isNotEmpty() }
                if (hasPending) withTimeoutOrNull(10_000) { wake.receive() } else wake.receive()
                continue
            }
            try {
                if (connection?.baseUrl != update.serverUrl || connection?.sessionId != update.sessionId || connection?.profileId != update.profileId) {
                    connection?.close()
                    connection = PluxyApi(update.serverUrl, update.sessionId, profileId = update.profileId)
                }
                checkNotNull(connection).setProgress(update.itemId,
                    update.positionMs, update.durationMs, update.sequence)
                mutex.withLock {
                    pending.acknowledge(update)
                    retryAt.remove(update.key)
                    persist()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutex.withLock { retryAt[update.key] = System.currentTimeMillis() + 30_000 }
                Logger.log("progress", "Serveur indisponible : reprise enregistrée, nouvel essai prévu")
            }
        }
    }
}
