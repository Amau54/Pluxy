package com.pluxy.server.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
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
    private val directory = File(context.filesDir, "segments-v3").apply { mkdirs() }
    private val pending = mutableSetOf<String>()
    @Volatile private var closed = false
    private val textEngine = lazy { SegmentOcr() }
    private val textCache = mutableMapOf<Pair<String, Double>, Pair<Double, CreditText>>()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "pluxy-segments").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private fun path(item: MediaEntry): File {
        val key = "v3:${item.id}:${item.uri}:${item.size}:${item.modifiedAtMs}:${item.fingerprint}"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    private fun read(item: MediaEntry): JSONObject? = runCatching {
        val file = path(item)
        if (file.length() in 1..262144) JSONObject(file.readText()) else null
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
        if (cache != null && cache.optLong("retry_after") > System.currentTimeMillis()) return known
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
        val seconds = min(900.0, item.durationSeconds * 0.5)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null)
            val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val supported = tracks.filter { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(extractor.getTrackFormat(it)) != null }
            val track = supported.firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_LANGUAGE) in setOf("fr", "fra", "fre") }
                ?: supported.firstOrNull() ?: throw java.io.IOException("Décodeur audio indisponible")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            if (Build.VERSION.SDK_INT >= 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0); codec.start()
            val result = ShortArray((seconds * 8000).toInt())
            var count = 0
            var inputEnded = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            var outputEnded = false
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
                    if (Build.VERSION.SDK_INT >= 24 && output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) throw java.io.IOException("Format PCM indisponible")
                } else if (index >= 0) {
                    val output = codec.getOutputBuffer(index)!!
                    output.position(info.offset); output.limit(info.offset + info.size)
                    val pcm = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var sourceFrame = 0L
                    while (pcm.remaining() >= channels && count < result.size) {
                        var sample = 0
                        repeat(channels) { sample += pcm.get().toInt() }
                        val timeUs = info.presentationTimeUs + sourceFrame * 1_000_000L / rate
                        val position = (timeUs * 8000 / 1_000_000).toInt()
                        if (position >= count && position < result.size) {
                            while (count < position) result[count++] = 0
                            result[count++] = (sample / channels).toShort()
                        }
                        sourceFrame++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputEnded = true; break }
                }
            }
            if (closed || (!outputEnded && count < result.size)) throw java.io.IOException("Analyse audio interrompue")
            if (count < 8000) throw java.io.IOException("Échantillon audio indisponible")
            val fingerprints = AudioSignals.fingerprints(result.copyOf(count))
            write(item, (read(item) ?: JSONObject()).put("audio", JSONArray(fingerprints.toList())))
            return fingerprints
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
    }
    private fun snapshot(item: MediaEntry, seconds: Double, exact: Boolean = false): SegmentSnapshot.Frame =
        SegmentSnapshot.read(context, item, seconds, exact) { closed } ?: throw java.io.IOException("Image indisponible")

    private fun visualIntro(item: MediaEntry, peer: MediaEntry, candidate: AudioSignals.Match): Pair<Double, Double>? {
        val length = candidate.end - candidate.start
        val points = (0..6).map { candidate.start + 1 + (length - 3) * it / 6 }
        val difference = candidate.otherStart - candidate.start
        fun same(seconds: Double) = SegmentPictures.same(snapshot(item, seconds, true).pixels, snapshot(peer, seconds + difference, true).pixels)
        val original = points.map { same(it) }
        val valid = original.toMutableList()
        for (index in if (original.count { it } >= 3) points.indices else IntRange.EMPTY) {
            if (original[index]) continue
            val own = runCatching { textFrame(item, points[index]).second }.getOrNull()
            val other = runCatching { textFrame(peer, points[index] + difference).second }.getOrNull()
            valid[index] = own != null && other != null && (own.nameCard || own.roles.isNotEmpty()) && (other.nameCard || other.roles.isNotEmpty())
        }
        val runs = ArrayList<Pair<Int, Int>>()
        var beginning: Int? = null
        for (index in 0..valid.size) {
            if (index < valid.size && valid[index]) {
                if (beginning == null) beginning = index
            } else if (beginning != null) { runs += beginning to index - 1; beginning = null }
        }
        val run = runs.maxByOrNull { it.second - it.first } ?: return null
        val first = run.first; val last = run.second
        if (last - first < 2) return null
        if (points[last] - points[first] < 8) return null
        var end = points[last]
        if (last < points.lastIndex) {
            var high = points[last + 1]
            repeat(4) { val point = (end + high) / 2; if (same(point)) end = point else high = point }
        } else end = candidate.end
        return points[first] to end
    }

    private fun textFrame(item: MediaEntry, seconds: Double): Pair<Double, CreditText> {
        val key = item.id to seconds
        textCache[key]?.let { return it }
        val frame = SegmentSnapshot.read(context, item, seconds, exact = true, textReader = { textEngine.value.read(it) }) { closed }
            ?: throw java.io.IOException("Image OCR indisponible")
        val text = frame.text ?: throw java.io.IOException("Texte indisponible")
        debug("OCR ${frame.seconds}: roles=${text.roles.size}, dense=${text.dense}, names=${text.nameCard}, blank=${text.blank}")
        textCache[key] = frame.seconds to text
        return frame.seconds to text
    }

    private fun textOpening(item: MediaEntry): Pair<Double, Double>? {
        val samples = ArrayList<Pair<Double, CreditText>>()
        for (seconds in 0 until min(300.0, item.durationSeconds * .4).toInt() step 12) {
            if (closed) throw java.io.IOException("Analyse interrompue")
            samples += textFrame(item, seconds.toDouble())
            if (samples.size >= 2 && samples.takeLast(2).all { !it.second.nameCard && !it.second.dense }) {
                CreditTextPolicy.opening(samples.distinctBy { it.first })?.let { return it }
            }
        }
        return CreditTextPolicy.opening(samples.distinctBy { it.first })
    }

    private fun textCredits(item: MediaEntry): Pair<Double, Double>? {
        val start = maxOf(item.durationSeconds * .65, item.durationSeconds - 600)
        var seed: Double? = null
        for (offset in listOf(90, 30, 180, 300, 420)) {
            val point = textFrame(item, maxOf(start, item.durationSeconds - offset))
            if (point.second.dense) { seed = point.first; break }
        }
        val anchor = seed ?: return null
        var lower = anchor; var upper = anchor; var dense = 1
        for (direction in listOf(-1, 1)) {
            var blanks = 0
            var seconds = anchor + direction * 8
            while (seconds >= start && seconds < item.durationSeconds - 1) {
                if (closed) throw java.io.IOException("Analyse interrompue")
                val (time, text) = textFrame(item, seconds)
                if (text.roles.isNotEmpty() || text.roster || direction < 0 && text.nameCard) {
                    lower = minOf(lower, time); upper = maxOf(upper, time)
                    if (text.dense) dense++
                    blanks = 0
                } else if (text.blank && blanks == 0) blanks++ else break
                seconds += direction * 8
            }
        }
        val confirmed = upper - lower >= 24 && dense >= 2 || upper - lower >= 16 && dense >= 3
        return if (confirmed) lower + 1 to upper else null
    }

    private fun credits(item: MediaEntry): Pair<Double, Double>? {
        return try { visualCredits(item) } catch (_: java.io.IOException) { textCredits(item) }
    }

    private fun visualCredits(item: MediaEntry): Pair<Double, Double>? {
        val start = maxOf(item.durationSeconds * .65, item.durationSeconds - 600)
        val samples = ArrayList<SegmentSnapshot.Frame>()
        var seconds = start
        while (seconds < item.durationSeconds) {
            if (closed) throw java.io.IOException("Analyse interrompue")
            val frame = SegmentSnapshot.read(context, item, seconds) { closed }
            if (frame != null && frame.seconds >= start - 15 && frame.seconds < item.durationSeconds &&
                (samples.isEmpty() || frame.seconds > samples.last().seconds + .1)) samples += frame
            seconds += 12
        }
        if (samples.size < 3) throw java.io.IOException("Images insuffisantes")
        val runs = ArrayList<Pair<Double, Double>>()
        var first: Double? = null; var last: Double? = null
        for (frame in samples + SegmentSnapshot.Frame(item.durationSeconds + 60, intArrayOf())) {
            if (SegmentSignals.creditFrame(frame.pixels) && (last == null || frame.seconds - last!! <= 40)) {
                if (first == null) first = frame.seconds
                last = frame.seconds
            } else {
                if (last != null && frame.seconds - last!! <= 40) continue
                if (first != null && last != null && last!! - first!! >= 12) runs += first!! to last!!
                first = null; last = null
            }
        }
        val coarse = runs.firstOrNull() ?: return textCredits(item)
        val scanStart = maxOf(start, coarse.first - 12)
        val scanEnd = minOf(item.durationSeconds, coarse.second + 12)
        val flags = DecodedCreditFrames.creditWindow(context, item, scanStart, scanEnd) { closed }
            ?: throw java.io.IOException("Analyse des limites interrompue")
        val interval = SegmentSignals.creditInterval(flags) ?: return textCredits(item)
        var lower = scanStart + interval.first
        var upper = scanStart + interval.second
        var begin = maxOf(start, lower - 8)
        val initial = (0..8).map { offset ->
            val time = begin + offset * 2
            time < item.durationSeconds && SegmentSignals.creditFrame(snapshot(item, time, true).pixels)
        }
        val confirmed = (0 until initial.lastIndex).firstOrNull { initial[it] && initial[it + 1] }
            ?: throw java.io.IOException("Début du générique non confirmé")
        lower = begin + confirmed * 2
        begin = maxOf(lower, upper - 8)
        var final: Int? = null
        for (index in 0..8) {
            val time = begin + index * 2
            if (time >= item.durationSeconds) break
            if (SegmentSignals.creditFrame(snapshot(item, time, true).pixels)) final = index
            else if (final != null && index - final!! > 1) break
        }
        val end = final ?: throw java.io.IOException("Fin du générique non confirmée")
        upper = minOf(item.durationSeconds - .5, begin + end * 2)
        if (upper - lower < 12) return null
        if (textFrame(item, (lower + upper) / 2).second.roles.isEmpty()) return textCredits(item)
        return lower to upper
    }

    private fun analyze(item: MediaEntry) {
        textCache.clear()
        val previous = read(item) ?: JSONObject()
        val data = JSONObject(previous.toString()).put("peers", revision(item)).put("complete", false)
        if (previous.optString("peers") != revision(item)) data.put("segments", JSONArray()).put("intro_done", false).put("credits_done", false)
        val segments = data.optJSONArray("segments") ?: JSONArray().also { data.put("segments", it) }
        fun publish() { if (!closed) write(item, data) }
        fun add(title: String, interval: Pair<Double, Double>?) {
            if (interval != null) segments.put(JSONObject().put("title", title).put("start_ms", (interval.first * 1000).toLong()).put("end_ms", (interval.second * 1000).toLong()))
        }
        if (!data.optBoolean("intro_done") && !closed) {
            try {
                var interval: Pair<Double, Double>? = null
                if (item.kind == "series") {
                    val peers = peers(item)
                    val own = if (peers.isNotEmpty()) audio(item) else intArrayOf()
                    data.put("audio", JSONArray(own.toList()))
                    val candidates = peers.mapNotNull { peer -> AudioSignals.match(own, audio(peer))?.let { peer to it } }
                    for ((peer, candidate) in candidates) {
                        if (candidate.end - candidate.start < 25) {
                            val agreeing = candidates.map { it.second }.filter { kotlin.math.abs(it.start - candidate.start) < 2 && kotlin.math.abs(it.end - candidate.end) < 2 }
                            if (agreeing.size >= 2) interval = agreeing.maxOf { it.start } + 1 to agreeing.minOf { it.end } - .5
                        } else interval = visualIntro(item, peer, candidate)
                        if (interval != null) break
                    }
                }
                if (interval == null && item.kind == "movie") interval = textOpening(item)
                if (interval == null && item.kind == "series") {
                    val frames = DecodedCreditFrames.opening(context, item) { closed } ?: throw java.io.IOException("Analyse du début interrompue")
                    interval = OpeningSignals.interval(frames)?.let { it.first.toDouble() to it.second.toDouble() }
                }
                add("Opening", interval)
                data.put("intro_done", true)
                publish()
            } catch (error: Exception) { data.put("last_error", error.javaClass.simpleName); debug("Intro : ${error.javaClass.simpleName}") }
        }
        if (!data.optBoolean("credits_done") && !closed) {
            try { add("End credits", credits(item)); data.put("credits_done", true) }
            catch (error: Exception) { data.put("last_error", error.javaClass.simpleName); debug("Générique : ${error.javaClass.simpleName}") }
        }
        val complete = data.optBoolean("intro_done") && data.optBoolean("credits_done")
        if (complete) data.remove("last_error")
        val attempts = previous.optInt("attempts") + 1
        data.put("complete", complete).put("attempts", attempts)
            .put("retry_after", if (complete) 0L else System.currentTimeMillis() + minOf(900_000L, 15_000L * (1L shl attempts.coerceAtMost(6))))
        publish()
    }
    fun close() { closed = true; worker.shutdownNow(); if (textEngine.isInitialized()) textEngine.value.close() }
}
