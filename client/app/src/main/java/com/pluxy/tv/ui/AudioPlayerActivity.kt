package com.pluxy.tv.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.MediaItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Activity controls the service player, which survives Home, locking, and UI recreation. */
@OptIn(UnstableApi::class)
class AudioPlayerActivity : ThemedActivity() {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var initial: MediaItem
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var errorView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var controls: PlayerControlView
    private lateinit var coverView: ImageView
    private lateinit var retryButton: Button
    private lateinit var queueButton: ImageButton
    private var service: AudioPlaybackService? = null
    private var bound = false
    private var observeJob: Job? = null
    private var requestedSession = false
    private var restoreSession = false
    private var positionMs = 0L
    private var playWhenReady = true
    private var artworkKey: String? = null
    private var ownerServerUrl = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val playback = (binder as AudioPlaybackService.PlaybackBinder).service
            service = playback
            observeJob?.cancel()
            observeJob = lifecycleScope.launch {
                playback.state.collect { render(it) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            observeJob?.cancel()
            controls.player = null
            errorView.setText(R.string.audio_service_disconnected)
            errorView.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)
        val json = savedInstanceState?.getString(STATE_ITEM) ?: intent.getStringExtra(EXTRA_ITEM)
            ?: run { finish(); return }
        initial = runCatching { moshi.adapter(MediaItem::class.java).fromJson(json) }.getOrNull()
            ?.takeIf { it.kind == "audio" } ?: run { finish(); return }
        positionMs = savedInstanceState?.getLong(STATE_POSITION, 0L) ?: 0L
        playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY, true) ?: true
        restoreSession = savedInstanceState != null || intent.getBooleanExtra(EXTRA_RESUME_SESSION, false)
        ownerServerUrl = savedInstanceState?.getString(STATE_SERVER_URL)
            ?: intent.getStringExtra(AudioPlaybackService.EXTRA_SERVER_URL)
            ?: PluxyApplication.serverBaseUrl(this)
        titleView = findViewById(R.id.audioTitle)
        metaView = findViewById(R.id.audioMeta)
        errorView = findViewById(R.id.audioError)
        loading = findViewById(R.id.audioLoading)
        controls = findViewById(R.id.audioControls)
        val density = resources.displayMetrics.density
        val sidePadding = resources.getDimensionPixelSize(R.dimen.auxiliary_viewer_pad)
        val deckWidth = minOf(
            (480 * density).toInt(),
            (resources.configuration.screenWidthDp * density).toInt() - sidePadding * 2,
        ).coerceAtLeast(1)
        controls.layoutParams = controls.layoutParams.apply { width = deckWidth }
        coverView = findViewById(R.id.audioCover)
        retryButton = findViewById(R.id.audioRetry)
        queueButton = findViewById(R.id.audioQueue)
        retryButton.setOnClickListener {
            service?.retry() ?: requestPlayback()
        }
        queueButton.setOnClickListener { showQueue() }
        findViewById<ImageButton>(R.id.audioStop).apply {
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener { service?.stopPlayback(); finish() }
        }
        titleView.text = initial.title
        titleView.contentDescription = initial.title
    }

    private fun requestPlayback() {
        val intent = Intent(this, AudioPlaybackService::class.java)
            .setAction(AudioPlaybackService.ACTION_LOAD)
            .putExtra(EXTRA_ITEM, moshi.adapter(MediaItem::class.java).toJson(initial))
            .putExtra(EXTRA_RESUME_SESSION, requestedSession || restoreSession)
            .putExtra(AudioPlaybackService.EXTRA_POSITION, positionMs)
            .putExtra(AudioPlaybackService.EXTRA_PLAY, playWhenReady)
            .putExtra(AudioPlaybackService.EXTRA_SERVER_URL, ownerServerUrl)
        ContextCompat.startForegroundService(this, intent)
        requestedSession = true
    }

    override fun onStart() {
        super.onStart()
        if (!::initial.isInitialized) return
        requestPlayback()
        bound = bindService(Intent(this, AudioPlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }

    private fun render(state: AudioPlaybackService.UiState) {
        controls.player = state.player
        controls.show()
        state.current?.let { item ->
            initial = item
            ownerServerUrl = state.serverUrl
            titleView.text = item.title
            titleView.contentDescription = item.title
            metaView.text = listOfNotNull(item.artist, item.album).joinToString(" · ")
            metaView.visibility = if (metaView.text.isBlank()) View.GONE else View.VISIBLE
            val key = state.serverUrl + "/" + item.id
            if (key != artworkKey) {
                artworkKey = key
                if (state.serverUrl.trimEnd('/') == PluxyApplication.serverBaseUrl(this).trimEnd('/')) {
                    val cover = item.thumbnailUrl?.let { relative ->
                        runCatching { LanUrlPolicy.resolveSameServer(state.serverUrl, relative) }.getOrNull()
                    }
                    coverView.load(cover, LanMediaImages.loader(this)) {
                        placeholder(R.drawable.ic_music_placeholder)
                        error(R.drawable.ic_music_placeholder)
                    }
                } else coverView.setImageResource(R.drawable.ic_music_placeholder)
            }
        }
        loading.visibility = if (state.loading) View.VISIBLE else View.GONE
        val hadError = errorView.visibility == View.VISIBLE
        errorView.text = state.error?.let { getString(R.string.playback_error, it) }.orEmpty()
        errorView.visibility = if (state.error != null) View.VISIBLE else View.GONE
        retryButton.visibility = if (state.error != null) View.VISIBLE else View.GONE
        if (state.error != null && !hadError) retryButton.requestFocus()
        queueButton.visibility = if (state.tracks.isNotEmpty()) View.VISIBLE else View.GONE
        queueButton.contentDescription = getString(R.string.audio_queue_count, state.tracks.size)
        TooltipCompat.setTooltipText(queueButton, queueButton.contentDescription)
    }

    private fun showQueue() {
        val playback = service ?: return
        val state = playback.state.value
        val tracks = state.tracks
        if (tracks.isEmpty()) return
        val multipleDiscs = tracks.any { (it.discNumber ?: 1) > 1 }
        val labels = tracks.map { item ->
            listOfNotNull(
                item.discNumber?.takeIf { it > 0 && multipleDiscs }?.let { getString(R.string.audio_disc_number, it) },
                item.trackNumber?.takeIf { it > 0 }?.toString(), item.title,
            ).joinToString(" · ")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_queue_title)
            .setSingleChoiceItems(labels.toTypedArray(), state.player?.currentMediaItemIndex ?: 0) { dialog, index ->
                if (playback.state.value.tracks == tracks) playback.playTrack(index)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun rememberPlayback() {
        service?.state?.value?.let { state ->
            if (state.serverUrl.isNotBlank()) ownerServerUrl = state.serverUrl
            state.current?.let { initial = it }
            state.player?.let {
                positionMs = it.currentPosition.coerceAtLeast(0L)
                playWhenReady = it.playWhenReady
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberPlayback()
        if (::initial.isInitialized) outState.putString(STATE_ITEM, moshi.adapter(MediaItem::class.java).toJson(initial))
        outState.putLong(STATE_POSITION, positionMs)
        outState.putBoolean(STATE_PLAY, playWhenReady)
        outState.putString(STATE_SERVER_URL, ownerServerUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        rememberPlayback()
        observeJob?.cancel()
        observeJob = null
        if (::controls.isInitialized) controls.player = null
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    companion object {
        const val EXTRA_ITEM = "extra_audio_item_json"
        const val EXTRA_RESUME_SESSION = "extra_audio_resume_session"
        private const val STATE_ITEM = "audio_current_item"
        private const val STATE_POSITION = "audio_position"
        private const val STATE_PLAY = "audio_play_when_ready"
        private const val STATE_SERVER_URL = "audio_server_url"
    }
}
