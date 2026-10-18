package com.pluxy.server.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AutomaticSegments(private val context: Context, private val library: MediaLibrary) {
    private fun debug(message: String) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.d("PluxySegments", message)
    }
    private val directory = File(context.filesDir, "segments-v2").apply { mkdirs() }
    private val pending = mutableSetOf<String>()
    @Volatile private var closed = false
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "pluxy-segments").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private fun path(item: MediaEntry): File {
        val key = "v2:${item.id}:${item.uri}:${item.size}:${item.modifiedAtMs}:${item.fingerprint}"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    private fun read(item: MediaEntry): JSONObject? = runCatching {
        val file = path(item)
        if (file.length() in 1..32768) JSONObject(file.readText()) else null
    }.getOrNull()
    private fun revision(item: MediaEntry): String = peers(item).joinToString("|") { "${it.id}:${it.modifiedAtMs}:${it.size}" }
    private fun peers(item: MediaEntry): List<MediaEntry> = library.all().filter {
        it.id != item.id && it.kind == "series" && (item.seriesKey ?: item.seriesName) != null &&
            (it.seriesKey ?: it.seriesName) == (item.seriesKey ?: item.seriesName) && it.sourceId == item.sourceId &&
            it.season == item.season && it.durationSeconds >= 180
    }.sortedBy { kotlin.math.abs((it.episode ?: 0) - (item.episode ?: 0)) }.take(2)
    private fun write(item: MediaEntry, value: JSONObject) {
        val file = path(item); val temporary = File(file.path + ".tmp")
        temporary.writeText(value.toString())
        if (!temporary.renameTo(file)) { temporary.delete(); throw java.io.IOException("Cache indisponible") }
    }
    fun request(item: MediaEntry): JSONArray {
        val cache = read(item)
        val known = if (cache != null && cache.optString("peers") == revision(item)) cache.optJSONArray("segments") ?: JSONArray() else JSONArray()
        if (cache?.has("segments") == true && cache.optString("peers") == revision(item) && cache.optBoolean("complete", true)) return known
        if (!closed && item.kind in setOf("series", "movie", "clip", "video") && item.durationSeconds >= 180 && item.durationSeconds.isFinite()) synchronized(pending) {
            if (pending.size < 4 && pending.add(item.id)) worker.execute {
                try { analyze(item) } catch (_: Exception) { /* Optional analysis never blocks playback. */ }
                finally { synchronized(pending) { pending.remove(item.id) } }
            }
        }
        return known
    }
    private fun audio(item: MediaEntry): IntArray {
        read(item)?.optJSONArray("audio")?.takeIf { it.length() > 0 }?.let { values -> return IntArray(values.length()) { values.getInt(it) } }
        val seconds = min(600.0, item.durationSeconds * 0.5)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null)
            val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val track = tracks.firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_LANGUAGE) in setOf("fr", "fra", "fre") } ?: tracks.firstOrNull() ?: return intArrayOf()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            if (Build.VERSION.SDK_INT >= 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0); codec.start()
            val result = ShortArray((seconds * 8000).toInt())
            var count = 0
            var inputEnded = false
            var frames = 0L
            var nextSample = 0.0
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65)
            while (!closed && count < result.size && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        val end = size < 0 || extractor.sampleTime >= (seconds * 1_000_000).toLong()
                        codec.queueInputBuffer(index, 0, if (end) 0 else size, if (end) 0 else extractor.sampleTime, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (end) inputEnded = true else extractor.advance()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return intArrayOf()
                } else if (index >= 0) {
                    val output = codec.getOutputBuffer(index)!!
                    output.position(info.offset); output.limit(info.offset + info.size)
                    val pcm = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (pcm.remaining() >= channels && count < result.size) {
                        var sample = 0
                        repeat(channels) { sample += pcm.get().toInt() }
                        if (frames >= nextSample) { result[count++] = (sample / channels).toShort(); nextSample += rate / 8000.0 }
                        frames++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (closed) return intArrayOf()
            val fingerprints = SegmentSignals.fingerprints(result.copyOf(count))
            write(item, JSONObject().put("audio", JSONArray(fingerprints.toList())))
            return fingerprints
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
    }
    private fun credits(item: MediaEntry): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < 27) return null
        val start = maxOf(item.durationSeconds * 0.65, item.durationSeconds - 600)
        // Sequential software decoding avoids inaccurate MKV thumbnails and repeated seeks.
        val decoded = DecodedCreditFrames.read(context, item, start) { closed } ?: return null
        debug("Générique décodé : ${decoded.size} images, ${decoded.count { it }} reconnues")
        return SegmentSignals.creditInterval(decoded)?.let {
            ((start + it.first) * 1000).toLong() to ((start + it.second) * 1000).toLong()
        }
    }
    private fun analyze(item: MediaEntry) {
        val segments = JSONArray()
        fun add(kind: String, start: Long, end: Long) { segments.put(JSONObject().put("title", kind).put("start_ms", start).put("end_ms", end)) }
        val peers = if (item.kind == "series") peers(item) else emptyList()
        val own = if (peers.isNotEmpty()) runCatching { audio(item) }.getOrDefault(intArrayOf()) else intArrayOf()
        for (peer in peers) {
            if (closed) return
            val match = runCatching { SegmentSignals.repeatedIntro(own, audio(peer)) }.getOrNull()
            if (match != null) {
                add("Opening", match.first * 1000L, match.second * 1000L)
                if (!closed) write(item, JSONObject().put("audio", JSONArray(own.toList())).put("segments", segments)
                    .put("peers", revision(item)).put("complete", false))
                break
            }
        }
        if (segments.length() == 0 && item.kind in setOf("movie", "series") && !closed && Build.VERSION.SDK_INT >= 27) {
            runCatching { DecodedCreditFrames.opening(context, item) { closed }?.let { OpeningSignals.interval(it) } }
                .onFailure { debug("Analyse du début indisponible : ${it.javaClass.simpleName}") }
                .getOrNull()?.let {
                    add("Opening", it.first * 1000L, it.second * 1000L)
                    if (!closed) write(item, JSONObject().put("audio", JSONArray(own.toList())).put("segments", segments)
                        .put("peers", revision(item)).put("complete", false))
                }
        }
        runCatching { credits(item) }.onFailure { debug("Analyse visuelle indisponible : ${it.javaClass.simpleName}") }
            .getOrNull()?.let { add("End credits", it.first, it.second) }
        if (!closed) write(item, JSONObject().put("audio", JSONArray(own.toList())).put("segments", segments).put("peers", revision(item)).put("complete", true))
    }
    fun close() { closed = true; worker.shutdownNow() }
}
