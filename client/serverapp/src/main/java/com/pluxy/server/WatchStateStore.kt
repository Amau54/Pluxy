package com.pluxy.server

import java.io.IOException

internal fun verifyWatchPublication(expected: ByteArray, readPublished: () -> ByteArray) {
    if (!readPublished().contentEquals(expected)) throw IOException("Publication de l'historique échouée")
}

data class WatchState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    val lastPlayedAtMs: Long = 0,
)

internal data class WatchRecord(
    val state: WatchState = WatchState(),
    val sessionId: String? = null,
    val sequence: Long = -1,
    val retiredSessions: Set<String> = emptySet(),
)

/** Publishes a new value only after its durable write succeeds. */
internal class WatchStateStore(
    private val load: (String) -> WatchRecord,
    private val persist: (String, WatchRecord) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val records = mutableMapOf<String, WatchRecord>()

    @Synchronized
    fun get(id: String): WatchRecord = records.getOrPut(id) { load(id) }

    @Synchronized
    fun update(id: String, position: Long, duration: Long, sessionId: String?, sequence: Long?): WatchRecord {
        require((sessionId == null) == (sequence == null)) { "session_id et sequence doivent être fournis ensemble" }
        require(sessionId == null || SESSION.matches(sessionId)) { "session_id invalide" }
        require(sequence == null || sequence >= 0) { "sequence invalide" }
        val old = get(id)
        if (sessionId != null && sessionId in old.retiredSessions ||
            sessionId != null && sessionId == old.sessionId && sequence!! <= old.sequence
        ) return old
        val safeDuration = duration.coerceAtLeast(0)
        val safePosition = position.coerceAtLeast(0).let {
            if (safeDuration > 0) it.coerceAtMost(safeDuration) else it
        }
        val watched = safeDuration > 0 && safePosition.toDouble() / safeDuration > 0.95
        val next = WatchRecord(
            state = WatchState(if (watched) 0 else safePosition, safeDuration, watched, now()),
            sessionId = sessionId ?: old.sessionId,
            sequence = sequence ?: old.sequence,
            retiredSessions = (old.retiredSessions + listOfNotNull(
                old.sessionId?.takeIf { sessionId != null && it != sessionId },
            )).toList().takeLast(128).toSet(),
        )
        persist(id, next)
        records[id] = next
        return next
    }

    @Synchronized
    fun forget(ids: Set<String>, delete: (String) -> Unit = {}) {
        ids.forEach { delete(it); records.remove(it) }
    }

    companion object { private val SESSION = Regex("[A-Za-z0-9_-]{8,64}") }
}
