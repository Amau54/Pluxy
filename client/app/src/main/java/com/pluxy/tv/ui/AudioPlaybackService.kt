package com.pluxy.tv.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.BufferConfig
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.networkOrNull
import com.pluxy.tv.player.PluxyPlayerFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as ExoMediaItem

/** The service owns playback; an activity can leave without releasing the music player. */
@OptIn(UnstableApi::class)
class AudioPlaybackService : Service() {
    data class UiState(
        val serverUrl: String = "",
        val player: ExoPlayer? = null,
        val current: MediaItem? = null,
        val tracks: List<MediaItem> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )
    inner class PlaybackBinder : Binder() { val service: AudioPlaybackService get() = this@AudioPlaybackService }
    private val binder = PlaybackBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mutableState = MutableStateFlow(UiState())
    val state = mutableState.asStateFlow()
    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private lateinit var session: MediaSession
    private var api: PluxyApi? = null
    private var serverUrl = ""
    private var initial: MediaItem? = null
    private var tracks: List<MediaItem> = emptyList()
    private var clientsBound = false
    private var stopping = false
    private var requestedPositionMs = 0L
    private var requestedPlayWhenReady = true

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.audio_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        session = MediaSession(this, "PluxyMusic").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { retryOrPlay() }
                override fun onPause() { player?.pause() }
                override fun onStop() { stopPlayback() }
                override fun onSkipToNext() { player?.seekToNextMediaItem() }
                override fun onSkipToPrevious() { player?.seekToPreviousMediaItem() }
                override fun onSkipToQueueItem(id: Long) { playTrack(id.toInt()) }
                override fun onSeekTo(pos: Long) { player?.seekTo(pos.coerceAtLeast(0L)) }
            })
            isActive = true
        }
        // Fulfil the foreground deadline before catalogue/runtime network requests.
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onBind(intent: Intent?): IBinder {
        clientsBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clientsBound = false
        if (stopping || player?.playbackState == Player.STATE_ENDED) stopSelf()
        return true
    }

    override fun onRebind(intent: Intent?) {
        clientsBound = true
        super.onRebind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> {
                val json = intent.getStringExtra(AudioPlayerActivity.EXTRA_ITEM)
                val item = runCatching { moshi.adapter(MediaItem::class.java).fromJson(json.orEmpty()) }.getOrNull()
                    ?.takeIf { it.kind == "audio" }
                if (item == null) {
                    stopPlayback()
                    return START_NOT_STICKY
                }
                // Recreating the UI also attaches while preparation is still in flight.
                if (intent.getBooleanExtra(AudioPlayerActivity.EXTRA_RESUME_SESSION, false) &&
                    !stopping && (player != null || loadJob?.isActive == true)
                ) return START_NOT_STICKY
                val owner = intent.getStringExtra(EXTRA_SERVER_URL) ?: PluxyApplication.serverBaseUrl(this)
                val url = runCatching { catalogueServerKey(owner) }.getOrNull()
                if (url == null) { stopPlayback(); return START_NOT_STICKY }
                load(item, intent.getLongExtra(EXTRA_POSITION, 0L), intent.getBooleanExtra(EXTRA_PLAY, true), url)
            }
            ACTION_PLAY -> retryOrPlay()
            ACTION_PAUSE -> player?.pause()
            ACTION_NEXT -> player?.seekToNextMediaItem()
            ACTION_PREVIOUS -> player?.seekToPreviousMediaItem()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun load(item: MediaItem, positionMs: Long, playWhenReady: Boolean, ownerUrl: String = serverUrl) {
        stopping = false
        initial = item
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedPlayWhenReady = playWhenReady
        loadJob?.cancel()
        api?.close()
        player?.release()
        player = null
        tracks = emptyList()
        serverUrl = ownerUrl
        val client = PluxyApi(serverUrl)
        api = client
        mutableState.value = UiState(serverUrl = serverUrl, current = item, loading = true)
        session.setQueue(emptyList())
        session.isActive = true
        startForeground(NOTIFICATION_ID, notification())
        publish()
        loadJob = scope.launch {
            try {
                val buffer = networkOrNull { client.clientRuntime().buffer } ?: DEFAULT_BUFFER
                val catalogue = networkOrNull {
                    CatalogueRepository.load(ownerUrl) {
                        val reader = PluxyApi(ownerUrl)
                        try { reader.listItems() } finally { reader.close() }
                    }
                }?.items.orEmpty()
                val ordered = withContext(Dispatchers.Default) { orderedAlbumTracks(catalogue, item) }
                tracks = ordered.takeIf { values -> values.any { it.id == item.id } } ?: listOf(item)
                val exo = PluxyPlayerFactory.build(this@AudioPlaybackService, buffer, serverUrl)
                player = exo
                exo.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true,
                )
                exo.setHandleAudioBecomingNoisy(true)
                exo.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (this@AudioPlaybackService.player !== player) return
                        if (stopping && player.playWhenReady) {
                            stopping = false
                            session.isActive = true
                            startForeground(NOTIFICATION_ID, notification())
                        }
                        publish()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (player !== exo) return
                        mutableState.value = mutableState.value.copy(error = error.message ?: error.errorCodeName, loading = false)
                        publish()
                    }
                })
                val playlist = tracks.map { media ->
                    ExoMediaItem.Builder().setUri(client.abs("/stream/direct/" + media.id)).setMediaId(media.id)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder().setTitle(media.title)
                                .setArtist(media.artist).setAlbumTitle(media.album).build(),
                        ).build()
                }
                exo.setMediaItems(playlist, tracks.indexOfFirst { it.id == item.id }.coerceAtLeast(0), positionMs.coerceAtLeast(0L))
                exo.prepare()
                exo.playWhenReady = playWhenReady
                session.setQueue(tracks.mapIndexed { index, media ->
                    MediaSession.QueueItem(
                        MediaDescription.Builder().setMediaId(media.id).setTitle(media.title)
                            .setSubtitle(listOfNotNull(media.artist, media.album).joinToString(" · ")).build(), index.toLong(),
                    )
                })
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                player?.release()
                player = null
                mutableState.value = UiState(serverUrl = serverUrl, current = item, tracks = tracks, error = failure.message.orEmpty())
                publish()
            } finally {
                client.close()
                if (api === client) api = null
            }
        }
    }

    fun retry() {
        val exo = player
        if (exo == null) initial?.let { load(it, requestedPositionMs, requestedPlayWhenReady) } else {
            mutableState.value = mutableState.value.copy(error = null)
            exo.prepare()
            publish()
        }
    }

    private fun retryOrPlay() {
        if (stopping) {
            stopping = false
            session.isActive = true
            startForeground(NOTIFICATION_ID, notification())
        }
        val exo = player
        if (exo == null) initial?.let { load(it, requestedPositionMs, true) } else {
            mutableState.value = mutableState.value.copy(error = null)
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            if (exo.playbackState == Player.STATE_ENDED) exo.seekToDefaultPosition()
            exo.play()
        }
    }

    fun playTrack(index: Int) {
        val exo = player ?: return
        if (index !in tracks.indices) return
        mutableState.value = mutableState.value.copy(error = null)
        exo.seekTo(index, 0L)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    fun stopPlayback() {
        stopping = true
        loadJob?.cancel()
        player?.pause()
        player?.stop()
        mutableState.value = mutableState.value.copy(loading = false)
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_STOPPED, player?.currentPosition?.coerceAtLeast(0L) ?: 0L, 0f).build())
        session.isActive = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    // MediaStyle always carries this service's MediaSession token; these media
    // notifications are exempt from POST_NOTIFICATIONS on Android 13+.
    @SuppressLint("NotificationPermission")
    private fun publish() {
        if (stopping) return
        val exo = player
        val current = tracks.getOrNull(exo?.currentMediaItemIndex ?: -1) ?: initial
        val error = if (exo?.playbackState == Player.STATE_READY) null else mutableState.value.error
        mutableState.value = mutableState.value.copy(
            player = exo, current = current, tracks = tracks,
            loading = if (exo == null) mutableState.value.loading && error == null else exo.playbackState == Player.STATE_BUFFERING,
            error = error,
        )
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, current?.id)
            .putString(MediaMetadata.METADATA_KEY_TITLE, current?.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, current?.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, current?.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, exo?.duration?.takeIf { it > 0L } ?: 0L).build())
        val playbackState = when {
            error != null -> PlaybackState.STATE_ERROR
            exo == null || exo.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            exo.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            exo.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM)
            .setActiveQueueItemId((exo?.currentMediaItemIndex ?: 0).toLong())
            .setState(playbackState, exo?.currentPosition?.coerceAtLeast(0L) ?: 0L, exo?.playbackParameters?.speed ?: 1f)
            .apply { if (error != null) setErrorMessage(error) }.build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        if (!clientsBound && exo?.playbackState == Player.STATE_ENDED) stopPlayback()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, AudioPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val current = state.value.current ?: initial
        val returnIntent = Intent(this, AudioPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AudioPlayerActivity.EXTRA_RESUME_SESSION, true)
            .putExtra(EXTRA_SERVER_URL, serverUrl)
        current?.let { returnIntent.putExtra(AudioPlayerActivity.EXTRA_ITEM, moshi.adapter(MediaItem::class.java).toJson(it)) }
        val content = PendingIntent.getActivity(this, 0, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exo = player
        val playing = exo?.playWhenReady == true && exo.playbackState != Player.STATE_IDLE &&
            exo.playbackState != Player.STATE_ENDED && state.value.error == null
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_music_placeholder)
            .setContentTitle(current?.title ?: getString(R.string.audio_preparing))
            .setContentText(listOfNotNull(current?.artist, current?.album).joinToString(" · "))
            .setContentIntent(content).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing || state.value.loading).setDeleteIntent(actionIntent(ACTION_STOP))
            .addAction(R.drawable.ic_player_rewind, getString(R.string.audio_previous), actionIntent(ACTION_PREVIOUS))
            .addAction(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                getString(if (playing) R.string.audio_pause else R.string.audio_play), actionIntent(if (playing) ACTION_PAUSE else ACTION_PLAY))
            .addAction(R.drawable.ic_player_forward, getString(R.string.audio_next), actionIntent(ACTION_NEXT))
            .addAction(R.drawable.ic_close, getString(R.string.audio_stop), actionIntent(ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        api?.close()
        player?.release()
        player = null
        session.isActive = false
        session.release()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOAD = "com.pluxy.tv.music.LOAD"
        private const val ACTION_PLAY = "com.pluxy.tv.music.PLAY"
        private const val ACTION_PAUSE = "com.pluxy.tv.music.PAUSE"
        private const val ACTION_NEXT = "com.pluxy.tv.music.NEXT"
        private const val ACTION_PREVIOUS = "com.pluxy.tv.music.PREVIOUS"
        private const val ACTION_STOP = "com.pluxy.tv.music.STOP"
        const val EXTRA_POSITION = "music_position"
        const val EXTRA_PLAY = "music_play_when_ready"
        const val EXTRA_SERVER_URL = "music_server_url"
        private const val CHANNEL_ID = "pluxy_music"
        private const val NOTIFICATION_ID = 2104
        private val DEFAULT_BUFFER = BufferConfig(
            minBufferMs = 60_000, maxBufferMs = 120_000, bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 8_000, targetBufferBytesMb = 64, backBufferMs = 15_000,
        )
    }
}
