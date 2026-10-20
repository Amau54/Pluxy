package com.pluxy.server.library

import android.media.Image
import android.graphics.Bitmap
import java.io.IOException
import kotlin.math.min
import kotlin.math.sqrt
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** The Latin recognition model is bundled: frames are processed on this device. */
internal class SegmentOcr : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var running: Task<Text>? = null
    fun read(image: Image): CreditText {
        if (running?.isComplete == false) throw IOException("Reconnaissance précédente encore en cours")
        // SDR and HDR credits need comparable contrast. Bound the temporary bitmap
        // rather than handing an unrestricted 4K frame to the recognition model.
        val bitmap = try { contrast(image) } catch (_: OutOfMemoryError) { throw IOException("Mémoire OCR insuffisante") }
        var task: Task<Text>? = null
        try {
            task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            running = task
            val result = Tasks.await(task, 8, TimeUnit.SECONDS)
            val lines = result.textBlocks.flatMap { it.lines }.map { it.text }
            val blank = SegmentSnapshot.luminance(image).count { it > 24 } < 10
            return CreditTextPolicy.summarize(lines, blank)
        } catch (_: OutOfMemoryError) { throw IOException("Mémoire OCR insuffisante") }
        finally {
            // A timed-out ML task can still be reading its input in native code.
            val pending = task
            if (pending == null || pending.isComplete) bitmap.recycle()
            else pending.addOnCompleteListener { bitmap.recycle() }
        }
    }

    private fun contrast(image: Image): Bitmap {
        val crop = image.cropRect
        val scale = min(1.0, min(2560.0 / crop.width(), sqrt(2_800_000.0 / (crop.width().toDouble() * crop.height()))))
        val width = (crop.width() * scale).toInt().coerceAtLeast(1)
        val height = (crop.height() * scale).toInt().coerceAtLeast(1)
        val plane = image.planes[0]; val buffer = plane.buffer; val origin = buffer.position()
        val histogram = IntArray(256)
        val pixels = IntArray(width * height) { index ->
            val x = crop.left + ((index % width + .5) * crop.width() / width).toInt().coerceAtMost(crop.width() - 1)
            val y = crop.top + ((index / width + .5) * crop.height() / height).toInt().coerceAtMost(crop.height() - 1)
            val value = buffer.get(origin + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
            histogram[value]++; value
        }
        fun percentile(portion: Double): Int {
            var count = 0
            for (level in histogram.indices) { count += histogram[level]; if (count >= pixels.size * portion) return level }
            return 255
        }
        val lower = percentile(.005); val upper = percentile(.999)
        for (index in pixels.indices) {
            val value = if (upper - lower >= 12) ((pixels[index] - lower) * 255 / (upper - lower)).coerceIn(0, 255) else pixels[index]
            pixels[index] = (255 shl 24) or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
    override fun close() { recognizer.close() }
}
