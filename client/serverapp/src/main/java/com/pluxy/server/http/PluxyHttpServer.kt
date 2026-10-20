package com.pluxy.server.http

import android.content.Context
import android.net.Uri
import com.pluxy.server.serverApplicationVersion
import com.pluxy.server.LanAddressPolicy
import com.pluxy.server.NetworkUtils
import com.pluxy.server.ServerPreferences
import com.pluxy.server.WatchState
import com.pluxy.server.library.CatalogRevisionChanged
import com.pluxy.server.library.BrowseEntry
import com.pluxy.server.library.LibraryBrowseCatalog
import com.pluxy.server.library.LibraryQuery
import com.pluxy.server.library.MediaEntry
import com.pluxy.server.library.MediaLibrary
import com.pluxy.server.library.MediaSupport
import com.pluxy.server.library.MediaThumbnailer
import com.pluxy.server.library.MediaThumbnailer.Result
import com.pluxy.server.metadata.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Petit serveur HTTP/1.1 dédié à Pluxy. Il n'accepte qu'une requête par connexion,
 * ce qui garde le cycle de vie explicite et évite les sockets zombies en veille.
 */
class PluxyHttpServer(
    private val context: Context,
    private val preferences: ServerPreferences,
    private val library: MediaLibrary,
    private val metadata: TmdbRepository,
    private val scope: CoroutineScope,
    private val requestScan: () -> Unit,
) {
    private val version = serverApplicationVersion(context)
    private val thumbnails = MediaThumbnailer(context)
    private var segments: com.pluxy.server.library.AutomaticSegments? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: HttpWorkScheduler? = null
    private var inactivityMonitor: ScheduledExecutorService? = null
    private val activeClients = ConcurrentHashMap<Socket, ConnectionActivity>()
    @Volatile private var browseSnapshot: LibraryBrowseCatalog? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            receiveBufferSize = 1024 * 1024
            bind(InetSocketAddress("0.0.0.0", preferences.httpPort), 50)
        }
        val pool = HttpWorkScheduler()
        serverSocket = socket
        workers = pool
        running = true
        inactivityMonitor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "pluxy-http-inactivity").apply { isDaemon = true }
        }.apply {
            scheduleAtFixedRate({
                activeClients.forEach { (client, activity) ->
                    if (activity.expired(TimeUnit.SECONDS.toNanos(30))) runCatching { client.close() }
                }
            }, 2, 2, TimeUnit.SECONDS)
        }
        acceptThread = Thread({ acceptLoop(socket, pool) }, "pluxy-http-accept").apply {
            isDaemon = true
            start()
        }
        prepareSegments()
    }

    fun prepareSegments() {
        val analyzer = synchronized(this) {
            if (!running) return
            segments ?: com.pluxy.server.library.AutomaticSegments(context, library).also { segments = it }
        }
        analyzer.prepare()
    }

    fun playing(): Boolean = synchronized(this) { segments?.playing() == true }

    @Synchronized
    fun stop() {
        segments?.close(); segments = null
        running = false
        thumbnails.cancelPreviews()
        runCatching { serverSocket?.close() }
        serverSocket = null
        inactivityMonitor?.shutdownNow()
        inactivityMonitor = null
        activeClients.keys.toList().forEach { runCatching { it.close() } }
        activeClients.clear()
        acceptThread?.interrupt()
        acceptThread = null
        workers?.close()
        workers = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: HttpWorkScheduler) {
        while (running) {
            try {
                val client = socket.accept().apply {
                    soTimeout = 15_000
                    sendBufferSize = 1024 * 1024
                    tcpNoDelay = true
                }
                if (!LanAddressPolicy.isTrusted(client.inetAddress)) {
                    runCatching { client.close() }
                    continue
                }
                activeClients[client] = ConnectionActivity()
                try {
                    pool.control { dispatchConnection(client, pool) }
                } catch (_: RejectedExecutionException) {
                    activeClients.remove(client)
                    runCatching { client.close() }
                }
            } catch (_: SocketException) {
                if (running) continue else break
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun dispatchConnection(socket: Socket, pool: HttpWorkScheduler) {
        try {
            val request = HttpRequestParser.parse(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            if (request == null) { closeConnection(socket); return }
            activeClients[socket]?.progress()
            if (request.method == "GET" && (request.path.startsWith("/stream/") || request.path.startsWith("/image/thumbnail/"))) {
                try { pool.transfer { handleConnection(socket, request) } }
                catch (_: RejectedExecutionException) { rejectConnection(socket, HttpFailure(503, "Transferts occupés. Réessayez.")) }
            } else handleConnection(socket, request)
        } catch (error: HttpFailure) { rejectConnection(socket, error) }
        catch (_: IOException) { closeConnection(socket) }
        catch (error: Exception) { rejectConnection(socket, HttpFailure(500, error.message ?: "Erreur interne")) }
    }

    private fun closeConnection(socket: Socket) {
        activeClients.remove(socket)
        runCatching { socket.close() }
    }

    private fun trackedOutput(socket: Socket): OutputStream {
        val raw = object : FilterOutputStream(socket.getOutputStream()) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var cursor = offset
                val end = offset + length
                while (cursor < end) {
                    val count = minOf(64 * 1024, end - cursor)
                    out.write(bytes, cursor, count)
                    activeClients[socket]?.progress()
                    cursor += count
                }
            }
            override fun write(value: Int) { out.write(value); activeClients[socket]?.progress() }
        }
        return BufferedOutputStream(raw, 64 * 1024)
    }

    private fun rejectConnection(socket: Socket, error: HttpFailure) {
        try { trackedOutput(socket).also { sendJson(it, error.status, errorJson(error.message), false, error.headers); it.flush() } }
        catch (_: IOException) { }
        finally { closeConnection(socket) }
    }

    private fun handleConnection(socket: Socket, request: HttpRequest) {
        try {
            socket.use { client ->
                val output = trackedOutput(client)
                val headOnly = request.method == "HEAD"
                try {
                    route(request, output)
                } catch (e: HttpFailure) {
                    runCatching { sendJson(output, e.status, errorJson(e.message), headOnly, e.headers) }
                } catch (_: IOException) {
                    // Déconnexion normale du lecteur pendant un seek ou un changement de source.
                } catch (e: Exception) {
                    runCatching {
                        sendJson(output, 500, errorJson(e.message ?: "Erreur interne"), headOnly)
                    }
                } finally {
                    runCatching { output.flush() }
                }
            }
        } finally {
            activeClients.remove(socket)
        }
    }

    private fun route(request: HttpRequest, output: OutputStream) {
        val profile = request.headers["x-pluxy-profile"] ?: "default"
        try { preferences.validateProfile(profile) } catch (e: IllegalArgumentException) { throw HttpFailure(404, "Profil indisponible") }

        val headOnly = request.method == "HEAD"
        when {
            request.path == "/" && request.method in setOf("GET", "HEAD") -> {
                val address = NetworkUtils.primaryLanIp(context)?.let { "http://$it:${preferences.httpPort}" }
                    ?: "Wi-Fi indisponible"
                val html = """
                    <!doctype html><html lang="fr"><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body{font:16px sans-serif;background:#0b0e14;color:#f5f7fb;padding:32px;max-width:700px;margin:auto}b{color:#e5a00d}</style>
                    <h1>Pluxy Serveur Android</h1><p><b>Actif</b> · $address</p>
                    <p>${library.all().size} média(s) disponibles. Ouvrez Pluxy sur la télévision et choisissez ce serveur.</p>
                    <p>Réseau domestique de confiance : tous les appareils du LAN peuvent accéder aux médias sélectionnés.</p>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                sendBytes(output, 200, "text/html; charset=utf-8", html, headOnly)
            }
            request.path == "/api/profiles" && request.method in setOf("GET", "HEAD") ->
                sendJson(output, 200, JSONArray().apply { preferences.familyProfiles().forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) } }, headOnly)
            request.path == "/api/profiles" && request.method == "POST" -> {
                val value = try { preferences.addFamilyProfile(JSONObject(String(request.body, StandardCharsets.UTF_8)).optString("name")) }
                catch(e: IllegalArgumentException) { throw HttpFailure(422, e.message ?: "Nom invalide") }
                sendJson(output, 201, JSONObject().put("id", value.first).put("name", value.second), headOnly)
            }
            request.path == "/health" && request.method in setOf("GET", "HEAD") ->
                sendJson(output, 200, JSONObject().apply {
                    put("status", "ok"); put("app", "pluxy"); put("platform", "android")
                }, headOnly)

            request.path == "/api/server/info" && request.method in setOf("GET", "HEAD") ->
                sendJson(output, 200, serverInfo(), headOnly)

            request.path == "/api/library/items" && request.method in setOf("GET", "HEAD") -> {
                val array = JSONArray().apply {
                    library.all().forEach { item ->
                        put(item.publicJson(metadata.cached(item.id), watchState(item.id, profile)))
                    }
                }
                sendJson(output, 200, array, headOnly)
            }
            request.path == "/api/library/items/page" && request.method in setOf("GET", "HEAD") -> {
                val limit = request.query["limit"]?.toIntOrNull() ?: if ("limit" in request.query) throw HttpFailure(400, "limit invalide") else 500
                val offset = request.query["offset"]?.toIntOrNull() ?: if ("offset" in request.query) throw HttpFailure(400, "offset invalide") else 0
                val page = try { browseCatalog(profile).page(limit, offset, request.query["revision"],
                    LibraryQuery.fromParameters(request.query, request.queryValues)) }
                catch (error: CatalogRevisionChanged) { throw HttpFailure(409, error.message ?: "Bibliothèque modifiée") }
                catch (error: IllegalArgumentException) { throw HttpFailure(400, error.message ?: "Pagination invalide") }
                sendJson(output, 200, JSONObject().apply {
                    put("items", JSONArray().apply { page.items.forEach { put(browseJson(it)) } })
                    put("total", page.total); put("revision", page.revision)
                    put("next_offset", page.nextOffset ?: JSONObject.NULL)
                }, headOnly)
            }
            request.path == "/api/library/facets" && request.method in setOf("GET", "HEAD") -> {
                val query = try { LibraryQuery.fromParameters(request.query, request.queryValues) }
                catch (error: IllegalArgumentException) { throw HttpFailure(400, error.message ?: "Filtre invalide") }
                val catalog = browseCatalog(profile)
                val facets = catalog.facets(query.kind)
                sendJson(output, 200, JSONObject().apply {
                    put("genres", JSONArray(facets.genres)); put("years", JSONArray(facets.years))
                    put("resolutions", JSONArray(facets.resolutions)); put("total", facets.total)
                    put("revision", catalog.revision); put("query_supported", true)
                    put("counts", JSONObject(catalog.counts))
                }, headOnly)
            }
            request.path == "/api/library/home" && request.method in setOf("GET", "HEAD") -> {
                val catalog = browseCatalog(profile)
                sendJson(output, 200, JSONObject().apply {
                    put("items", JSONArray().apply { catalog.home().forEach { put(browseJson(it)) } })
                    put("counts", JSONObject(catalog.counts)); put("revision", catalog.revision)
                }, headOnly)
            }
            request.path == "/api/library/scan-status" && request.method in setOf("GET", "HEAD") ->
                sendJson(output, 200, JSONObject().apply {
                    put("scanning", library.isScanning); put("count", library.all().size)
                    put("metadata", JSONObject().put("message", metadata.status))
                }, headOnly)

            request.path == "/api/library/scan" && request.method == "POST" -> {
                requestScan()
                sendJson(output, 200, JSONObject().apply {
                    put("status", "scanning"); put("current_count", library.all().size)
                }, false)
            }
            request.path == "/api/library/refresh-metadata" && request.method == "POST" -> {
                scope.launch { metadata.enrichAll(library.all(), force = true) }
                sendJson(output, 200, JSONObject().put("status", "started"), false)
            }
            request.path.matches(Regex("^/api/library/items/[0-9a-f]+/chapters$")) && request.method in setOf("GET", "HEAD") -> {
                val item = requireItem(request.path.removeSuffix("/chapters").substringAfterLast('/'))
                val analyzer = synchronized(this) {
                    if (!running) throw HttpFailure(503, "Serveur arrêté")
                    segments ?: com.pluxy.server.library.AutomaticSegments(context, library).also { segments = it }
                }
                sendJson(output, 200, analyzer.request(item), headOnly)
            }
            request.path.matches(Regex("^/api/library/items/[0-9a-f]+$")) &&
                request.method in setOf("GET", "HEAD") -> {
                val item = requireItem(request.path.substringAfterLast('/'))
                sendJson(
                    output, 200,
                    item.publicJson(metadata.cached(item.id), watchState(item.id, profile)),
                    headOnly,
                )
            }
            request.path.matches(Regex("^/api/library/items/[0-9a-f]+/matches$")) && request.method in setOf("GET", "HEAD") -> {
                val item = requireItem(request.path.removeSuffix("/matches").substringAfterLast('/'))
                val query = request.query["q"].orEmpty().trim()
                if (query.length !in 1..160 || item.kind !in setOf("movie", "series")) throw HttpFailure(422, "Recherche invalide")
                val matches = try { runBlocking { metadata.searchMatches(item, query) } }
                catch(e: IllegalArgumentException) { throw HttpFailure(422, e.message ?: "TMDB non configuré") }
                catch(e: IOException) { throw HttpFailure(503, "TMDB est indisponible. Réessayez.") }
                sendJson(output, 200, matches, headOnly)
            }
            request.path.matches(Regex("^/api/library/items/[0-9a-f]+/match$")) && request.method == "POST" -> {
                val item = requireItem(request.path.removeSuffix("/match").substringAfterLast('/'))
                if (item.kind !in setOf("movie", "series")) throw HttpFailure(422, "Média non concerné")
                val id = JSONObject(String(request.body, StandardCharsets.UTF_8)).optInt("tmdb_id", 0)
                val related = if (item.kind == "series" && item.seriesKey != null) library.all().filter { it.kind == "series" && it.seriesKey == item.seriesKey } else listOf(item)
                val value = try { runBlocking { metadata.chooseMatch(item, id, related) } }
                catch(e: IllegalArgumentException) { throw HttpFailure(422, e.message ?: "Correspondance invalide") }
                catch(e: IOException) { throw HttpFailure(503, "Correspondance TMDB indisponible. Réessayez.") }
                sendJson(output, 200, value, headOnly)
            }
            request.path.matches(Regex("^/api/library/items/[0-9a-f]+/metadata$")) &&
                request.method in setOf("GET", "HEAD") -> {
                val id = request.path.removeSuffix("/metadata").substringAfterLast('/')
                val item = requireItem(id)
                // Un client LAN peut lire le cache mais ne force pas une requête TMDB
                // unitaire à chaque GET. Le rafraîchissement global est déjà sérialisé.
                val value = runBlocking { metadata.get(item, force = false) }
                sendJson(output, 200, value, headOnly)
            }
            request.path in setOf("/api/playback/decide", "/api/playback/diagnose") &&
                request.method == "POST" -> {
                val body = request.jsonBody()
                val item = requireItem(body.optString("item_id"))
                sendJson(output, 200, androidPlaybackDecision(item, body), false)
            }
            request.path.matches(Regex("^/api/playback/progress/[0-9a-f]+$")) -> {
                val id = request.path.substringAfterLast('/')
                requireItem(id)
                val state = if (request.method == "POST") {
                    val body = request.jsonBody()
                    val session = if (body.has("session_id")) body.opt("session_id") as? String
                        ?: throw HttpFailure(400, "session_id invalide") else null
                    val sequence = if (body.has("sequence")) {
                        val raw = body.opt("sequence")
                        if (raw !is Number || raw.toString().toLongOrNull() == null) throw HttpFailure(400, "sequence invalide")
                        raw.toLong()
                    } else null
                    try {
                        preferences.setWatchState(id, body.optLong("position_ms", 0), body.optLong("duration_ms", 0), session, sequence, profile)
                    } catch (error: IllegalArgumentException) {
                        throw HttpFailure(400, error.message ?: "Progression invalide")
                    } catch (_: IOException) {
                        throw HttpFailure(503, "Impossible d'enregistrer la progression. Réessayez.")
                    }
                } else if (request.method in setOf("GET", "HEAD")) {
                    watchState(id, profile)
                } else throw HttpFailure(405, "Méthode non autorisée")
                sendJson(output, 200, JSONObject().apply {
                    put("position_ms", state.positionMs)
                    put("duration_ms", state.durationMs)
                    put("watched", state.watched)
                }, headOnly)
            }
            request.path == "/api/settings/client" && request.method in setOf("GET", "HEAD") ->
                sendJson(output, 200, clientSettings(), headOnly)

            request.path == "/api/settings" && request.method in setOf("GET", "HEAD", "PATCH") -> {
                if (request.method == "PATCH") applySettingsPatch(request.jsonBody())
                sendJson(output, 200, serverSettings(), headOnly)
            }
            request.path.matches(Regex("^/stream/direct/[0-9a-f]+$")) &&
                request.method in setOf("GET", "HEAD") -> {
                streamDocument(output, request, requireItem(request.path.substringAfterLast('/')), null)
            }
            request.path.matches(Regex("^/api/library/items/[0-9a-f]+/preview$")) &&
                request.method in setOf("GET", "HEAD") -> {
                val item = requireItem(request.path.substringBeforeLast('/').substringAfterLast('/'))
                val position = request.query["position"]?.toDoubleOrNull()
                    ?: throw HttpFailure(400, "Position invalide")
                if (!position.isFinite() || position !in 0.0..604_800.0) throw HttpFailure(400, "Position invalide")
                when (val preview = thumbnails.preview(item, position)) {
                    is Result.Ready -> sendBytes(output, 200, "image/jpeg", preview.bytes, headOnly,
                        mapOf("Cache-Control" to "private, max-age=86400"))
                    Result.Busy -> sendBytes(output, 202, "image/jpeg", ByteArray(0), headOnly,
                        mapOf("Retry-After" to "1", "Cache-Control" to "no-store"))
                    Result.Unsupported -> throw HttpFailure(404, "Aperçu indisponible")
                }
            }
            request.path.matches(Regex("^/image/thumbnail/[0-9a-f]+$")) &&
                request.method in setOf("GET", "HEAD") -> {
                val item = requireItem(request.path.substringAfterLast('/'))
                if (item.kind !in setOf("photo", "movie", "series", "clip", "audio")) {
                    throw HttpFailure(404, "Miniature introuvable")
                }
                val bytes = when (val thumbnail = thumbnails.jpeg(item)) {
                    is Result.Ready -> thumbnail.bytes
                    Result.Busy -> throw HttpFailure(
                        503,
                        "Génération de miniatures occupée, réessayez.",
                        mapOf("Retry-After" to "1"),
                    )
                    Result.Unsupported -> throw HttpFailure(
                        415,
                        "Format de miniature non pris en charge par cet Android",
                    )
                }
                sendBytes(
                    output = output,
                    status = 200,
                    contentType = "image/jpeg",
                    body = bytes,
                    headOnly = headOnly,
                    headers = mapOf(
                        "Cache-Control" to "private, max-age=86400",
                        "ETag" to "\"thumb-${item.id}-${item.modifiedAtMs}-${item.size}\"",
                    ),
                )
            }
            request.path.matches(Regex("^/stream/remux/[0-9a-f]+$")) &&
                request.method in setOf("GET", "HEAD") -> {
                val id = request.path.substringAfterLast('/')
                requireItem(id)
                sendRedirect(output, "/stream/direct/$id", headOnly)
            }
            request.path.matches(Regex("^/stream/subs/[0-9a-f]+/\\d+$")) &&
                request.method in setOf("GET", "HEAD") -> {
                val parts = request.path.trim('/').split('/')
                val item = requireItem(parts[2])
                val index = parts[3].toIntOrNull() ?: -1
                val subtitle = item.subtitles.getOrNull(index)
                    ?: throw HttpFailure(404, "Sous-titre introuvable")
                streamDocument(output, request, item, subtitle.uri)
            }
            request.path.matches(Regex("^/stream/hls/[0-9a-f]+$")) && request.method == "DELETE" ->
                sendJson(output, 200, JSONObject().put("stopped", request.path.substringAfterLast('/')), false)

            request.path.startsWith("/stream/hls/") ->
                throw HttpFailure(501, "Le serveur Android ne transcode pas. Utilisez le serveur PC pour ce fichier.")

            else -> throw HttpFailure(404, "Ressource introuvable")
        }
    }

    private fun streamDocument(
        output: OutputStream,
        request: HttpRequest,
        item: MediaEntry,
        overrideUri: String?,
    ) {
        if (!item.available) throw HttpFailure(503, "Source indisponible. Rétablissez son accès puis relancez le scan.")
        val uri = Uri.parse(overrideUri ?: item.uri)
        val descriptor = try { context.contentResolver.openFileDescriptor(uri, "r") }
        catch (_: SecurityException) { throw HttpFailure(503, "Autorisation de la source perdue. Sélectionnez-la à nouveau.") }
        catch (_: IOException) { throw HttpFailure(503, "La source ne répond pas. Réessayez.") }
            ?: throw HttpFailure(404, "Fichier inaccessible")
        try {
            val descriptorSize = descriptor.statSize.takeIf { it >= 0 }
            val total = descriptorSize ?: item.size.takeIf { overrideUri == null && it > 0 }
                ?: throw HttpFailure(500, "Le fournisseur Android ne communique pas la taille du fichier")
            val mediaTag = "${item.id}-${item.modifiedAtMs}-$total"
            // The index does not track subtitle modification dates, so its validator is weak.
            val etag = if (overrideUri == null) "\"$mediaTag\"" else {
                "W/\"$mediaTag-sub-${item.subtitles.indexOfFirst { it.uri == overrideUri }}\""
            }
            val range = try {
                ByteRange.forRequest(request.method, request.headers, total, etag)
            } catch (e: IllegalArgumentException) {
                throw HttpFailure(416, e.message ?: "Range invalide", mapOf("Content-Range" to "bytes */$total"))
            }
            val start = range?.start ?: 0L
            val end = range?.endInclusive ?: (total - 1).coerceAtLeast(-1)
            val length = if (total == 0L) 0L else end - start + 1
            val status = if (range == null) 200 else 206
            val contentType = if (overrideUri == null) mediaMime(item) else subtitleMime(overrideUri)
            val headers = linkedMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to length.toString(),
                "Cache-Control" to "no-store",
                "ETag" to etag,
                // Interop DLNA : OP=01 annonce le seek par octets, CI=0 confirme que
                // Pluxy sert l'original sans conversion. Les lecteurs HTTP ordinaires
                // ignorent proprement ces en-têtes.
                "transferMode.dlna.org" to if (item.kind == "photo") "Interactive" else "Streaming",
                "contentFeatures.dlna.org" to
                    "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            )
            if (range != null) headers["Content-Range"] = "bytes $start-$end/$total"
            if (request.method == "HEAD" || length == 0L) {
                writeHeaders(output, status, contentType, headers)
                return
            }

            val input = FileInputStream(descriptor.fileDescriptor)
            val playback = if (overrideUri == null && item.kind != "photo") synchronized(this) { segments } else null
            playback?.playbackStarted()
            try {
                val positioned = runCatching { input.channel.position(start); true }.getOrDefault(false)
                if (!positioned) skipFully(input, start)
                writeHeaders(output, status, contentType, headers)
                copyExactly(input, output, length)
            } finally {
                runCatching { input.close() }
                playback?.playbackFinished()
            }
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private fun serverInfo(): JSONObject {
        val ip = NetworkUtils.primaryLanIp(context).orEmpty()
        return JSONObject().apply {
            put("app", "pluxy")
            put("name", preferences.serverName)
            put("ip", ip)
            put("port", preferences.httpPort)
            put("version", version)
            put("platform", "android")
            put("primary_ip", ip)
            put("ips", JSONArray(NetworkUtils.allLanIps()))
            put("base_url", if (ip.isBlank()) "" else "http://$ip:${preferences.httpPort}")
            put("discovery_port", preferences.discoveryPort)
            put("ffmpeg", false)
            put("ffprobe", false)
            put("transcoding_available", false)
            put("scanning", library.isScanning)
            put("media_count", library.all().size)
            put("library_query_supported", true)
        }
    }

    @Synchronized
    private fun browseCatalog(profile: String = "default"): LibraryBrowseCatalog {
        repeat(3) {
            val scan = library.snapshot()
            val metadataRevision = metadata.revision
            val watchRevision = preferences.watchRevision
            val revision = "${scan.revision}:$metadataRevision:$watchRevision:$profile"
            browseSnapshot?.takeIf { it.revision == revision }?.let { return it }
            val next = LibraryBrowseCatalog(revision, scan.items.map { item ->
                BrowseEntry(item,
                    if (item.kind in setOf("movie", "series")) metadata.facts(item.id) else com.pluxy.server.library.BrowseMetadata(),
                    watchState(item.id, profile))
            })
            if (scan.revision == library.snapshot().revision && metadataRevision == metadata.revision &&
                watchRevision == preferences.watchRevision) {
                browseSnapshot = next
                return next
            }
        }
        throw HttpFailure(503, "La bibliothèque est en cours de mise à jour. Réessayez.")
    }

    private fun browseJson(entry: BrowseEntry): JSONObject = entry.media.publicJson(metadata.cached(entry.media.id), entry.watch).apply {
        put("group_count", entry.groupCount)
        put("season_count", entry.seasonCount)
        put("year", entry.year ?: JSONObject.NULL)
        put("genres", JSONArray(entry.metadata.genres))
        put("metadata_title", entry.metadata.title ?: JSONObject.NULL)
    }

    private fun clientSettings(): JSONObject = JSONObject().apply {
        put("buffer", JSONObject().apply {
            put("min_buffer_ms", 60_000)
            put("max_buffer_ms", 120_000)
            put("buffer_for_playback_ms", 2_500)
            put("buffer_for_playback_after_rebuffer_ms", 15_000)
            put("target_buffer_bytes_mb", 600)
            put("back_buffer_ms", 30_000)
        })
        put("subtitles", JSONObject().apply {
            put("external_extensions", JSONArray(listOf(".srt", ".vtt", ".ass", ".ssa")))
            put("prefer_external", true)
            put("burn_in", false)
        })
        put("server_name", preferences.serverName)
        put("server_kind", "android")
        put("transcoding_available", false)
        put("direct_stream_available", false)
        put("conversion_profiles", JSONArray())
        put("conversion_unavailable_reason", "Ce serveur Android transmet la vidéo originale. La conversion nécessite un serveur PC Pluxy.")
    }

    /**
     * Diagnostic honnête du serveur Android : il ne promet jamais un transcodage qu'il
     * ne sait pas réaliser. L'URL directe reste fournie afin que le mode utilisateur
     * « Direct Play uniquement » puisse tenter la lecture malgré un profil prudent.
     */
    private fun androidPlaybackDecision(item: MediaEntry, body: JSONObject): JSONObject {
        val caps = body.optJSONObject("capabilities") ?: JSONObject()
        val reasons = mutableListOf("Serveur Android : fichier original, sans réencodage")
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val containers = caps.optJSONArray("supported_containers")?.stringSet().orEmpty()
        val videoCodecs = caps.optJSONArray("supported_video_codecs")?.stringSet().orEmpty()
        val audioCodecs = caps.optJSONArray("supported_audio_codecs")?.stringSet().orEmpty()
        val codec = item.videoCodec?.lowercase()

        if (containers.isNotEmpty() && item.container.lowercase() !in containers) {
            blockers += "Conteneur ${item.container.uppercase()} non validé par ce téléviseur"
        }
        if (codec != null) {
            val codecSupported = when (codec) {
                "h264", "avc" -> caps.optBoolean("supports_h264", "h264" in videoCodecs)
                "hevc", "h265" -> caps.optBoolean("supports_hevc", "hevc" in videoCodecs) &&
                    (!item.isHdr || caps.optBoolean("supports_hevc_10bit", false))
                "av1", "av01" -> caps.optBoolean("supports_av1", "av1" in videoCodecs)
                else -> codec in videoCodecs
            }
            if (!codecSupported) blockers += "Codec vidéo ${codec.uppercase()} non décodable"
        }
        val maxHeight = caps.optInt("max_video_height", 0)
        if (maxHeight > 0 && (item.height ?: 0) > maxHeight) {
            blockers += "Résolution ${item.height}p supérieure au profil ${maxHeight}p"
        }
        if (item.isHdr && !caps.optBoolean("supports_hdr", caps.optBoolean("supports_hdr10", false))) {
            blockers += "Image HDR sur un écran déclaré SDR"
        }
        item.audioCodec?.lowercase()?.let { audio ->
            if (audioCodecs.isNotEmpty() && audio !in audioCodecs) {
                blockers += "Audio ${audio.uppercase()} non décodable ou non passable vers l'ampli"
            }
        }
        val maxChannels = caps.optInt("max_audio_channels", 0)
        if (maxChannels > 0 && (item.audioChannels ?: 0) > maxChannels) {
            blockers += "Audio ${item.audioChannels} canaux supérieur au profil $maxChannels canaux"
        }
        val maxBitrate = caps.optInt("max_bitrate_mbps", 0)
        val sourceBitrate = if (item.durationSeconds > 0 && item.size > 0) {
            item.size * 8.0 / item.durationSeconds / 1_000_000.0
        } else 0.0
        if (maxBitrate > 0 && sourceBitrate > maxBitrate) {
            warnings += "Débit estimé ${sourceBitrate.toInt()} Mbps supérieur au plafond appris $maxBitrate Mbps"
        }

        reasons += blockers
        reasons += warnings
        val playable = blockers.isEmpty()
        val severity = when {
            !playable -> "unsupported"
            warnings.isNotEmpty() -> "warning"
            else -> "success"
        }
        val summary = when {
            !playable -> "Adaptation nécessaire, indisponible sur le serveur Android"
            warnings.isNotEmpty() -> "Direct Play possible avec tampon renforcé"
            else -> "Direct Play · qualité originale"
        }
        return JSONObject().apply {
            put("mode", "direct_play")
            put("reasons", JSONArray(reasons))
            put("compat", false)
            put("video_action", "copy")
            put("audio_action", "copy")
            put("tone_map", false)
            put("target_bitrate_mbps", JSONObject.NULL)
            put("container", item.container)
            put("delivery", "direct")
            put("stream_url", "/stream/direct/${item.id}")
            put("playable", playable)
            put("severity", severity)
            put("summary", summary)
            put("profile_id", caps.optString("profile_id").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("profile_name", caps.optString("profile_name").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        }
    }

    private fun serverSettings(): JSONObject = JSONObject().apply {
        put("transcoding", JSONObject().apply {
            put("hardware_acceleration", false)
            put("preset", "none")
            put("force_transcode", false)
            put("prefer_direct_play", true)
            put("max_bitrate_mbps", 50)
            put("hdr_tone_mapping", "never")
        })
        put("audio", JSONObject().apply {
            put("force_audio_passthrough", true)
            put("target_codec", "aac")
            put("target_channels", 2)
            put("bitrate_kbps", 256)
            put("downmix_lossless", false)
        })
        put("network", JSONObject().apply {
            put("direct_play_enabled", true)
            put("direct_stream_enabled", false)
            put("hls_segment_duration", 3)
        })
        put("client_buffer", JSONObject().apply {
            put("min_buffer_ms", 60_000)
            put("max_buffer_ms", 120_000)
        })
        put("server", JSONObject().apply {
            put("name", preferences.serverName)
            put("autostart", preferences.autoStart)
            put("platform", "android")
            put("transcoding_available", false)
        })
    }

    private fun applySettingsPatch(patch: JSONObject) {
        patch.optJSONObject("server")?.let { server ->
            if (server.has("autostart")) preferences.autoStart = server.optBoolean("autostart")
        }
        // Les options NVENC/remux sont volontairement ignorées : elles ne sont pas
        // réalisables de façon fiable en arrière-plan sur Android.
    }

    private fun watchState(id: String, profile: String = "default"): WatchState = try { preferences.watchState(id, profile) }
    catch (_: IOException) { throw HttpFailure(503, "Historique de lecture indisponible. Réessayez.") }

    private fun requireItem(id: String): MediaEntry = library.get(id)
        ?: throw HttpFailure(404, "Média introuvable")

    private fun sendJson(
        output: OutputStream,
        status: Int,
        json: Any,
        headOnly: Boolean,
        headers: Map<String, String> = emptyMap(),
    ) {
        sendBytes(
            output,
            status,
            "application/json; charset=utf-8",
            json.toString().toByteArray(StandardCharsets.UTF_8),
            headOnly,
            headers,
        )
    }

    private fun sendBytes(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
        headers: Map<String, String> = emptyMap(),
    ) {
        writeHeaders(output, status, contentType, headers + ("Content-Length" to body.size.toString()))
        if (!headOnly) output.write(body)
    }

    private fun sendRedirect(output: OutputStream, location: String, headOnly: Boolean) {
        sendBytes(output, 307, "text/plain; charset=utf-8", ByteArray(0), headOnly, mapOf("Location" to location))
    }

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        contentType: String,
        headers: Map<String, String>,
    ) {
        val safeHeaders = linkedMapOf(
            "Content-Type" to contentType,
            "Connection" to "close",
            "Server" to "Pluxy-Android/$version",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )
        safeHeaders.putAll(headers)
        val text = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            safeHeaders.forEach { (key, value) ->
                append(key.replace(Regex("[\\r\\n:]"), ""))
                    .append(": ")
                    .append(value.replace(Regex("[\\r\\n]"), ""))
                    .append("\r\n")
            }
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun skipFully(input: InputStream, amount: Long) {
        var remaining = amount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw IOException("Fin de fichier pendant le seek")
            }
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, amount: Long) {
        val buffer = ByteArray(1024 * 1024)
        var remaining = amount
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Fin de fichier avant la taille annoncée")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun mediaMime(item: MediaEntry): String = item.mimeType?.takeIf {
        it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/")
    } ?: MediaSupport.mimeFor(item.kind, item.container)

    private fun subtitleMime(uri: String): String = when (uri.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "text/plain; charset=utf-8"
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 307 -> "Temporary Redirect"
        400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        202 -> "Accepted"
        409 -> "Conflict"
        415 -> "Unsupported Media Type"
        413 -> "Payload Too Large"; 414 -> "URI Too Long"; 416 -> "Range Not Satisfiable"
        417 -> "Expectation Failed"
        431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
        501 -> "Not Implemented"; 503 -> "Service Unavailable"; else -> "Error"
    }

    private fun errorJson(message: String?) = JSONObject().put("detail", message ?: "Erreur")

}

private fun HttpRequest.jsonBody(): JSONObject {
    if (body.isEmpty()) return JSONObject()
    return runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }
        .getOrElse { throw HttpFailure(400, "JSON invalide") }
}

private fun JSONArray.stringSet(): Set<String> = buildSet {
    for (i in 0 until length()) {
        optString(i).trim().lowercase().takeIf { it.isNotBlank() }?.let(::add)
    }
}
