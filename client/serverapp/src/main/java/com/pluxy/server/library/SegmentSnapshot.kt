package com.pluxy.server.library

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads one indexed frame with a software decoder; it never reserves the playback decoder. */
internal object SegmentSnapshot {
    data class Frame(val seconds: Double, val pixels: IntArray, val text: CreditText? = null)

    fun read(context: Context, item: MediaEntry, seconds: Double, exact: Boolean = false,
        textReader: ((Image) -> CreditText)? = null, stopped: () -> Boolean): Frame? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IOException("Piste vidéo indisponible")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } &&
                    SegmentDecoderPolicy.software(it.name, Build.VERSION.SDK_INT >= 29 && it.isSoftwareOnly) &&
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in it.getCapabilitiesForType(mime).colorFormats
            } ?: throw IOException("Décodeur logiciel indisponible")
            extractor.selectTrack(track)
            val target = (seconds.coerceAtLeast(0.0) * 1_000_000).toLong()
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createByCodecName(decoder.name)
            codec.configure(format, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var ended = false
            var supplied = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!stopped() && System.nanoTime() < deadline) {
                if (!ended) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = if (supplied && !exact) -1 else extractor.readSampleData(buffer, 0)
                        val end = size < 0
                        codec.queueInputBuffer(index, 0, if (end) 0 else size, if (end) 0 else extractor.sampleTime,
                            if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (end) ended = true else { supplied = true; extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index >= 0) try {
                    if (info.size > 0 && (!exact || info.presentationTimeUs >= target)) {
                        val image = codec.getOutputImage(index) ?: throw IOException("Image vidéo indisponible")
                        try { return Frame(info.presentationTimeUs / 1_000_000.0, luminance(image), textReader?.invoke(image)) } finally { image.close() }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                } finally { codec.releaseOutputBuffer(index, false) }
            }
            throw IOException("Analyse vidéo interrompue")
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
    }

    fun luminance(image: Image): IntArray {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) throw IOException("Format vidéo non pris en charge")
        val crop = image.cropRect
        val plane = image.planes[0]
        val origin = plane.buffer.position()
        return IntArray(128 * 72) { index ->
            val x = crop.left + ((index % 128 + .5) * crop.width() / 128).toInt().coerceAtMost(crop.width() - 1)
            val y = crop.top + ((index / 128 + .5) * crop.height() / 72).toInt().coerceAtMost(crop.height() - 1)
            plane.buffer.get(origin + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
        }
    }
}
