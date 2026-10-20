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
    fun read(context: Context, item: MediaEntry, start: Double, stopped: () -> Boolean): List<Boolean>? =
        decode(context, item, start, item.durationSeconds, stopped, false) { SegmentSignals.creditFrame(it) }

    fun opening(context: Context, item: MediaEntry, stopped: () -> Boolean): List<OpeningSignals.Frame>? =
        decode(context, item, 0.0, minOf(300.0, item.durationSeconds * .4), stopped, OpeningSignals.Frame(), true, OpeningSignals::frame)

    fun creditWindow(context: Context, item: MediaEntry, start: Double, end: Double, stopped: () -> Boolean): List<Boolean>? =
        decode(context, item, start, end, stopped, false, true) { SegmentSignals.creditFrame(it) }

    private fun <T> decode(context: Context, item: MediaEntry, start: Double, end: Double,
        stopped: () -> Boolean, missing: T, syncOnly: Boolean = false, classify: (IntArray) -> T): List<T>? {
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
                    SegmentDecoderPolicy.software(info.name, Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) &&
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in info.getCapabilitiesForType(mime).colorFormats
            } ?: return null
            extractor.selectTrack(track)
            extractor.seekTo((start * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createByCodecName(decoder.name)
            codec.configure(format, null, null, 0)
            codec.start()
            val flags = mutableListOf<T>()
            var nextTime = (start * 1_000_000).toLong()
            val endTime = (end * 1_000_000).toLong()
            var inputEnded = false
            var outputEnded = false
            var validSamples = 0
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65)
            while (!stopped() && !outputEnded && nextTime < endTime && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    if (syncOnly) while (extractor.sampleTime >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
                        if (!extractor.advance()) break
                    }
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        val inputFinished = size < 0 || extractor.sampleTime >= endTime
                        codec.queueInputBuffer(index, 0, if (inputFinished) 0 else size, if (inputFinished) 0 else extractor.sampleTime,
                            if (inputFinished) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (inputFinished) inputEnded = true else extractor.advance()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 1000)
                if (index >= 0) {
                    try {
                        if (info.presentationTimeUs >= nextTime && info.size > 0) {
                            val frame = codec.getOutputImage(index)
                            val value = if (frame == null) missing else try {
                                validSamples++
                                classify(SegmentSnapshot.luminance(frame))
                            } finally { frame.close() }
                            val closeKeyframe = syncOnly && info.presentationTimeUs - nextTime <= 8_000_000
                            while (info.presentationTimeUs >= nextTime && nextTime < endTime) {
                                flags += if (closeKeyframe || info.presentationTimeUs < nextTime + 2_000_000) value else missing
                                nextTime += 2_000_000
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            if (stopped() || (!outputEnded && nextTime < endTime) || validSamples == 0) return null
            return flags
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }
}
