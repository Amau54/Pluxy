package com.pluxy.tv.player

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.api.BufferConfig
import com.pluxy.tv.api.LanUrlPolicy
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Lecteur matériel en priorité, tampon plafonné selon la RAM et flux limités au serveur choisi. */
@OptIn(UnstableApi::class)
object PluxyPlayerFactory {

    fun build(
        context: Context,
        buffer: BufferConfig,
        baseUrl: String = PluxyApplication.serverBaseUrl(context),
        sessionId: String? = null,
        onBytesTransferred: ((Int) -> Unit)? = null,
    ): ExoPlayer {
        // ---- 1. LoadControl : tampon adapté à la mémoire réelle ------------ //
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runtimeBuffer = PlaybackMemoryPolicy.resolve(
            requested = buffer,
            memoryClassMb = activityManager.memoryClass,
            isTelevision = isTelevision(context),
            isLowRamDevice = activityManager.isLowRamDevice,
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                runtimeBuffer.minBufferMs,
                runtimeBuffer.maxBufferMs,
                runtimeBuffer.bufferForPlaybackMs,
                runtimeBuffer.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(runtimeBuffer.targetBufferBytes)
            // Le plafond octets doit rester effectif : privilégier systématiquement
            // la durée permettait à un flux 4K de dépasser largement le budget mémoire.
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(
                runtimeBuffer.backBufferMs,
                runtimeBuffer.retainBackBufferFromKeyframe,
            )
            .build()

        // ---- 2. Renderers : MATÉRIEL d'abord, logiciel en REPLI ------------ //
        // Les décodeurs matériels restent prioritaires. Les extensions ne sont
        // disponibles que si elles sont effectivement incluses dans l'application.
        val audioMode = AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(context))
        val renderers = object : DefaultRenderersFactory(context) {
            @Suppress("DEPRECATION")
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? {
                if (audioMode != AudioOutputMode.PASSTHROUGH)
                    return super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
                // With Context, Media3 ignores static capabilities. Its legacy builder
                // without Context deliberately keeps the user-requested override.
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities(intArrayOf(
                        C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
                        C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD, C.ENCODING_AC4,
                    ), 8))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build().also { sink ->
                        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        val priorities = buildList {
                            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.media.AudioDeviceInfo.TYPE_HDMI_EARC)
                            add(android.media.AudioDeviceInfo.TYPE_HDMI_ARC)
                            add(android.media.AudioDeviceInfo.TYPE_HDMI)
                            add(android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL)
                        }
                        val digital = manager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                            ?.filter { it.type in priorities }?.minByOrNull { priorities.indexOf(it.type) }
                        if (digital != null) sink.setPreferredDevice(digital)
                        com.pluxy.tv.util.Logger.log("audio", "passthrough statique Dolby/DTS, sortie préférée=${digital?.type ?: "système"}")
                    }
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // ---- 3. Source de données HTTP via OkHttp (Range + keep-alive) ----- //
        val serverHost = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
        val sameServerOnly = Interceptor { chain ->
            if (!LanUrlPolicy.isSameServer(baseUrl, chain.request().url.toString())) {
                throw IOException("Flux hors du serveur Pluxy sélectionné")
            }
            chain.proceed(chain.request())
        }
        val ok = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    if (!hostname.equals(serverHost, ignoreCase = true)) {
                        throw UnknownHostException("Hôte différent du serveur Pluxy sélectionné")
                    } else {
                        LanUrlPolicy.resolveTrustedHost(hostname)
                    }
                }
            )
            .addInterceptor(sameServerOnly)
            .addNetworkInterceptor(sameServerOnly)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(ok)
            .setDefaultRequestProperties(buildMap {
                put("User-Agent", "PluxyTV/1.0")
                sessionId?.let { put("X-Pluxy-Session", it) }
            })
        if (onBytesTransferred != null) httpFactory.setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork && bytesTransferred > 0) onBytesTransferred(bytesTransferred)
            }
        })

        // Tolérance aux erreurs de chargement : un segment transcodé peut renvoyer un
        // 503 passager (NVENC momentanément en retard sur une scène lourde). On RÉESSAIE
        // plusieurs fois ce segment au lieu de faire échouer toute la lecture (écran noir).
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(/* minRetryCount */ 6) {
            override fun getRetryDelayMsFor(info: LoadErrorInfo): Long {
                if (super.getRetryDelayMsFor(info) == C.TIME_UNSET) return C.TIME_UNSET
                val status = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (PlaybackFailurePolicy.requiresUserActionForPath(
                        info.loadEventInfo.dataSpec.uri.path, status,
                    )) return C.TIME_UNSET
                // Backoff court et borné : on laisse au serveur le temps de produire.
                return (1000L * (info.errorCount)).coerceAtMost(4000L)
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)

        // ---- 4. Sélecteur de pistes --------------------------------------- //
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // TUNNELING DÉSACTIVÉ. Le tunneling couple audio+vidéo dans une
                    // session matérielle fragile : après un changement de sortie audio
                    // (retour sur l'app depuis une AUTRE source HDMI, (re)négociation
                    // ARC/eARC), la piste audio tunnelée reste MUETTE ("Error -32 during
                    // AudioTrack native read"). Le HDR10 (HEVC Main10) N'A PAS besoin du
                    // tunneling : la dalle bascule en HDR via les métadonnées couleur du
                    // décodeur vers la Surface. On privilégie donc la FIABILITÉ du son.
                    .setTunnelingEnabled(false)
                    .setConstrainAudioChannelCountToDeviceCapabilities(audioMode != AudioOutputMode.PASSTHROUGH)
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H265)
                    // Langue audio préférée = réglage utilisateur (fiche film).
                    .setPreferredAudioLanguage(
                        PluxyApplication.audioLang(context).ifBlank { null }
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
                it.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
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

}
