package com.pluxy.server.library

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.util.concurrent.TimeUnit

/** Reads actual presentation timestamps; Matroska thumbnails may return an older keyframe. */
internal object DecodedCreditFrames {
    fun read(context: Context, item: MediaEntry, start: Double, stopped: () -> Boolean): List<Boolean>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            // Preserve hardware decoder capacity for active playback.
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                    (if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else
                        info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")) &&
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in info.getCapabilitiesForType(mime).colorFormats
            } ?: return null
            extractor.selectTrack(track)
            extractor.seekTo((start * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createByCodecName(decoder.name)
            codec.configure(format, null, null, 0)
            codec.start()
            val flags = mutableListOf<Boolean>()
            var nextTime = (start * 1_000_000).toLong()
            val endTime = (item.durationSeconds * 1_000_000).toLong()
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65)
            while (!stopped() && !outputEnded && nextTime < endTime && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        val end = size < 0
                        codec.queueInputBuffer(index, 0, if (end) 0 else size, if (end) 0 else extractor.sampleTime,
                            if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (end) inputEnded = true else extractor.advance()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index >= 0) {
                    try {
                        if (info.presentationTimeUs >= nextTime && info.size > 0) {
                            // Missing samples remain false; they cannot create a confident interval.
                            while (info.presentationTimeUs >= nextTime + 2_000_000 && nextTime < endTime) {
                                flags += false; nextTime += 2_000_000
                            }
                            if (nextTime < endTime) {
                                val frame = codec.getOutputImage(index)
                                if (frame == null) flags += false else try {
                                    if (frame.format != android.graphics.ImageFormat.YUV_420_888) return null
                                    val crop = frame.cropRect
                                    val plane = frame.planes[0]
                                    val pixels = IntArray(128 * 72)
                                    val origin = plane.buffer.position()
                                    for (y in 0 until 72) for (x in 0 until 128) {
                                        val px = crop.left + ((x + 0.5) * crop.width() / 128).toInt().coerceAtMost(crop.width() - 1)
                                        val py = crop.top + ((y + 0.5) * crop.height() / 72).toInt().coerceAtMost(crop.height() - 1)
                                        pixels[y * 128 + x] = plane.buffer.get(origin + py * plane.rowStride + px * plane.pixelStride).toInt() and 255
                                    }
                                    flags += SegmentSignals.creditFrame(pixels)
                                } finally { frame.close() }
                                nextTime += 2_000_000
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            if (stopped() || (!outputEnded && nextTime < endTime)) return null
            return flags
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }
}
