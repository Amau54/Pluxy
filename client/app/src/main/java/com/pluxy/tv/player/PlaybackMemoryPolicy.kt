package com.pluxy.tv.player

import com.pluxy.tv.api.BufferConfig

/**
 * Transforme la configuration serveur en budget réellement soutenable par l'appareil.
 *
 * Le serveur peut proposer un gros tampon pour une TV haut de gamme, mais il ne connaît
 * ni la classe mémoire Android ni le drapeau low-RAM du lecteur. Cette politique pure
 * garde donc la décision testable et évite de dépendre de largeHeap.
 */
internal object PlaybackMemoryPolicy {

    data class RuntimeBuffer(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val targetBufferBytes: Int,
        val backBufferMs: Int,
        val retainBackBufferFromKeyframe: Boolean,
    )

    fun resolve(
        requested: BufferConfig,
        memoryClassMb: Int,
        isTelevision: Boolean,
        isLowRamDevice: Boolean,
        sourceBitrateBps: Long = 0L,
    ): RuntimeBuffer {
        val durationFloorMs = when {
            isLowRamDevice -> 15_000
            isTelevision -> 30_000
            else -> 20_000
        }
        val durationCapMs = when {
            isLowRamDevice -> 45_000
            isTelevision -> 120_000
            else -> 90_000
        }
        val minBufferMs = requested.minBufferMs.coerceIn(durationFloorMs, durationCapMs)
        val maxBufferMs = requested.maxBufferMs.coerceIn(minBufferMs, durationCapMs)
        val afterRebufferFloorMs = 500

        val safeMemoryClassMb = memoryClassMb.coerceAtLeast(1)
        val heapBudgetMb = when {
            isLowRamDevice -> (safeMemoryClassMb / 8).coerceIn(16, 48)
            isTelevision -> (safeMemoryClassMb / 3).coerceIn(16, 256)
            else -> (safeMemoryClassMb / 4).coerceIn(16, 128)
        }
        val targetMb = requested.targetBufferBytesMb
            .coerceAtLeast(16)
            .coerceAtMost(heapBudgetMb)

        return RuntimeBuffer(
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = requested.bufferForPlaybackMs.coerceIn(250, minBufferMs),
            bufferForPlaybackAfterRebufferMs = requested.bufferForPlaybackAfterRebufferMs
                .coerceIn(afterRebufferFloorMs, minBufferMs),
            targetBufferBytes = targetMb * MEBIBYTE,
            // La vidéo déjà lue partage le même budget que les images à venir.
            // Réserver 30 s derrière consommait tout le tampon d'un gros fichier.
            backBufferMs = if (isLowRamDevice) 0 else requested.backBufferMs.coerceIn(0,
                if (sourceBitrateBps > 0L) {
                    (targetMb * MEBIBYTE * 8.0 * 0.1 / (sourceBitrateBps * 1.5) * 1000)
                        .toInt().coerceIn(0, 10_000)
                } else 2_000),
            retainBackBufferFromKeyframe = false,
        )
    }

    private const val MEBIBYTE = 1024 * 1024
}
