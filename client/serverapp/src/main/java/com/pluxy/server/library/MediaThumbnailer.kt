package com.pluxy.server.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Miniatures photo/audio/vidéo calculées à la demande et bornées. Les vidéos ont en plus un
 * verrou exclusif : sur Android 6–8, l'extraction peut temporairement décoder une image 4K.
 */
class MediaThumbnailer(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val decodeSlots = Semaphore(MAX_CONCURRENT_DECODES, true)
    private val videoSlot = Semaphore(1, true)
    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val previewCache = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val previewLock = Any()
    private val previewPending = LinkedHashMap<String, Triple<String, MediaEntry, Long>>()
    private val previewFailures = LinkedHashMap<String, Long>()
    private var previewActive: String? = null
    private var previewWorker: Thread? = null

    fun preview(item: MediaEntry, position: Double): Result {
        // Les anciens retrievers décodent une image complète : garder le temps seul sur ces appareils.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 ||
            item.kind !in setOf("movie", "series", "clip")) return Result.Unsupported
        val second = PreviewPosition.second(position, item.durationSeconds)
        val key = "${item.id}:${item.modifiedAtMs}:${item.size}:$second"
        previewCache.get(key)?.let { return Result.Ready(it) }
        synchronized(previewLock) {
            if ((previewFailures[key] ?: 0) > android.os.SystemClock.elapsedRealtime()) return Result.Unsupported
            if (previewActive != key) {
                previewPending.remove(item.id)
                previewPending[item.id] = Triple(key, item, second)
                while (previewPending.size > 4) previewPending.remove(previewPending.keys.first())
            }
            if (previewWorker == null) {
                previewWorker = Thread(::runPreviews, "pluxy-seek-preview").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        return Result.Busy
    }

    fun cancelPreviews() = synchronized(previewLock) { previewPending.clear() }

    private fun runPreviews() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            val task = synchronized(previewLock) {
                if (previewPending.isEmpty()) {
                    previewWorker = null
                    previewActive = null
                    return
                }
                val id = previewPending.keys.first()
                previewPending.remove(id)!!.also { previewActive = it.first }
            }
            val result = capturePreview(task.second, task.third)
            synchronized(previewLock) {
                previewActive = null
                when (result) {
                    is Result.Ready -> previewCache.put(task.first, result.bytes)
                    Result.Busy -> Unit // Les miniatures peuvent occuper le décodeur ; laisser la prochaine demande réessayer.
                    Result.Unsupported -> {
                        previewFailures[task.first] = android.os.SystemClock.elapsedRealtime() + 60_000
                        while (previewFailures.size > 256) previewFailures.remove(previewFailures.keys.first())
                    }
                }
            }
        }
    }

    @android.annotation.TargetApi(27)
    private fun capturePreview(item: MediaEntry, second: Long): Result {
        if (!decodeSlots.tryAcquire()) return Result.Busy
        if (!videoSlot.tryAcquire()) { decodeSlots.release(); return Result.Busy }
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val width = item.width?.coerceAtLeast(1) ?: 320
                val height = item.height?.coerceAtLeast(1) ?: 180
                val ratio = minOf(320f / width, 180f / height, 1f)
                val frame = retriever.getScaledFrameAtTime(second * 1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
                    ?: return Result.Unsupported
                encodeScaled(frame)?.takeIf { it.size <= 256 * 1024 }
                    ?.let { Result.Ready(it) } ?: Result.Unsupported
            } ?: Result.Unsupported
        } catch (_: Exception) {
            Result.Unsupported
        } finally {
            runCatching { retriever.release() }
            videoSlot.release()
            decodeSlots.release()
        }
    }

    fun jpeg(item: MediaEntry): Result {
        if (item.kind !in setOf("photo", "movie", "series", "clip", "audio")) return Result.Unsupported
        val key = "v2:${item.id}:${item.modifiedAtMs}:${item.size}"
        cache.get(key)?.let { return Result.Ready(it) }
        if (!decodeSlots.tryAcquire(DECODE_WAIT_MS, TimeUnit.MILLISECONDS)) return Result.Busy
        val videoLocked = item.kind in setOf("movie", "series", "clip")
        if (videoLocked && !videoSlot.tryAcquire(DECODE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            decodeSlots.release()
            return Result.Busy
        }
        return try {
            cache.get(key)?.let { return Result.Ready(it) }
            val bytes = when (item.kind) {
                "photo" -> createPhoto(Uri.parse(item.uri))
                "audio" -> createAudio(Uri.parse(item.uri), item)
                else -> createVideo(Uri.parse(item.uri), item)
            } ?: return Result.Unsupported
            cache.put(key, bytes)
            Result.Ready(bytes)
        } finally {
            if (videoLocked) videoSlot.release()
            decodeSlots.release()
        }
    }

    sealed interface Result {
        data class Ready(val bytes: ByteArray) : Result
        data object Busy : Result
        data object Unsupported : Result
    }

    private fun createPhoto(uri: Uri): ByteArray? {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.loadThumbnail(uri, Size(THUMB_EDGE, THUMB_EDGE), null) }
                .getOrNull()
        } else {
            decodeSampled(uri)
        } ?: return null

        val longest = max(source.width, source.height)
        val output = if (longest > THUMB_EDGE) {
            val scale = THUMB_EDGE.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else source

        return try {
            ByteArrayOutputStream(128 * 1024).use { bytes ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)) return null
                bytes.toByteArray().takeIf { it.size <= MAX_THUMBNAIL_BYTES }
            }
        } finally {
            if (output !== source) output.recycle()
            source.recycle()
        }
    }

    private fun createVideo(uri: Uri, item: MediaEntry): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val times = if (item.durationSeconds > 0) listOf(0.12, 0.35, 0.65).map { item.durationSeconds * it } else listOf(1.0, 5.0, 15.0)
                var best: ByteArray? = null
                var bestScore = -1.0
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                for (second in times.distinct()) {
                    if (android.os.SystemClock.elapsedRealtime() > deadline) break
                    val timeUs = (second * 1_000_000.0).toLong()
                val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val sourceWidth = item.width?.coerceAtLeast(1) ?: THUMB_EDGE
                    val sourceHeight = item.height?.coerceAtLeast(1) ?: THUMB_EDGE
                    val ratio = minOf(
                        THUMB_EDGE.toFloat() / sourceWidth,
                        THUMB_EDGE.toFloat() / sourceHeight,
                        1f,
                    )
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        (sourceWidth * ratio).toInt().coerceAtLeast(1),
                        (sourceHeight * ratio).toInt().coerceAtLeast(1),
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: continue
                val sample = Bitmap.createScaledBitmap(source, 32, 32, false)
                val pixels = IntArray(32 * 32)
                sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                if (sample !== source) sample.recycle()
                val score = VideoFrameScore.score(pixels)
                val bytes = encodeScaled(source)
                if (bytes != null && score > bestScore) { best = bytes; bestScore = score }
                }
                best
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createAudio(uri: Uri, item: MediaEntry): ByteArray? {
        val embedded = if (item.size in 1L..MAX_AUDIO_METADATA_SOURCE_BYTES) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        retriever.setDataSource(descriptor.fileDescriptor)
                        retriever.embeddedPicture
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()?.takeIf { it.size <= MAX_EMBEDDED_ART_BYTES }
        } else null
        val source = embedded?.let { runCatching { decodeEmbeddedCover(it) }.getOrNull() }
            ?: generatedAudioCover(item)
        return encodeScaled(source)
    }

    private fun decodeEmbeddedCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / sample > THUMB_EDGE * 2 ||
            bounds.outHeight / sample > THUMB_EDGE * 2
        ) {
            sample *= 2
            if (sample >= 1 shl 16) return null
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    private fun generatedAudioCover(item: MediaEntry): Bitmap {
        val bitmap = Bitmap.createBitmap(THUMB_EDGE, THUMB_EDGE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val identity = listOfNotNull(item.artist, item.album, item.folder)
            .joinToString("|")
            .ifBlank { item.id }
        val hue = ((identity.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.58f, 0.42f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 210f
            alpha = 235
        }
        val symbol = item.album?.firstOrNull()?.uppercaseChar()?.toString()
            ?: item.artist?.firstOrNull()?.uppercaseChar()?.toString()
            ?: item.folder?.substringAfterLast('/')?.trim()?.firstOrNull()
                ?.uppercaseChar()?.toString()
            ?: "♪"
        val baseline = THUMB_EDGE / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(symbol, THUMB_EDGE / 2f, baseline, paint)
        return bitmap
    }

    private fun encodeScaled(source: Bitmap): ByteArray? {
        val longest = max(source.width, source.height)
        val output = if (longest > THUMB_EDGE) {
            val scale = THUMB_EDGE.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else source
        return try {
            ByteArrayOutputStream(128 * 1024).use { bytes ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)) return null
                bytes.toByteArray().takeIf { it.size <= MAX_THUMBNAIL_BYTES }
            }
        } finally {
            if (output !== source) output.recycle()
            source.recycle()
        }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth.toLong() / sample > THUMB_EDGE * 2L ||
            bounds.outHeight.toLong() / sample > THUMB_EDGE * 2L ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_DECODE_PIXELS
        ) {
            sample *= 2
            if (sample >= 1 shl 16) return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    companion object {
        private const val THUMB_EDGE = 512
        private const val JPEG_QUALITY = 82
        private const val CACHE_BYTES = 12 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 1024 * 1024
        private const val MAX_EMBEDDED_ART_BYTES = 8 * 1024 * 1024
        private const val MAX_AUDIO_METADATA_SOURCE_BYTES = 96L * 1024L * 1024L
        private const val MAX_DECODE_PIXELS = 2_000_000L
        private const val MAX_CONCURRENT_DECODES = 2
        private const val DECODE_WAIT_MS = 25L
    }
}
