package com.pluxy.receiver.cast

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.pluxy.receiver.core.ReceiverController
import com.pluxy.receiver.core.ReceiverIdentity
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.RemoteMedia
import com.pluxy.receiver.core.RemotePlaybackPhase
import com.pluxy.receiver.core.RemotePlaybackStatus
import com.pluxy.receiver.core.UnsupportedFormatComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class GoogleCastController(
    context: Context,
    private val preferredDevice: ReceiverDevice? = null,
) : ReceiverController {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(RemotePlaybackStatus(device = preferredDevice))
    override val status = mutableStatus.asStateFlow()
    private var player: RemoteCastPlayer? = null
    private var positionJob: Job? = null
    private val identity = ReceiverIdentity(preferredDevice?.id)
    @Volatile private var closed = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)

        override fun onPlayerError(error: PlaybackException) {
            positionJob?.cancel()
            val component = unsupportedComponent(error, mutableStatus.value.media)
            mutableStatus.value = mutableStatus.value.copy(
                phase = RemotePlaybackPhase.ERROR,
                error = error.message?.take(220) ?: "Format Google Cast non pris en charge",
                formatUnsupported = component != null,
                unsupportedComponent = component,
            )
        }
    }

    override suspend fun load(media: RemoteMedia, playWhenReady: Boolean) = onMain {
        val discoveredDevice = GoogleCastSupport.currentDevice(appContext)
            ?: throw IllegalStateException("Choisissez d'abord un appareil Google Cast")
        identity.load(discoveredDevice.id)
        val device = discoveredDevice
        val remote = ensurePlayer()
        mutableStatus.value = RemotePlaybackStatus(
            device = device,
            media = media,
            phase = RemotePlaybackPhase.CONNECTING,
            durationMs = media.durationMs,
        )
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .apply { media.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(media.url)
            .setMimeType(media.mimeType)
            .setMediaMetadata(metadata)
            .build()
        remote.setMediaItem(item)
        remote.prepare()
        if (playWhenReady) remote.play() else remote.pause()
        refresh(remote)
        startPositionUpdates()
    }

    override suspend fun play() = onMain {
        val remote = requirePlayer()
        remote.play()
        refresh(remote)
        startPositionUpdates()
    }

    override suspend fun pause() = onMain {
        val remote = requirePlayer()
        remote.pause()
        refresh(remote)
    }

    override suspend fun seekTo(positionMs: Long) = onMain {
        val remote = requirePlayer()
        remote.seekTo(positionMs.coerceAtLeast(0))
        refresh(remote)
    }

    override suspend fun stop() = onMain {
        if (ownsCurrentSession()) player?.stop()
        positionJob?.cancel()
        mutableStatus.value = mutableStatus.value.copy(
            phase = RemotePlaybackPhase.ENDED,
            positionMs = 0,
            error = null,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.launch {
            positionJob?.cancel()
            if (ownsCurrentSession()) player?.stop()
            player?.removeListener(listener)
            player?.release()
            player = null
            scope.cancel()
        }
    }

    private fun ensurePlayer(): RemoteCastPlayer {
        check(!closed) { "Session Google Cast fermée" }
        player?.let { return it }
        GoogleCastSupport.initialize(appContext)
        check(Cast.getSingletonInstance(appContext).currentCastSession != null) {
            "Aucune session Google Cast active"
        }
        return RemoteCastPlayer.Builder(appContext).build().also {
            it.addListener(listener)
            player = it
        }
    }

    private fun ownsCurrentSession(): Boolean = identity.owns(GoogleCastSupport.currentDevice(appContext)?.id)

    private fun requirePlayer(): RemoteCastPlayer {
        check(!closed && ownsCurrentSession()) { "La session Google Cast a changé ou s'est déconnectée." }
        return player ?: throw IllegalStateException("Aucun média Google Cast chargé")
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                delay(1_000)
                player?.let(::refresh)
            }
        }
    }

    private fun refresh(remote: Player) {
        val current = mutableStatus.value
        val actualDevice = GoogleCastSupport.currentDevice(appContext)
        if (identity.loaded && !identity.owns(actualDevice?.id)) {
            positionJob?.cancel(); positionJob = null
            mutableStatus.value = current.copy(device = actualDevice ?: current.device,
                phase = RemotePlaybackPhase.ERROR, error = "La session Google Cast a changé ou s'est déconnectée.")
            return
        }
        val component = remote.playerError?.let { unsupportedComponent(it, current.media) }
        val phase = when {
            remote.playerError != null -> RemotePlaybackPhase.ERROR
            remote.playbackState == Player.STATE_BUFFERING -> RemotePlaybackPhase.BUFFERING
            remote.playbackState == Player.STATE_ENDED -> RemotePlaybackPhase.ENDED
            remote.isPlaying -> RemotePlaybackPhase.PLAYING
            remote.playbackState == Player.STATE_READY -> RemotePlaybackPhase.PAUSED
            current.media != null -> RemotePlaybackPhase.CONNECTING
            else -> RemotePlaybackPhase.IDLE
        }
        mutableStatus.value = current.copy(
            device = actualDevice ?: current.device,
            phase = phase,
            positionMs = remote.currentPosition.coerceAtLeast(0),
            durationMs = remote.duration.takeIf { it > 0 } ?: current.media?.durationMs ?: 0,
            error = remote.playerError?.message?.take(220),
            formatUnsupported = component != null,
            unsupportedComponent = component,
        )
        if (phase == RemotePlaybackPhase.ENDED || phase == RemotePlaybackPhase.ERROR) {
            positionJob?.cancel()
            positionJob = null
        }
    }

    private fun unsupportedComponent(
        error: PlaybackException,
        media: RemoteMedia?,
    ): UnsupportedFormatComponent? = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> UnsupportedFormatComponent.CONTAINER
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            when (media?.kind) {
                "audio" -> UnsupportedFormatComponent.AUDIO
                "photo" -> UnsupportedFormatComponent.IMAGE
                else -> UnsupportedFormatComponent.VIDEO
            }
        else -> null
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

}
