package com.pluxy.tv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.pluxy.tv.api.BufferConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Construit un ExoPlayer optimisé pour le streaming 4K HDR sur Wi-Fi instable :
 *
 *  1. Pré-buffer agressif (LoadControl) piloté par la config serveur — pré-charge
 *     plusieurs centaines de Mo en RAM pour absorber les micro-coupures du double
 *     saut Wi-Fi 5 GHz.
 *  2. Décodage matériel HEVC Main10 + passthrough HDR10 (tunneling) vers la dalle
 *     OLED de la Philips 803.
 *  3. Source HLS (transcode NVENC) ou progressive (Direct Play / Direct Stream).
 */
@UnstableApi
object PluxyPlayerFactory {

    fun build(context: Context, buffer: BufferConfig): ExoPlayer {
        // ---- 1. LoadControl : gros tampon réseau --------------------------- //
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minBufferMs,                            // ex. 50 s mini
                buffer.maxBufferMs,                            // ex. 120 s maxi
                buffer.bufferForPlaybackMs,                    // démarrage
                buffer.bufferForPlaybackAfterRebufferMs        // reprise post-coupure
            )
            // Plafond mémoire = X Mo demandés dans l'UI (256 Mo par défaut).
            .setTargetBufferBytes(buffer.targetBufferBytesMb * 1024 * 1024)
            // Priorise le remplissage du tampon sur la taille mémoire stricte.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(buffer.backBufferMs, /* retainBackBufferFromKeyframe */ true)
            .build()

        // ---- 2. Renderers : HW d'abord, repli logiciel autorisé ------------ //
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // ---- 3. Source de données HTTP via OkHttp (Range + keep-alive) ----- //
        val ok = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(ok)
            .setDefaultRequestProperties(mapOf("User-Agent" to "PluxyTV/1.0"))

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)

        // ---- 4. Sélecteur de pistes : HDR10 + tunneling -------------------- //
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Le tunneling laisse le flux HDR10 transiter directement vers
                    // l'afficheur (HDR statique préservé, A/V sync matérielle).
                    .setTunnelingEnabled(true)
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H265)
                    // Langues par défaut (audio + sous-titres) : français.
                    .setPreferredAudioLanguage("fr")
                    .setPreferredTextLanguage("fr")
                    // 4K natif : aucun bridage de résolution côté client.
                    .clearVideoSizeConstraints()
                    .clearViewportSizeConstraints()
                    .setForceHighestSupportedBitrate(false)
            )
        }

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            // Saut avant/arrière de 10 s (boutons natifs du PlayerView).
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Surdimensionne le back-buffer audio pour éviter les coupures son ARC.
            .setReleaseTimeoutMs(5000)
            .build()
            .also { it.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT }
    }
}
