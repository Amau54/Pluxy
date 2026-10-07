package com.pluxy.server.library

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Index SAF persistant. Aucun chemin absolu n'est exposé à la télévision. */
class MediaLibrary(
    private val context: Context,
    private val onStateChanged: (scanning: Boolean, count: Int, error: String?) -> Unit,
) {
    private val indexFile = AtomicFile(File(context.filesDir, "library_index.json"))
    private val scanning = AtomicBoolean(false)

    @Volatile
    private var catalog = MediaCatalog(loadIndex())

    val isScanning: Boolean get() = scanning.get()

    fun all(): List<MediaEntry> = catalog.items

    fun get(id: String): MediaEntry? = catalog.get(id)

    internal fun snapshot(): MediaCatalog = catalog

    fun page(limit: Int, offset: Int, revision: String?): MediaPage = catalog.page(limit, offset, revision)

    fun scanAsync(
        scope: CoroutineScope,
        sources: List<LibrarySource>,
        afterScan: (items: List<MediaEntry>, complete: Boolean) -> Unit = { _, _ -> },
    ): Job? {
        if (!scanning.compareAndSet(false, true)) return null
        onStateChanged(true, catalog.items.size, null)
        var error: String? = null
        return scope.launch(Dispatchers.IO) {
            try {
                val result = scan(sources)
                val next = MediaCatalog(result.items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }))
                saveIndex(next.items)
                catalog = next
                // A missing source or a scan limit is not proof that its media were deleted.
                afterScan(next.items, result.warning == null)
                error = result.warning
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                error = "Autorisation d'une source perdue. Sélectionnez-la à nouveau."
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "Échec du scan de la bibliothèque."
            }
        }.also { job ->
            // Also runs if the scope was cancelled before the coroutine could start.
            job.invokeOnCompletion {
                scanning.set(false)
                onStateChanged(false, catalog.items.size, error)
            }
        }
    }

    private suspend fun scan(sources: List<LibrarySource>): ScanResult = withContext(Dispatchers.IO) {
        val selected = sources
            .filter { it.treeUri.isNotBlank() }
            .distinctBy { it.kind to it.treeUri }
        if (selected.isEmpty()) return@withContext ScanResult(emptyList())
        val previous = catalog.items.associateBy { it.uri }
        val discoveredAt = System.currentTimeMillis()
        val found = ArrayList<MediaEntry>()
        val visited = HashSet<String>()
        val seenFiles = HashSet<String>()
        val warnings = mutableListOf<String>()
        val incomplete = mutableSetOf<String>()
        var usableSourceCount = 0
        var limitReached = false

        sourceLoop@ for (source in selected) {
            currentCoroutineContext().ensureActive()
            if (source.isAutomatic) {
                try {
                    scanMediaStoreSource(
                        source = source,
                        previous = previous,
                        found = found,
                        seenFiles = seenFiles,
                        discoveredAt = discoveredAt,
                    )
                    usableSourceCount++
                    if (found.size >= MAX_MEDIA_ITEMS) {
                        limitReached = true
                        break@sourceLoop
                    }
                } catch (_: SecurityException) {
                    warnings += "Autorisez l'accès aux médias pour la détection automatique."
                    incomplete += source.identity()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    warnings += "La source multimédia ne répond pas. Réessayez le scan."
                    incomplete += source.identity()
                }
                continue
            }
            val root = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(source.treeUri))
                    ?.takeIf { it.exists() && it.isDirectory && it.canRead() }
            }.getOrNull()
            if (root == null) {
                incomplete += source.identity()
                warnings += when (source.kind) {
                    LibrarySourceKind.VIDEO -> "Le dossier vidéos n'est plus accessible."
                    LibrarySourceKind.PHOTO -> "Le dossier photos n'est plus accessible."
                    LibrarySourceKind.AUDIO -> "Le dossier musique n'est plus accessible."
                }
                continue
            }
            usableSourceCount++
            val stack = ArrayDeque<DirectoryNode>()
            stack.add(DirectoryNode(root, emptyList(), 0))

            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val node = stack.removeLast()
                if (!visited.add("${source.kind}:${node.directory.uri}")) continue
                if (visited.size > MAX_DIRECTORY_COUNT) {
                    throw IllegalStateException(
                        "Trop de dossiers dans la sélection (maximum $MAX_DIRECTORY_COUNT).",
                    )
                }
                val children = try {
                    if (!node.directory.canRead()) throw SecurityException("Dossier inaccessible")
                    listChildren(node.directory)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SecurityException) {
                    incomplete += source.identity()
                    warnings += "Autorisation d'un sous-dossier perdue."
                    emptyList()
                } catch (_: Exception) {
                    incomplete += source.identity()
                    warnings += "Un dossier n'a pas pu être analysé. Réessayez le scan."
                    emptyList()
                }
                val sidecars = if (source.kind == LibrarySourceKind.VIDEO) {
                    children.filter {
                        it.isFile && MediaSupport.extension(it.name) in SUBTITLE_EXTENSIONS
                    }
                } else emptyList()

                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (child.isDirectory) {
                        if (node.depth < MAX_SCAN_DEPTH) {
                            stack.add(
                                DirectoryNode(
                                    child,
                                    node.ancestors + child.name.orEmpty(),
                                    node.depth + 1,
                                ),
                            )
                        } else {
                            incomplete += source.identity()
                            if ("Profondeur maximale de $MAX_SCAN_DEPTH dossiers atteinte." !in warnings) {
                                warnings += "Profondeur maximale de $MAX_SCAN_DEPTH dossiers atteinte."
                            }
                        }
                        continue
                    }
                    val ext = MediaSupport.extension(child.name)
                    if (!child.isFile || !MediaSupport.supports(source.kind, ext)) continue
                    val uriValue = child.uri.toString()
                    if (!seenFiles.add(uriValue)) continue
                    if (found.size >= MAX_MEDIA_ITEMS) {
                        limitReached = true
                        break@sourceLoop
                    }

                    val fileName = child.name ?: "Média.$ext"
                    val size = child.length().takeIf { it > 0 } ?: querySize(child.uri)
                    val modified = child.lastModified().coerceAtLeast(0)
                    val old = previous[uriValue]
                    var parsed = if (source.kind == LibrarySourceKind.VIDEO) {
                        VideoClassifier.parse(fileName, listOf(root.name.orEmpty(), source.title) + node.ancestors, source.category, old?.durationSeconds ?: 0.0, old?.embeddedTitle)
                    } else null
                    val subs = if (source.kind == LibrarySourceKind.VIDEO) {
                        matchingSubtitles(fileName, sidecars)
                    } else emptyList()
                    val oldMatchesSource = when (source.kind) {
                        LibrarySourceKind.VIDEO -> old?.kind in setOf("movie", "series", "clip")
                        else -> old?.kind == source.kind.publicKind
                    }

                    if (old != null && oldMatchesSource && old.size == size &&
                        old.modifiedAtMs == modified && old.probeRevision >= requiredProbeRevision(source.kind)
                    ) {
                        found += old.copy(
                            sourceKey = source.identity(), available = true,
                            fileName = fileName,
                            title = parsed?.title ?: old.title,
                            parsedYear = parsed?.year,
                            container = ext,
                            mimeType = child.type,
                            kind = parsed?.kind ?: source.kind.publicKind,
                            seriesName = parsed?.seriesName,
                            seriesKey = parsed?.seriesKey,
                            season = parsed?.season,
                            episode = parsed?.episode,
                            folder = displayFolder(source, node.ancestors),
                            folderId = folderIdentity(source.identity(), node.directory.uri.toString()),
                            relativePath = (node.ancestors + fileName).joinToString("/"),
                            sourceName = source.title.ifBlank { root.name ?: "Photos" },
                            subtitles = subs,
                        )
                        continue
                    }

                    val probe = probe(child.uri, fileName, source.kind)
                    if (source.kind == LibrarySourceKind.VIDEO) parsed = VideoClassifier.parse(fileName,
                        listOf(root.name.orEmpty(), source.title) + node.ancestors, source.category, probe.durationSeconds, probe.title)
                    found += MediaEntry(
                        id = stableId(uriValue),
                        uri = uriValue,
                        fileName = fileName,
                        title = parsed?.title ?: probe.title ?: displayTitle(fileName),
                        parsedYear = parsed?.year,
                        container = ext,
                        mimeType = child.type,
                        size = size,
                        modifiedAtMs = modified,
                        addedAtMs = old?.addedAtMs ?: discoveredAt,
                        capturedAtMs = probe.capturedAtMs,
                        embeddedTitle = probe.title,
                        durationSeconds = probe.durationSeconds,
                        width = probe.width,
                        height = probe.height,
                        videoCodec = probe.videoCodec,
                        audioCodec = probe.audioCodec,
                        audioChannels = probe.audioChannels,
                        isHdr = probe.isHdr,
                        kind = parsed?.kind ?: source.kind.publicKind,
                        seriesName = parsed?.seriesName,
                        seriesKey = parsed?.seriesKey,
                        season = parsed?.season,
                        episode = parsed?.episode,
                        artist = probe.artist,
                        album = probe.album,
                        folder = displayFolder(source, node.ancestors),
                        folderId = folderIdentity(source.identity(), node.directory.uri.toString()),
                        relativePath = (node.ancestors + fileName).joinToString("/"),
                            sourceName = source.title.ifBlank { root.name ?: "Photos" },
                        subtitles = subs,
                        sourceKey = source.identity(),
                        discNumber = probe.discNumber, trackNumber = probe.trackNumber,
                    )
                }
            }
        }
        if (usableSourceCount == 0) warnings += "Aucune source accessible. Les médias précédents sont conservés comme indisponibles."
        if (limitReached) {
            warnings += "Limite de $MAX_MEDIA_ITEMS médias atteinte."
            incomplete += selected.map { it.identity() }
        }
        val merged = ScanMergePolicy.merge(previous.values.toList(), found, selected, incomplete, MAX_MEDIA_ITEMS)
        ScanResult(merged, warnings.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    private fun displayFolder(source: LibrarySource, ancestors: List<String>): String? =
        listOfNotNull(source.title.trim().takeIf(String::isNotBlank))
            .plus(ancestors.filter(String::isNotBlank))
            .joinToString(" / ")
            .ifBlank { null }

    /** DocumentFile.listFiles swallows provider errors and would turn denial into deletion. */
    private suspend fun listChildren(directory: DocumentFile): List<DocumentFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directory.uri,
            DocumentsContract.getDocumentId(directory.uri))
        val cursor = context.contentResolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?: throw IOException("Le fournisseur de dossiers ne répond pas")
        return cursor.use {
            buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(directory.uri, cursor.getString(0))
                    add(DocumentFile.fromSingleUri(context, uri) ?: throw IOException("Document inaccessible"))
                }
            }
        }
    }

    private suspend fun scanMediaStoreSource(
        source: LibrarySource,
        previous: Map<String, MediaEntry>,
        found: MutableList<MediaEntry>,
        seenFiles: MutableSet<String>,
        discoveredAt: Long,
    ) {
        val permission = AutomaticMediaPermission.required(Build.VERSION.SDK_INT, source.kind)
        requireAutomaticMediaPermission(permission)
        val collection = when (source.kind) {
            LibrarySourceKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            LibrarySourceKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            LibrarySourceKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (source.kind == LibrarySourceKind.PHOTO) add(MediaStore.Images.ImageColumns.DATE_TAKEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING}=0"
        } else null
        val cursor = context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        ) ?: throw IllegalStateException("La source multimédia ne répond pas.")
        cursor.use {
            while (cursor.moveToNext() && found.size < MAX_MEDIA_ITEMS) {
                currentCoroutineContext().ensureActive()
                val id = cursor.long(MediaStore.MediaColumns._ID) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                val uriValue = uri.toString()
                if (!seenFiles.add(uriValue)) continue
                val fileName = cursor.string(MediaStore.MediaColumns.DISPLAY_NAME)
                    ?.takeIf { it.isNotBlank() } ?: "Média"
                val ext = MediaSupport.extension(fileName)
                if (!MediaSupport.supports(source.kind, ext)) continue
                val size = cursor.long(MediaStore.MediaColumns.SIZE)?.coerceAtLeast(0) ?: 0
                val modified = (cursor.long(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0)
                    .coerceAtLeast(0) * 1_000L
                val mimeType = cursor.string(MediaStore.MediaColumns.MIME_TYPE)
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.string(MediaStore.MediaColumns.RELATIVE_PATH)?.trim('/')?.ifBlank { null }
                } else null
                val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.string(MediaStore.MediaColumns.VOLUME_NAME).orEmpty() else ""
                val folderId = folderIdentity("${source.identity()}:$volume", folder.orEmpty())
                val old = previous[uriValue]
                var parsed = if (source.kind == LibrarySourceKind.VIDEO) {
                    VideoClassifier.parse(fileName, folder?.split('/')?.filter(String::isNotBlank).orEmpty(), source.category, old?.durationSeconds ?: 0.0, old?.embeddedTitle)
                } else null
                val oldMatchesSource = when (source.kind) {
                    LibrarySourceKind.VIDEO -> old?.kind in setOf("movie", "series", "clip")
                    else -> old?.kind == source.kind.publicKind
                }
                if (old != null && oldMatchesSource && old.size == size &&
                    old.modifiedAtMs == modified && old.probeRevision >= requiredProbeRevision(source.kind)
                ) {
                    found += old.copy(
                        sourceKey = source.identity(), available = true,
                        fileName = fileName,
                        title = parsed?.title ?: old.title,
                        parsedYear = parsed?.year,
                        container = ext,
                        mimeType = mimeType,
                        kind = parsed?.kind ?: source.kind.publicKind,
                        seriesName = parsed?.seriesName,
                        seriesKey = parsed?.seriesKey,
                        season = parsed?.season,
                        episode = parsed?.episode,
                        folder = folder,
                        folderId = folderId,
                        relativePath = listOfNotNull(folder, fileName).joinToString("/"),
                        sourceName = if (volume.isBlank() || volume == "external_primary") "Téléphone" else "Stockage $volume",
                        sourceId = folderIdentity("${source.identity()}:$volume", ""),
                    )
                    continue
                }
                val probe = probe(uri, fileName, source.kind,
                    cursor.long(MediaStore.Images.ImageColumns.DATE_TAKEN)?.takeIf { it > 0 })
                if (source.kind == LibrarySourceKind.VIDEO) parsed = VideoClassifier.parse(fileName,
                    folder?.split('/')?.filter(String::isNotBlank).orEmpty(), source.category, probe.durationSeconds, probe.title)
                found += MediaEntry(
                    id = stableId(uriValue),
                    uri = uriValue,
                    fileName = fileName,
                    title = parsed?.title ?: probe.title ?: displayTitle(fileName),
                    parsedYear = parsed?.year,
                    container = ext,
                    mimeType = mimeType,
                    size = size,
                    modifiedAtMs = modified,
                    addedAtMs = old?.addedAtMs ?: discoveredAt,
                    capturedAtMs = probe.capturedAtMs,
                        embeddedTitle = probe.title,
                    durationSeconds = probe.durationSeconds,
                    width = probe.width,
                    height = probe.height,
                    videoCodec = probe.videoCodec,
                    audioCodec = probe.audioCodec,
                    audioChannels = probe.audioChannels,
                    isHdr = probe.isHdr,
                    kind = parsed?.kind ?: source.kind.publicKind,
                    seriesName = parsed?.seriesName,
                    seriesKey = parsed?.seriesKey,
                    season = parsed?.season,
                    episode = parsed?.episode,
                    artist = probe.artist,
                    album = probe.album,
                    folder = folder,
                    folderId = folderId,
                    relativePath = listOfNotNull(folder, fileName).joinToString("/"),
                        sourceName = if (volume.isBlank() || volume == "external_primary") "Téléphone" else "Stockage $volume",
                        sourceId = folderIdentity("${source.identity()}:$volume", ""),
                    subtitles = emptyList(),
                    sourceKey = source.identity(),
                    discNumber = probe.discNumber, trackNumber = probe.trackNumber,
                )
            }
        }
        // Revocation during the query must also preserve unseen cached entries/history.
        requireAutomaticMediaPermission(permission)
    }

    private fun requireAutomaticMediaPermission(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Accès multimédia incomplet : $permission")
        }
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.long(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun matchingSubtitles(mediaName: String, candidates: List<DocumentFile>): List<SubtitleEntry> {
        val stem = mediaName.substringBeforeLast('.').lowercase(Locale.ROOT)
        return candidates.mapNotNull { sub ->
            val name = sub.name ?: return@mapNotNull null
            val subStem = name.substringBeforeLast('.').lowercase(Locale.ROOT)
            if (subStem != stem && !subStem.startsWith("$stem.")) return@mapNotNull null
            val format = extension(name)
            val suffix = subStem.removePrefix(stem).trim('.', ' ', '-', '_')
            SubtitleEntry(
                uri = sub.uri.toString(),
                name = name,
                format = format,
                language = suffix.takeIf { it in LANGUAGE_CODES },
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun probe(uri: Uri, fileName: String, sourceKind: LibrarySourceKind, capturedAtHint: Long? = null): ProbeResult {
        // Read only image headers once per new/modified photo; never decode a bitmap during a scan.
        if (sourceKind == LibrarySourceKind.PHOTO) return ProbeResult(capturedAtMs = capturedAtHint ?: photoTakenAt(uri))
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.takeIf { it > 0 }
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.takeIf { it > 0 }
            val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                ?.toIntOrNull()
            val hdrByTransfer = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                transfer == MediaFormat.COLOR_TRANSFER_HLG
            val hdrByName = Regex("(?i)\\b(?:hdr10\\+?|hdr|dovi|dolby[ ._-]?vision|hlg)\\b")
                .containsMatchIn(fileName)
            val trackInfo = extractTrackInfo(uri)
            ProbeResult(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotBlank() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotBlank() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotBlank() },
                durationSeconds = durationMs / 1000.0,
                width = width,
                height = height,
                videoCodec = if (sourceKind == LibrarySourceKind.VIDEO) trackInfo.videoCodec else null,
                audioCodec = trackInfo.audioCodec,
                audioChannels = trackInfo.audioChannels,
                isHdr = sourceKind == LibrarySourceKind.VIDEO && (hdrByTransfer || hdrByName),
                discNumber = audioNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                trackNumber = audioNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
            )
        } catch (_: Exception) {
            ProbeResult(
                isHdr = sourceKind == LibrarySourceKind.VIDEO &&
                    Regex("(?i)\\b(?:hdr10\\+?|hdr|dovi|hlg)\\b").containsMatchIn(fileName),
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun photoTakenAt(uri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            photoDateMs(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                ?: photoDateMs(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED))
                ?: photoDateMs(exif.getAttribute(ExifInterface.TAG_DATETIME), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))
        }
    }.getOrNull()

    /** Un seul passage MediaExtractor récupère vidéo et audio pour limiter I/O et batterie. */
    private fun extractTrackInfo(uri: Uri): TrackInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var videoCodec: String? = null
            var audioCodec: String? = null
            var audioChannels: Int? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (videoCodec == null && mime?.startsWith("video/") == true) {
                    videoCodec = codecName(mime)
                } else if (audioCodec == null && mime?.startsWith("audio/") == true) {
                    audioCodec = codecName(mime)
                    audioChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 }
                    } else null
                }
            }
            TrackInfo(videoCodec, audioCodec, audioChannels)
        } catch (_: Exception) {
            TrackInfo()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use 0L
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun loadIndex(): List<MediaEntry> {
        return runCatching {
            val array = JSONArray(indexFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            buildList {
                for (i in 0 until minOf(array.length(), MAX_MEDIA_ITEMS)) {
                    val json = array.optJSONObject(i) ?: continue
                    runCatching { MediaEntry.fromPersistedJson(json) }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveIndex(items: List<MediaEntry>) {
        val payload = JSONArray().apply { items.forEach { put(it.persistedJson()) } }.toString()
        val output = indexFile.startWrite()
        try {
            output.write(payload.toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(output)
        } catch (error: Exception) {
            indexFile.failWrite(output)
            throw error
        }
    }

    private fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun extension(name: String?): String = MediaSupport.extension(name)

    private fun displayTitle(fileName: String): String = fileName
        .substringBeforeLast('.', fileName)
        .replace(Regex("[._]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Média" }

    private fun codecName(mime: String?): String? = when {
        mime.isNullOrBlank() -> null
        mime.contains("hevc", true) || mime.contains("h265", true) -> "hevc"
        mime.contains("avc", true) || mime.contains("h264", true) -> "h264"
        mime.contains("av01", true) || mime.contains("av1", true) -> "av1"
        mime.contains("vp9", true) -> "vp9"
        mime.contains("mp4a", true) || mime.contains("aac", true) -> "aac"
        mime.contains("eac3", true) || mime.contains("e-ac3", true) -> "eac3"
        mime.contains("ac3", true) || mime.contains("ac-3", true) -> "ac3"
        mime.contains("truehd", true) -> "truehd"
        mime.contains("dts", true) -> "dts"
        mime.contains("mpeg", true) -> "mp3"
        else -> mime.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private data class DirectoryNode(
        val directory: DocumentFile,
        val ancestors: List<String>,
        val depth: Int,
    )
    private data class ProbeResult(
        val discNumber: Int? = null,
        val trackNumber: Int? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationSeconds: Double = 0.0,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
        val isHdr: Boolean = false,
        val capturedAtMs: Long? = null,
    )
    private data class TrackInfo(
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
    )

    private data class ScanResult(
        val items: List<MediaEntry>,
        val warning: String? = null,
    )

    companion object {
        private const val MAX_SCAN_DEPTH = 64
        private const val MAX_DIRECTORY_COUNT = 25_000
        private const val MAX_MEDIA_ITEMS = 10_000
        private fun requiredProbeRevision(kind: LibrarySourceKind): Int = if (kind == LibrarySourceKind.PHOTO) 4 else 3
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
        private val LANGUAGE_CODES = setOf(
            "fr", "fre", "fra", "en", "eng", "es", "spa", "de", "ger", "deu", "it", "ita",
            "pt", "por", "nl", "dut", "ru", "rus", "ja", "jpn", "ko", "kor", "zh", "chi",
            "ar", "ara", "vff", "vfq", "vostfr",
        )
    }
}
