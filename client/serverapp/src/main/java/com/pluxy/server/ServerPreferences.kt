package com.pluxy.server

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Un dossier SAF, organisé indépendamment pour chaque thème de la bibliothèque. */
data class MediaFolder(
    val uri: String,
    val title: String = "",
    val visible: Boolean = true,
    val category: String = "auto",
)

enum class MediaTheme(val label: String) {
    VIDEO("Films, séries et vidéos"),
    PHOTO("Images"),
    AUDIO("Musique"),
}

/** Toutes les préférences nécessaires au serveur, sans accès au stockage historique PC. */
class ServerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val watch = context.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE)
    private val watchDirectory = File(context.filesDir, "watch_state")
    // Activity/service instances and a fast service restart must share one ordered writer.
    private val watchStore = synchronized(watchStores) {
        watchStores.getOrPut(watchDirectory.absolutePath) { WatchStateStore(::loadWatchRecord, ::persistWatchRecord) }
    }

    /**
     * Several SAF trees can be assigned to each library theme. The legacy single-folder
     * entries remain readable so existing installs keep their selection after the upgrade.
     */
    var videoFolders: List<MediaFolder>
        get() = folders(KEY_VIDEO_TREE_URIS, KEY_TREE_URI)
        set(value) = saveFolders(KEY_VIDEO_TREE_URIS, KEY_TREE_URI, value)

    var photoFolders: List<MediaFolder>
        get() = folders(KEY_PHOTO_TREE_URIS, KEY_PHOTO_TREE_URI)
        set(value) = saveFolders(KEY_PHOTO_TREE_URIS, KEY_PHOTO_TREE_URI, value)

    var audioFolders: List<MediaFolder>
        get() = folders(KEY_AUDIO_TREE_URIS, KEY_AUDIO_TREE_URI)
        set(value) = saveFolders(KEY_AUDIO_TREE_URIS, KEY_AUDIO_TREE_URI, value)

    /** Compatibilité avec les installations précédentes et les appels internes historiques. */
    var videoTreeUris: List<String>
        get() = videoFolders.filter { it.visible }.map { it.uri }
        set(value) { videoFolders = value.map(::MediaFolder) }

    var photoTreeUris: List<String>
        get() = photoFolders.filter { it.visible }.map { it.uri }
        set(value) { photoFolders = value.map(::MediaFolder) }

    var audioTreeUris: List<String>
        get() = audioFolders.filter { it.visible }.map { it.uri }
        set(value) { audioFolders = value.map(::MediaFolder) }

    var autoDiscoverMedia: Boolean
        // Les choix déjà enregistrés restent prioritaires sur la valeur par défaut.
        get() = prefs.getBoolean(KEY_AUTO_DISCOVER_MEDIA, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DISCOVER_MEDIA, value).apply()

    fun hasMediaSource(): Boolean = autoDiscoverMedia ||
        listOf(videoFolders, photoFolders, audioFolders).any { folders -> folders.any { it.visible } }

    fun hasManualMediaSource(): Boolean =
        videoFolders.any { it.visible } || photoFolders.any { it.visible } || audioFolders.any { it.visible }

    fun folders(theme: MediaTheme): List<MediaFolder> = when (theme) {
        MediaTheme.VIDEO -> videoFolders
        MediaTheme.PHOTO -> photoFolders
        MediaTheme.AUDIO -> audioFolders
    }

    fun saveFolders(theme: MediaTheme, values: List<MediaFolder>) = when (theme) {
        MediaTheme.VIDEO -> videoFolders = values
        MediaTheme.PHOTO -> photoFolders = values
        MediaTheme.AUDIO -> audioFolders = values
    }

    private fun folders(collectionKey: String, legacyKey: String): List<MediaFolder> {
        val stored = prefs.getString(collectionKey, null)
        if (stored != null) return runCatching {
            JSONArray(stored).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val raw = array.opt(index)
                        when (raw) {
                            is JSONObject -> raw.optString("uri").trim().takeIf(String::isNotBlank)?.let { uri ->
                                add(MediaFolder(
                                    uri = uri,
                                    title = raw.optString("title").trim().take(60),
                                    visible = raw.optBoolean("visible", true),
                                    category = raw.optString("category", "auto").takeIf { it in setOf("movie", "series", "clip") } ?: "auto",
                                ))
                            }
                            is String -> raw.trim().takeIf(String::isNotBlank)?.let(::MediaFolder)?.let(::add)
                        }
                    }
                }.distinctBy { it.uri }
            }
        }.getOrDefault(emptyList())
        return prefs.getString(legacyKey, null)?.trim()?.takeIf(String::isNotBlank)
            ?.let(::MediaFolder)?.let(::listOf).orEmpty()
    }

    private fun saveFolders(collectionKey: String, legacyKey: String, values: List<MediaFolder>) {
        val normalized = values.mapNotNull { folder ->
            folder.uri.trim().takeIf(String::isNotBlank)?.let { uri ->
                MediaFolder(uri, folder.title.trim().take(60), folder.visible, folder.category.takeIf { it in setOf("movie", "series", "clip") } ?: "auto")
            }
        }.distinctBy { it.uri }
        prefs.edit()
            .putString(collectionKey, JSONArray().apply {
                normalized.forEach { folder -> put(JSONObject().apply {
                    put("uri", folder.uri)
                    put("title", folder.title)
                    put("visible", folder.visible)
                    put("category", folder.category)
                }) }
            }.toString())
            .remove(legacyKey)
            .apply()
    }

    var serverName: String
        get() = prefs.getString(KEY_SERVER_NAME, defaultServerName()).orEmpty()
            .ifBlank { defaultServerName() }
        set(value) = prefs.edit().putString(KEY_SERVER_NAME, value.trim().take(60)).apply()

    var tmdbApiKey: String
        get() = prefs.getString(KEY_TMDB, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TMDB, value.trim().take(4_096)).apply()

    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var runningDesired: Boolean
        get() = prefs.getBoolean(KEY_RUNNING_DESIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_RUNNING_DESIRED, value).apply()

    val httpPort: Int get() = DEFAULT_HTTP_PORT
    val discoveryPort: Int get() = DEFAULT_DISCOVERY_PORT

    @Synchronized
    fun watchState(itemId: String): WatchState {
        return watchStore.get(itemId).state
    }

    internal val watchRevision: Long get() = watchStore.revision

    @Synchronized
    fun setWatchState(itemId: String, positionMs: Long, durationMs: Long, sessionId: String? = null, sequence: Long? = null): WatchState {
        return watchStore.update(itemId, positionMs, durationMs, sessionId, sequence).state
    }

    /** Wait for pending preference writes before dropping a persisted SAF permission. */
    fun flushFolders(): Boolean = prefs.edit().commit()

    private fun watchFile(id: String): AtomicFile {
        require(id.matches(Regex("[0-9a-f]+"))) { "Identifiant média invalide" }
        return AtomicFile(File(watchDirectory, "$id.json"))
    }

    private fun loadWatchRecord(id: String): WatchRecord {
        val file = watchFile(id)
        val raw = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else watch.getString(id, null) ?: return WatchRecord()
        return try {
            val json = JSONObject(raw)
            val retired = json.optJSONArray("retired_sessions")
            WatchRecord(
                WatchState(json.optLong("position_ms").coerceAtLeast(0), json.optLong("duration_ms").coerceAtLeast(0),
                    json.optBoolean("watched"), json.optLong("last_played_at_ms").coerceAtLeast(0)),
                json.optString("session_id").takeIf { it.isNotBlank() }, json.optLong("sequence", -1),
                buildSet { if (retired != null) for (index in 0 until retired.length()) add(retired.getString(index)) },
            )
        } catch (error: Exception) { throw IOException("Historique de lecture illisible", error) }
    }

    private fun persistWatchRecord(id: String, value: WatchRecord) {
        if (!watchDirectory.exists() && !watchDirectory.mkdirs()) throw IOException("Historique inaccessible")
        val json = JSONObject().apply {
            put("position_ms", value.state.positionMs); put("duration_ms", value.state.durationMs)
            put("watched", value.state.watched); put("last_played_at_ms", value.state.lastPlayedAtMs)
            value.sessionId?.let { put("session_id", it) }; put("sequence", value.sequence)
            put("retired_sessions", JSONArray(value.retiredSessions.toList()))
        }
        val file = watchFile(id)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
            // AtomicFile logs some rename failures instead of throwing: verify publication.
            verifyWatchPublication(bytes) { file.openRead().use { it.readBytes() } }
        } catch (error: Exception) {
            file.failWrite(output)
            throw IOException("Impossible d'enregistrer la progression", error)
        }
    }

    @Synchronized
    fun pruneWatchState(validIds: Set<String>) {
        val stale = (watch.all.keys + watchDirectory.listFiles().orEmpty().map { it.name.substringBefore('.') })
            .filter { it.matches(Regex("[0-9a-f]+")) && it !in validIds }.toSet()
        if (stale.isEmpty()) return
        watch.edit().also { editor -> stale.forEach(editor::remove) }.apply()
        watchStore.forget(stale) { watchFile(it).delete() }
    }

    private fun defaultServerName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank()) "Pluxy Android" else "Pluxy · $model"
    }

    companion object {
        private val watchStores = mutableMapOf<String, WatchStateStore>()
        const val DEFAULT_HTTP_PORT = 8420
        const val DEFAULT_DISCOVERY_PORT = 8421

        private const val PREFS = "pluxy_server_prefs"
        private const val WATCH_PREFS = "pluxy_server_watch"
        private const val KEY_TREE_URI = "media_tree_uri"
        private const val KEY_PHOTO_TREE_URI = "photo_tree_uri"
        private const val KEY_AUDIO_TREE_URI = "audio_tree_uri"
        private const val KEY_VIDEO_TREE_URIS = "media_tree_uris"
        private const val KEY_PHOTO_TREE_URIS = "photo_tree_uris"
        private const val KEY_AUDIO_TREE_URIS = "audio_tree_uris"
        private const val KEY_AUTO_DISCOVER_MEDIA = "auto_discover_media"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_TMDB = "tmdb_api_key"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_RUNNING_DESIRED = "running_desired"
    }
}
