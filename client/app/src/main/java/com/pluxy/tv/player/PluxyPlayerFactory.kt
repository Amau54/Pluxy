package com.pluxy.tv.player

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
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
        // Planchers de sécurité : même si la config serveur est conservatrice, on
        // garantit un coussin minimal. Surtout, le coussin de REPRISE après un
        // rebuffering est généreux -> on repart avec une vraie avance au lieu de
        // re-bégayer aussitôt (cycle stall -> lecture -> stall) sur Wi-Fi instable.
        val minBufMs       = buffer.minBufferMs.coerceAtLeast(60_000)
        val maxBufMs       = buffer.maxBufferMs.coerceIn(minBufMs, 180_000)
        val startMs        = buffer.bufferForPlaybackMs                       // démarrage rapide
        val afterRebufMs   = buffer.bufferForPlaybackAfterRebufferMs.coerceAtLeast(15_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufMs, maxBufMs, startMs, afterRebufMs)
            // Plafond mémoire borné à la RAM dispo (évite l'OOM sur mobile faible) :
            // au plus la valeur demandée ET au plus ~1/4 de la mémoire de classe.
            .setTargetBufferBytes(targetBufferBytes(context, buffer.targetBufferBytesMb))
            // Priorise le remplissage du tampon sur la taille mémoire stricte.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(buffer.backBufferMs, /* retainBackBufferFromKeyframe */ true)
            .build()

        // ---- 2. Renderers : MATÉRIEL d'abord, logiciel en REPLI ------------ //
        // MODE_ON (et non PREFER) : le décodeur matériel HEVC/HDR est prioritaire ;
        // FFmpeg logiciel n'est utilisé QUE si le matériel échoue. PREFER cassait
        // le HDR10 (décodage CPU + conflit avec le tunneling).
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val isTv = isTelevision(context)

        // ---- 3. Source de données HTTP via OkHttp (Range + keep-alive) ----- //
        val ok = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(ok)
            .setDefaultRequestProperties(mapOf("User-Agent" to "PluxyTV/1.0"))

        // Tolérance aux erreurs de chargement : un segment transcodé peut renvoyer un
        // 503 passager (NVENC momentanément en retard sur une scène lourde). On RÉESSAIE
        // plusieurs fois ce segment au lieu de faire échouer toute la lecture (écran noir).
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(/* minRetryCount */ 6) {
            override fun getRetryDelayMsFor(info: LoadErrorInfo): Long {
                // Backoff court et borné : on laisse au serveur le temps de produire.
                return (1000L * (info.errorCount)).coerceAtMost(4000L)
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)

        // ---- 4. Sélecteur de pistes : HDR10 + tunneling -------------------- //
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Tunneling UNIQUEMENT sur Android TV (HDR10 passthrough vers la
                    // dalle). Sur mobile, beaucoup de décodeurs gèrent mal le tunneling
                    // (écran noir) -> on le désactive.
                    .setTunnelingEnabled(isTv)
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H265)
                    // Langue audio préférée = réglage utilisateur (fiche film).
                    .setPreferredAudioLanguage(
                        com.pluxy.tv.PluxyApplication.audioLang(context).ifBlank { null }
                    )
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
            .also {
                it.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                // Maintient un wake lock CPU + Wi-Fi tant que la lecture est active :
                // empêche l'endormissement réseau (et la veille système) qui coupait
                // le flux après quelques minutes sans interaction. Complète le
                // FLAG_KEEP_SCREEN_ON posé par l'activité.
                it.setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private fun isTelevision(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Octets de buffer cible : min(demandé, ~1/4 de la mémoire de classe), borné. */
    private fun targetBufferBytes(ctx: Context, requestedMb: Int): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val classMb = am.memoryClass                      // Mo alloués max au heap app
        val capMb = (classMb / 4).coerceIn(32, requestedMb.coerceAtLeast(32))
        return capMb * 1024 * 1024
    }
}
