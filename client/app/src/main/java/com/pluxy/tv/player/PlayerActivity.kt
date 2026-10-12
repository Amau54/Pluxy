package com.pluxy.tv.player

import com.pluxy.tv.ui.ThemedActivity
import com.pluxy.tv.ui.CompactChoiceMenu
import com.pluxy.tv.ui.LibraryOrganizer
import android.content.Context
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.pluxy.tv.ui.InAppNotice as Toast
import kotlin.math.abs
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.BufferConfig
import com.pluxy.tv.api.PlaybackDecision
import com.pluxy.tv.api.DeviceProfile
import com.pluxy.tv.api.MediaItem as PluxyItem
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.LibraryRequest
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.ui.CatalogueRepository
import com.pluxy.tv.ui.episodeCode
import com.pluxy.tv.ui.cleanEpisodeTitle
import coil.load
import com.pluxy.tv.api.TvProfileStore
import com.pluxy.tv.util.Logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ThemedActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var controlsOverlay: View
    private lateinit var mediaTitleView: TextView
    private lateinit var modeTextView: TextView
    private lateinit var timeline: DefaultTimeBar
    private lateinit var timePositionView: TextView
    private lateinit var timeDurationView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var loadingIndicator: View
    private lateinit var playbackErrorPanel: View
    private lateinit var playbackErrorMessage: TextView
    private lateinit var retryPlaybackButton: Button
    private var compactPlayerControls: Boolean? = null
    private var playerChoiceMenu: PopupWindow? = null
    private var playerChoiceMenuShowing = false
    private var playerChoiceMenuGeneration = 0
    private var terminalPlaybackError = false
    private var activityStarted = false
    private var timelineScrubbing = false
    private var player: ExoPlayer? = null
    private val remoteKeyRouter = TvPlaybackKeyRouter()
    private var mediaSession: MediaSession? = null
    private lateinit var api: PluxyApi
    private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    private val itemAdapter by lazy { moshi.adapter(PluxyItem::class.java) }
    private val sessionAdapter by lazy { moshi.adapter(PlaybackSessionState::class.java) }
    private var progressSequence = 0L
    private var playbackSpeed = 1f
    private var subtitlesDisabled = false
    private var audioChoice: PlaybackTrackChoice? = null
    private var subtitleChoice: PlaybackTrackChoice? = null
    private lateinit var item: PluxyItem
    private val tvProfileStore by lazy { TvProfileStore(this) }
    private var forceDirectForLaunch = false
    private var strategyOverride: PlaybackStrategy? = null
    private var audioFallbackLevel = 0
    private var failedAudioCodec: String? = null
    private var audioAdaptationPending = false
    private var startFromBeginning = false
    private var autoResume = false
    private var networkConstraintRecorded = false
    private var stableProfileRecorded = false

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retries = 0

    // État conservé entre onStop/onStart (recréation du player sans tout refaire).
    private var buffer: BufferConfig? = null
    private val attempts = mutableListOf<PlaybackAttempt>()
    private var attemptIdx = 0
    private var savedPositionMs = 0L
    private var savedPlayWhenReady = true
    private var resumeHandled = false
    private var modeLabel = ""
    private var activeStrategy = PlaybackStrategy.AUTO
    private var transcodingAvailable = false
    private var conversionProfiles = emptyList<com.pluxy.tv.api.ConversionProfile>()
    private var conversionBufferActive = false
    private val adaptiveConversion get() = ConversionPolicy.adaptive(activeStrategy, PluxyApplication.conversionQuality(this))
    private var directStreamSeekAvailable = false
    private var serverKind = "pc"
    private var reinforcedBufferActive = false
    private var networkLimitWarned = false
    private var decisionToneMapped = false
    private var prepareJob: Job? = null
    private var nextEpisodeHandled = false
    private var nextEpisodeDialog: Dialog? = null
    private var nextEpisodeJob: Job? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var prefetchedNextEpisode: PluxyItem? = null
    private var nextEpisodePrefetched = false
    private var resumeDialog: AlertDialog? = null
    private var nextEpisodeTick: Runnable? = null
    private val adaptiveHistory = ArrayDeque<Int>()
    private var lastAdaptiveChangeAtMs = 0L
    private var lastQualifiedStallAtMs = 0L
    private val isTranscode get() = attempts.getOrNull(attemptIdx)?.delivery in setOf("hls", "conversion")

    // ---- Bascule adaptative Pluxy au débit réseau ------------------------- //
    // Un rebuffering (le tampon se vide -> le lecteur se fige pour recharger) n'est PAS
    // une erreur : la chaîne de repli sur erreur ne s'en occupe pas. On compte donc les
    // rebufferings réels (hors démarrage et hors seek) sur une fenêtre glissante ; au-delà
    // d'un seuil, on descend d'un palier de qualité pour tenir le débit réseau :
    //   Direct Play  ->  HLS « main »  (HEVC/HDR transcodé à débit PLAFONNÉ, qualité quasi
    //                                    intacte)  ->  HLS « compat » (H.264 1080p, sûr).
    private val rebufferTimes = ArrayDeque<Long>()
    private var wasReady = false
    private var lastSeekAtMs = 0L
    // Horodatage du DERNIER (re)démarrage de lecture continue (entrée en READY).
    // Un rebuffering ne compte comme « réseau » que si la lecture tournait depuis
    // assez longtemps : une salve de buffering juste APRÈS un seek (tampon mince le
    // temps que le transcodage reprenne son avance) n'est PAS un souci réseau.
    private var playStartedAtMs = 0L
    // Temps CUMULÉ passé en rebuffering réseau qualifié sur la fenêtre glissante.
    // Capte la saturation SÉVÈRE : quand le débit s'effondre, les rebufferings sont
    // rares mais LONGS -> le simple comptage d'événements ne les fait pas atteindre le
    // seuil dans la fenêtre, alors que le cumul de durée, lui, le détecte.
    private var networkStallStartMs = 0L
    private val stallWindow = ArrayDeque<Pair<Long, Long>>()   // (instant de fin, durée ms)

    // ---- Chien de garde de chargement -------------------------------------- //
    // Un chargement (surtout en lecture directe) peut RESTER bloqué en BUFFERING sans
    // jamais lever d'erreur : le flux ne démarre pas, ou s'arrête de charger. Sans
    // garde-fou, la chaîne de repli ne s'active pas et l'écran reste figé. On surveille
    // donc la progression du tampon : pas d'avancée pendant X s (ou buffering trop long)
    // -> on bascule sur la tentative suivante (repli HLS) ou on recharge.
    private var bufWatchLastPos = 0L
    private var transferBytes = java.util.concurrent.atomic.AtomicLong()
    private var bufWatchLastBytes = 0L
    private var bufWatchProgressAtMs = 0L
    private var bufWatchStartedAtMs = 0L
    private var watchdogReloads = 0

    // ---- Position robuste -------------------------------------------------- //
    // DERNIÈRE position connue VALIDE (> 0, lecture établie). `currentPosition` peut
    // renvoyer 0 transitoirement (juste après prepare/seek, en BUFFERING) ; recharger
    // ou re-seeker à partir de ce 0 fait « sauter au début ». On s'appuie donc sur cette
    // valeur mémorisée pour TOUTE reprise/repli/seek -> plus de retour au début parasite.
    private var lastGoodPositionMs = 0L
    private var lastBuildAtMs = 0L
    private var streamPositionOffsetMs = 0L
    private var pendingSeekTargetMs: Long? = null
    private var seekRebuildRunnable: Runnable? = null

    // La playlist VOD couvre tout le film : position et durée sont absolues.
    private fun absolutePositionMs(): Long = PlaybackSeekPolicy.absolutePosition(
        streamPositionOffsetMs, player?.currentPosition ?: 0L)
    private val durationMs get() = (item.duration * 1000).toLong()

    /** Position FIABLE pour reprendre/recharger/seeker : la position courante si elle est
     *  plausible, sinon la dernière bonne position connue (jamais un 0 transitoire). */
    private fun reliablePositionMs(): Long {
        pendingSeekTargetMs?.let { return it }
        // Pendant la reprise post-seek, Media3 peut encore publier l'ANCIENNE position.
        // La cible mémorisée reste alors la seule base fiable pour un second geste rapide.
        if (SystemClock.elapsedRealtime() - lastSeekAtMs <= SEEK_GRACE_MS) {
            return maxOf(lastGoodPositionMs, savedPositionMs)
        }
        val cur = if (player != null) absolutePositionMs() else -1L
        return if (cur > 1_000L) cur else maxOf(lastGoodPositionMs, savedPositionMs)
    }

    /** Suivi continu (1 s) de la dernière bonne position. */
    private val positionTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.playbackState != Player.STATE_IDLE) {
                val pos = absolutePositionMs()
                if (p.playbackState == Player.STATE_READY && pendingSeekTargetMs?.let { kotlin.math.abs(pos - it) < 2_000L } == true) {
                    pendingSeekTargetMs = null
                }
                if (pos > 0 && p.playbackState == Player.STATE_READY && pendingSeekTargetMs == null && SystemClock.elapsedRealtime() - lastSeekAtMs > SEEK_GRACE_MS) {
                    lastGoodPositionMs = pos
                }
                if (!isSeeking && !timelineScrubbing) updateTimeline(pendingSeekTargetMs ?: pos)
                updatePlayPauseButton()
                updateMediaSessionPlaybackState()
                maybeRecoverQuality()
                updateSkipSegment()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (!isSeeking &&
            !timelineScrubbing &&
            shouldAutoHideControls() &&
            ::controlsOverlay.isInitialized
        ) {
            controlsOverlay.animate().cancel()
            controlsOverlay.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction {
                    if (controlsOverlay.alpha == 0f) {
                        controlsOverlay.visibility = View.GONE
                        updateSubtitleClearance()
                        playerView.requestFocus()
                    }
                }
                .start()
        }
    }

    // ---- Récupération audio sur changement de sortie ----------------------- //
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = onAudioRouteChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onAudioRouteChanged()
    }
    private val audioRecreateTimes = ArrayDeque<Long>()
    private val audioRecoverRunnable = Runnable {
        if (!isFinishing && player != null) {
            Logger.log("audio", "changement de sortie audio -> recréation du lecteur (son)")
            audioRecreateTimes.addLast(SystemClock.elapsedRealtime())
            audioFallbackLevel = 0
            failedAudioCodec = null
            reprepareForStrategy(activeStrategy)
        }
    }

    /** Après un changement de route audio (retour sur l'app depuis une AUTRE source HDMI,
     *  (re)négociation ARC/eARC), le rendu audio peut rester MUET. On recrée proprement le
     *  lecteur à la position courante -> puits audio sain. Ignoré si on vient justement de
     *  (re)construire le lecteur (le cycle onStart l'a déjà fait) -> pas de double-recréation. */
    private fun onAudioRouteChanged() {
        if (player == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBuildAtMs < 3_000) return
        // Anti-emballement : si la sortie audio « flappe » (ARC instable), on BORNE le
        // nombre de recréations sur une fenêtre — chaque recréation réinitialise les filets
        // de sécurité (chien de garde / repli) ; en boucle, un flux réellement bloqué ne
        // remonterait jamais son erreur. Au-delà, on laisse ExoPlayer récupérer seul.
        while (audioRecreateTimes.isNotEmpty() && now - audioRecreateTimes.first() > 60_000)
            audioRecreateTimes.removeFirst()
        if (audioRecreateTimes.size >= 3) {
            Logger.log("audio", "sortie audio instable -> recréations bornées (3/60s)")
            return
        }
        handler.removeCallbacks(audioRecoverRunnable)
        handler.postDelayed(audioRecoverRunnable, 500)
    }

    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Original · proportions conservées",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré",
    )
    private var aspectIdx = 0
    private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private val saveTick = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, 10_000) }
    }

    // ---- Seek progressif ------------------------------------------------- //
    // Accumule le décalage souhaité SANS toucher à ExoPlayer immédiatement.
    // Un Runnable différé commit le seek réel après une courte inactivité.
    // Pendant l'accumulation, une capsule attachée à la timeline affiche la cible
    // et le delta — elle ne masque plus le centre de l'image.
    private var isSeeking = false
    private var seekBaseMs = 0L        // position au début de la séquence de touches
    private var seekAccumMs = 0L       // delta prévisualisé (peut être négatif)
    private var seekDirection = 0
    private var seekHoldStartedAtMs = 0L
    private var seekKeyHeld = false

    private lateinit var seekOverlay: LinearLayout
    private lateinit var seekArrowView: TextView
    private lateinit var seekDeltaView: TextView
    private lateinit var seekTargetView: TextView
    private var seekPreviewJob: Job? = null
    private var seekPreviewBucket = -1L
    private var seekPreviewUnavailableItem: String? = null
    private val seekPreviewCache = android.util.LruCache<String, Bitmap>(12)

    private fun requestSeekPreview(positionMs: Long) {
        val bucket = positionMs.coerceAtLeast(0) / 10_000 * 10_000
        val image = findViewById<ImageView>(R.id.seekPreviewImage)
        if (bucket != seekPreviewBucket) {
            seekPreviewBucket = bucket
            image.setImageDrawable(null)
            image.visibility = View.GONE
        }
        val key = "${api.baseUrl}:${item.id}:$bucket"
        seekPreviewCache.get(key)?.let {
            image.setImageBitmap(it)
            image.visibility = View.VISIBLE
            return
        }
        if (seekPreviewUnavailableItem == item.id || seekPreviewJob?.isActive == true) return
        seekPreviewJob = lifecycleScope.launch {
            delay(350)
            var previous = -1L
            var attempts = 0
            while (seekOverlay.visibility == View.VISIBLE && activityStarted) {
                val requested = seekPreviewBucket
                val requestedItem = item.id
                val requestedApi = api
                if (previous != requested) { previous = requested; attempts = 0 }
                val cacheKey = "${requestedApi.baseUrl}:$requestedItem:$requested"
                val bytes = try {
                    requestedApi.seekPreview(requestedItem, requested)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: com.pluxy.tv.api.ApiException) {
                    if (error.code == 404 && item.id == requestedItem) seekPreviewUnavailableItem = requestedItem
                    return@launch
                } catch (_: Exception) {
                    return@launch
                }
                if (item.id != requestedItem || api !== requestedApi) return@launch
                if (bytes != null) {
                    val bitmap = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth !in 1..640 || bounds.outHeight !in 1..360) null
                        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                    }
                    if (bitmap != null) {
                        seekPreviewCache.put(cacheKey, bitmap)
                        if (requested == seekPreviewBucket && seekOverlay.visibility == View.VISIBLE) {
                            image.setImageBitmap(bitmap)
                            image.visibility = View.VISIBLE
                            return@launch
                        }
                    }
                }
                if (requested == seekPreviewBucket && ++attempts >= 4) return@launch
                delay(if (requested == seekPreviewBucket) 1_000 else 350)
            }
        }
    }

    /** Démarre un scrub visuel. Le lecteur réel ne reçoit aucun seek avant la relâche :
     *  cela évite les rafales de requêtes Range/HLS et les redémarrages FFmpeg. */
    private fun startSeekHold(dir: Int) {
        val p = player
        if (p == null && seekRebuildRunnable == null) return
        if (seekKeyHeld) return
        seekBaseMs = reliablePositionMs()
        seekAccumMs = SeekAcceleration.TAP_MS * dir
        seekDirection = dir
        seekHoldStartedAtMs = SystemClock.elapsedRealtime()
        seekKeyHeld = true
        isSeeking = true
        handler.removeCallbacks(hideControlsRunnable)
        updateHeldSeekPreview(p, seekHoldStartedAtMs)
        handler.postDelayed(seekPreviewRunnable, SEEK_PREVIEW_TICK_MS)
        armSeekReleaseFallback()
    }

    private fun updateHeldSeekPreview(p: ExoPlayer?, nowMs: Long) {
        val elapsed = (nowMs - seekHoldStartedAtMs).coerceAtLeast(0L)
        seekAccumMs = SeekAcceleration.distanceMs(elapsed) * seekDirection
        val dur = durationMs.takeIf { it > 0 } ?: (p?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val target = (seekBaseMs + seekAccumMs).coerceIn(0L, dur)
        showSeekOverlay(seekDirection, seekAccumMs, target, SeekAcceleration.speedLabel(elapsed))
    }

    private val seekPreviewRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (!seekKeyHeld) return
            updateHeldSeekPreview(p, SystemClock.elapsedRealtime())
            handler.postDelayed(this, SEEK_PREVIEW_TICK_MS)
        }
    }

    private val seekCommitRunnable = Runnable {
        val p = player
        if (p == null && seekRebuildRunnable == null) { cancelPendingSeek(); return@Runnable }
        handler.removeCallbacks(seekPreviewRunnable)
        seekKeyHeld = false
        val dur = durationMs.takeIf { it > 0 } ?: (p?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val target = (seekBaseMs + seekAccumMs).coerceIn(0L, dur)
        goToAbsolute(target)
        isSeeking = false
        seekAccumMs = 0L
        seekDirection = 0
        dismissSeekOverlay()
        scheduleControlsHide()
    }

    /**
     * HDMI-CEC adapters occasionally lose ACTION_UP. Re-arming this timeout on every
     * repeated ACTION_DOWN preserves long-press acceleration while guaranteeing that a
     * missing release can never leave the player stuck in seek mode.
     */
    private fun armSeekReleaseFallback() {
        handler.removeCallbacks(seekCommitRunnable)
        if (isSeeking) handler.postDelayed(seekCommitRunnable, SEEK_RELEASE_FALLBACK_MS)
    }

    private fun showSeekOverlay(dir: Int, accumMs: Long, targetMs: Long, speed: String) {
        seekOverlay.visibility = View.VISIBLE
        seekArrowView.text = if (dir > 0) "›" else "‹"
        val sign  = if (accumMs >= 0) "+" else "−"
        val secs  = abs(accumMs) / 1000
        val delta = when {
            secs < 60  -> "$sign ${secs} s"
            secs % 60 == 0L -> "$sign ${secs / 60} min"
            else -> "$sign ${secs / 60} min ${secs % 60} s"
        }
        seekDeltaView.text = getString(R.string.player_seek_delta, delta, speed)
        seekTargetView.text = fmtPlayerTime(targetMs)
        requestSeekPreview(targetMs)
        timeline.setPosition(targetMs)
        timePositionView.text = fmtPlayerTime(targetMs)
    }

    private fun dismissSeekOverlay() {
        seekPreviewJob?.cancel()
        seekPreviewJob = null
        seekPreviewBucket = -1L
        if (::seekOverlay.isInitialized) {
            findViewById<ImageView>(R.id.seekPreviewImage).apply {
                setImageDrawable(null)
                visibility = View.GONE
            }
        }
        if (::seekOverlay.isInitialized) seekOverlay.visibility = View.GONE
    }

    /** Annule proprement un seek progressif en attente et restaure l'auto-masquage des
     *  contrôles (épinglés pendant le geste). À appeler avant tout rechargement/recréation. */
    private fun cancelPendingSeek() {
        handler.removeCallbacks(seekCommitRunnable)
        handler.removeCallbacks(seekPreviewRunnable)
        seekKeyHeld = false
        isSeeking = false
        seekAccumMs = 0L
        seekDirection = 0
        dismissSeekOverlay()
        if (::timeline.isInitialized && !timelineScrubbing) updateTimeline(reliablePositionMs())
        if (::controlsOverlay.isInitialized && controlsOverlay.visibility == View.VISIBLE) {
            scheduleControlsHide()
        }
    }

    /**
     * Normalizes TV, gamepad and media-button events before they reach individual
     * views. Actions run on the first DOWN, repeats are debounced, and UP is consumed
     * only when this router owned the gesture.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::controlsOverlay.isInitialized) return super.dispatchKeyEvent(event)
        if (terminalPlaybackError && !playerChoiceMenuShowing) {
            // Error actions use ordinary button navigation, not playback/seek shortcuts.
            if (!playbackErrorPanel.hasFocus()) retryPlaybackButton.requestFocus()
            return super.dispatchKeyEvent(event)
        }
        val key = event.toPlaybackRemoteKey()
        val phase = when (event.action) {
            KeyEvent.ACTION_DOWN -> TvPlaybackKeyRouter.Phase.DOWN
            KeyEvent.ACTION_UP -> TvPlaybackKeyRouter.Phase.UP
            else -> return super.dispatchKeyEvent(event)
        }
        val decision = remoteKeyRouter.route(
            key = key,
            phase = phase,
            repeatCount = event.repeatCount,
            // Some HDMI-CEC bridges report a constant/zero KeyEvent.eventTime.
            eventTimeMs = SystemClock.elapsedRealtime(),
            state = TvPlaybackKeyRouter.State(
                controlsVisible = controlsOverlay.visibility == View.VISIBLE || findViewById<View>(R.id.skipSegment).hasFocus(),
                seekActive = isSeeking || timelineScrubbing,
                focus = playbackFocus(),
            ),
        )
        if (phase == TvPlaybackKeyRouter.Phase.DOWN &&
            controlsOverlay.visibility == View.VISIBLE
        ) {
            scheduleControlsHide()
        }
        if (!decision.handled) return super.dispatchKeyEvent(event)

        val centerOnPrimary = key == TvPlaybackKeyRouter.Key.CENTER &&
            playbackFocus() == TvPlaybackKeyRouter.Focus.PLAY_PAUSE
        if (centerOnPrimary && ::playPauseButton.isInitialized) {
            playPauseButton.isPressed = phase == TvPlaybackKeyRouter.Phase.DOWN
        }
        decision.commands.forEach(::executeRemoteCommand)
        if (key == TvPlaybackKeyRouter.Key.UP && phase == TvPlaybackKeyRouter.Phase.DOWN &&
            TvPlaybackKeyRouter.Command.ShowControls in decision.commands) {
            findViewById<View>(R.id.skipSegment).takeIf { it.visibility == View.VISIBLE }?.requestFocusFromTouch()
        }
        return true
    }

    private fun KeyEvent.toPlaybackRemoteKey(): TvPlaybackKeyRouter.Key = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> TvPlaybackKeyRouter.Key.CENTER

        KeyEvent.KEYCODE_DPAD_LEFT -> TvPlaybackKeyRouter.Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvPlaybackKeyRouter.Key.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> TvPlaybackKeyRouter.Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TvPlaybackKeyRouter.Key.DOWN
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> TvPlaybackKeyRouter.Key.MEDIA_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> TvPlaybackKeyRouter.Key.MEDIA_PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvPlaybackKeyRouter.Key.MEDIA_PAUSE
        KeyEvent.KEYCODE_MEDIA_REWIND -> TvPlaybackKeyRouter.Key.MEDIA_REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TvPlaybackKeyRouter.Key.MEDIA_FAST_FORWARD
        else -> TvPlaybackKeyRouter.Key.OTHER
    }

    private fun playbackFocus(): TvPlaybackKeyRouter.Focus {
        if (findViewById<View>(R.id.skipSegment).hasFocus()) return TvPlaybackKeyRouter.Focus.OTHER_CONTROL
        if (controlsOverlay.visibility != View.VISIBLE) {
            return TvPlaybackKeyRouter.Focus.PLAYBACK
        }
        return when (currentFocus?.id) {
            R.id.playbackTimeline -> TvPlaybackKeyRouter.Focus.TIMELINE
            R.id.btnPlayPause -> TvPlaybackKeyRouter.Focus.PLAY_PAUSE
            R.id.player_view, null -> TvPlaybackKeyRouter.Focus.PLAYBACK
            else -> TvPlaybackKeyRouter.Focus.OTHER_CONTROL
        }
    }

    private fun executeRemoteCommand(command: TvPlaybackKeyRouter.Command) {
        when (command) {
            TvPlaybackKeyRouter.Command.ShowControls -> showControls(focusPlayPause = true, fromRemote = true)
            TvPlaybackKeyRouter.Command.ToggleOrReplay -> togglePlayPause()
            TvPlaybackKeyRouter.Command.PlayOrReplay -> playOrReplay()
            TvPlaybackKeyRouter.Command.Pause -> pausePlayback()
            is TvPlaybackKeyRouter.Command.StartSeek -> {
                timeline.requestFocus()
                startSeekHold(command.direction)
            }
            TvPlaybackKeyRouter.Command.KeepSeekAlive -> armSeekReleaseFallback()
            TvPlaybackKeyRouter.Command.CommitSeek -> {
                if (isSeeking) {
                    updateHeldSeekPreview(player, SystemClock.elapsedRealtime())
                    handler.removeCallbacks(seekCommitRunnable)
                    seekCommitRunnable.run()
                }
            }
            TvPlaybackKeyRouter.Command.CancelSeek -> cancelPendingSeek()
            TvPlaybackKeyRouter.Command.HideControls -> hideControls()
            TvPlaybackKeyRouter.Command.Exit -> finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        updateScreenAwake()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))

        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.status)

        if (!PluxyApplication.isConfigured(this)) {
            Toast.makeText(this, "Aucun serveur configuré", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val restored = savedInstanceState?.getString(STATE_SESSION)?.let {
            runCatching { sessionAdapter.fromJson(it) }.getOrNull()
        }
        val initial = restored?.item ?: intent.getStringExtra(EXTRA_ITEM)?.let {
            runCatching { itemAdapter.fromJson(it) }.getOrNull()
        }
        if (initial == null) { finish(); return }
        item = initial
        api = PluxyApi(restored?.serverUrl ?: PluxyApplication.serverBaseUrl(this))
        ProgressOutbox.start(this)
        subtitlesDisabled = restored?.subtitlesDisabled ?: (PluxyApplication.subsMode(this) == "off")
        playbackSpeed = restored?.speed?.takeIf { it.isFinite() && it in 0.5f..2f } ?: 1f
        audioChoice = restored?.audioChoice
        subtitleChoice = restored?.subtitleChoice
        aspectIdx = (restored?.aspectIndex ?: 0).coerceIn(aspectModes.indices)
        forceDirectForLaunch = restored?.forceDirect ?: intent.getBooleanExtra(EXTRA_FORCE_DIRECT, false)
        strategyOverride = restored?.strategyOverride?.let(PlaybackStrategy::fromKey)
        audioFallbackLevel = restored?.audioFallbackLevel?.takeIf { it > 0 }
            ?: if (restored?.compatibleAudio == true) 2 else 0
        failedAudioCodec = restored?.failedAudioCodec
        if (restored != null) {
            savedPositionMs = restored.positionMs.coerceAtLeast(0L)
            lastGoodPositionMs = savedPositionMs
            savedPlayWhenReady = restored.playWhenReady
            resumeHandled = true
        }
        startFromBeginning = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        autoResume = !startFromBeginning && intent.getBooleanExtra(EXTRA_AUTO_RESUME, false)
        if (startFromBeginning) {
            savedPositionMs = 0L
            lastGoodPositionMs = 0L
            resumeHandled = true
        }

        controlsOverlay = findViewById(R.id.controlsOverlay)
        mediaTitleView = findViewById(R.id.mediaTitle)
        modeTextView = findViewById(R.id.modeText)
        // PlayerView contains its own hidden exo_progress. Use a distinct ID for
        // our visible controls so position updates and D-pad scrubbing target it.
        timeline = findViewById(R.id.playbackTimeline)
        timePositionView = findViewById(R.id.timePosition)
        timeDurationView = findViewById(R.id.timeDuration)
        playPauseButton = findViewById(R.id.btnPlayPause)
        loadingIndicator = findViewById(R.id.playerLoading)
        playbackErrorPanel = findViewById(R.id.playbackErrorPanel)
        playbackErrorMessage = findViewById(R.id.playbackErrorMessage)
        retryPlaybackButton = findViewById(R.id.btnRetryPlayback)
        seekOverlay   = findViewById(R.id.seekOverlay)
        seekArrowView = findViewById(R.id.seekArrow)
        seekDeltaView = findViewById(R.id.seekDelta)
        seekTargetView= findViewById(R.id.seekTarget)
        setupModernControls()
        findViewById<Button>(R.id.skipSegment).setOnClickListener {
            activeSkipSegment?.let { goToAbsolute(it.endMs); updateSkipSegment() }
        }
        loadSkipChapters()
        playerView.resizeMode = aspectModes[aspectIdx].first
        setupBackNavigation()
        setupMediaSession()
        setLoadingVisible(true)
        showControls(focusPlayPause = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::item.isInitialized && ::api.isInitialized) {
            val playIntent = if (terminalPlaybackError) savedPlayWhenReady
                else player?.playWhenReady ?: savedPlayWhenReady
            outState.putString(STATE_SESSION, sessionAdapter.toJson(PlaybackSessionState(
                item, api.baseUrl, reliablePositionMs(), playIntent, playbackSpeed,
                subtitlesDisabled, audioChoice, subtitleChoice, aspectIdx, forceDirectForLaunch,
                audioFallbackLevel > 0, audioFallbackLevel, failedAudioCodec,
                strategyOverride?.key,
            )))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        val next = newIntent.getStringExtra(EXTRA_ITEM)?.let {
            runCatching { itemAdapter.fromJson(it) }.getOrNull()
        } ?: return
        setIntent(newIntent)
        forceDirectForLaunch = newIntent.getBooleanExtra(EXTRA_FORCE_DIRECT, false)
        strategyOverride = null
        playNextEpisode(next, markPreviousCompleted = false,
            serverUrl = PluxyApplication.serverBaseUrl(this))
    }

    private fun setupModernControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (id in listOf(R.id.btnPlayPause, R.id.btnRewind, R.id.btnForward, R.id.btnRestart,
                R.id.btnTracks, R.id.btnSubtitles, R.id.btnGear, R.id.btnExitPlayback,
                R.id.btnRetryPlayback, R.id.btnAudioOutputError, R.id.btnLeavePlayback, R.id.skipSegment)) {
                findViewById<View>(id).defaultFocusHighlightEnabled = false
            }
        }
        mediaTitleView.text = LibraryOrganizer.playbackTitle(item)
        timeDurationView.text = fmtPlayerTime(durationMs)
        timeline.setDuration(durationMs.coerceAtLeast(0L))
        setupResponsiveControls()

        playPauseButton.setOnClickListener { togglePlayPause() }
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            goToAbsolute(reliablePositionMs() - 10_000L)
            showControls()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            goToAbsolute(reliablePositionMs() + 10_000L)
            showControls()
        }
        findViewById<ImageButton>(R.id.btnRestart).apply {
            setOnClickListener {
                player?.let(::replayFromBeginning)
                showControls(focusPlayPause = true)
            }
        }
        findViewById<PlayerIconButton>(R.id.btnTracks).setOnClickListener {
            pickTrack(C.TRACK_TYPE_AUDIO, getString(R.string.player_audio_action))
        }
        findViewById<PlayerIconButton>(R.id.btnSubtitles).setOnClickListener {
            pickSubtitles()
        }
        findViewById<ImageButton>(R.id.btnGear).setOnClickListener {
            openMenu()
        }
        retryPlaybackButton.setOnClickListener { retryPlayback() }
        findViewById<Button>(R.id.btnAudioOutputError).setOnClickListener { pickAudioOutput(it) }
        findViewById<Button>(R.id.btnLeavePlayback).setOnClickListener { finish() }
        playerView.setOnClickListener {
            if (controlsOverlay.visibility == View.VISIBLE) hideControls()
            else showControls(focusPlayPause = true)
        }

        timeline.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                timelineScrubbing = true
                handler.removeCallbacks(hideControlsRunnable)
                showScrubPreview(position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                showScrubPreview(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                timelineScrubbing = false
                if (!canceled) goToAbsolute(position)
                dismissSeekOverlay()
                scheduleControlsHide()
            }
        })
    }

    private fun setupResponsiveControls() {
        val title = findViewById<View>(R.id.playerTitleCapsule)
        val panel = findViewById<View>(R.id.playerBottomPanel)
        val update = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateResponsiveControls()
        }
        controlsOverlay.addOnLayoutChangeListener(update)
        title.addOnLayoutChangeListener(update)
        panel.addOnLayoutChangeListener(update)
        val television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        for (id in listOf(R.id.btnRetryPlayback, R.id.btnAudioOutputError, R.id.btnLeavePlayback)) {
            findViewById<Button>(id).isFocusableInTouchMode = television
        }
        findViewById<ImageButton>(R.id.btnExitPlayback).apply {
            visibility = if (television) View.GONE else View.VISIBLE
            setOnClickListener { finish() }
        }
        setupImmersivePlayback()
        controlsOverlay.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            updatePlayerActionHint(focused)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controlsOverlay.isInitialized) {
            hideSystemBars()
            ViewCompat.requestApplyInsets(findViewById(R.id.playerRoot))
            controlsOverlay.post { updateResponsiveControls() }
        }
    }

    private fun setupImmersivePlayback() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val root = findViewById<View>(R.id.playerRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val gesture = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            // La vidéo reste plein écran ; seules les commandes évitent les découpes.
            controlsOverlay.setPadding(cutout.left, cutout.top, cutout.right, maxOf(cutout.bottom, gesture.bottom))
            val edge = resources.getDimensionPixelSize(R.dimen.media_viewer_screen_pad)
            val rtl = ViewCompat.getLayoutDirection(root) == ViewCompat.LAYOUT_DIRECTION_RTL
            val safeStart = if (rtl) cutout.right else cutout.left
            val safeEnd = if (rtl) cutout.left else cutout.right
            statusView.layoutParams = (statusView.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = edge + safeStart
                marginEnd = edge + safeEnd
            }
            val errorGap = (16 * resources.displayMetrics.density).toInt()
            playbackErrorPanel.layoutParams = (playbackErrorPanel.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = edge + safeStart
                marginEnd = edge + safeEnd
                topMargin = errorGap + cutout.top
                bottomMargin = errorGap + maxOf(cutout.bottom, gesture.bottom)
            }
            controlsOverlay.post { updateResponsiveControls() }
            insets
        }
        hideSystemBars()
        ViewCompat.requestApplyInsets(root)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Garde les commandes accessibles sans recréer la lecture à chaque rotation. */
    private fun updateResponsiveControls() {
        if (controlsOverlay.width <= 0 || controlsOverlay.height <= 0) return
        val density = resources.displayMetrics.density
        val television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val edge = resources.getDimensionPixelSize(R.dimen.media_viewer_screen_pad)
        val availableWidth = controlsOverlay.width - controlsOverlay.paddingLeft -
            controlsOverlay.paddingRight - edge * 2
        val compact = availableWidth / density >= 400f
        val scroll = findViewById<ScrollView>(R.id.playerPanelScroll)
        val hintsEnabled = PluxyApplication.playerActionHints(this)
        val edgeGap = resources.getDimensionPixelSize(R.dimen.player_edge_gap)
        val requestedMargin = edgeGap + if (hintsEnabled) (24 * density).toInt() else 0
        val scrollParams = scroll.layoutParams as FrameLayout.LayoutParams
        if (scrollParams.bottomMargin != requestedMargin) {
            scroll.layoutParams = scrollParams.apply { bottomMargin = requestedMargin }
        }
        val deckWidth = minOf(availableWidth, resources.getDimensionPixelSize(R.dimen.player_deck_max_width))
            .coerceAtLeast((48 * density).toInt())
        if (scroll.layoutParams.width != deckWidth) {
            scroll.layoutParams = scroll.layoutParams.apply { width = deckWidth }
        }
        val title = findViewById<View>(R.id.playerTitleCapsule)
        val titleParams = title.layoutParams as FrameLayout.LayoutParams
        val exitSpace = if (television) 0 else (60 * density).toInt()
        if (titleParams.marginEnd != edge + exitSpace) {
            title.layoutParams = titleParams.apply { marginEnd = edge + exitSpace }
        }
        val titleSpace = controlsOverlay.width - controlsOverlay.paddingLeft - controlsOverlay.paddingRight -
            titleParams.marginStart - titleParams.marginEnd - title.paddingLeft - title.paddingRight
        if (mediaTitleView.maxWidth != titleSpace.coerceAtLeast(0)) {
            mediaTitleView.maxWidth = titleSpace.coerceAtLeast(0)
        }
        if (compactPlayerControls != compact) {
            compactPlayerControls = compact
            val rows = findViewById<LinearLayout>(R.id.playerActionRows)
            rows.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val secondary = findViewById<HorizontalScrollView>(R.id.playerSecondaryScroll)
            secondary.layoutParams = (secondary.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (compact) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                marginStart = if (compact) (16 * density).toInt() else 0
            }
            val restart = findViewById<View>(R.id.btnRestart)
            val rewind = findViewById<View>(R.id.btnRewind)
            val forward = findViewById<View>(R.id.btnForward)
            val tracks = findViewById<View>(R.id.btnTracks)
            val subtitles = findViewById<View>(R.id.btnSubtitles)
            val more = findViewById<View>(R.id.btnGear)
            restart.nextFocusLeftId = if (compact) R.id.btnGear else R.id.btnForward
            forward.nextFocusRightId = if (compact) R.id.btnTracks else R.id.btnRestart
            tracks.nextFocusLeftId = if (compact) R.id.btnForward else R.id.btnGear
            more.nextFocusRightId = if (compact) R.id.btnRestart else R.id.btnTracks
            for (control in listOf(restart, rewind, playPauseButton, forward, tracks, subtitles, more)) {
                control.nextFocusUpId = if (compact) R.id.playbackTimeline else when (control.id) {
                    R.id.btnTracks, R.id.btnSubtitles -> R.id.btnPlayPause
                    R.id.btnGear -> R.id.btnForward
                    else -> R.id.playbackTimeline
                }
                control.nextFocusDownId = if (compact) control.id else when (control.id) {
                    R.id.btnForward -> R.id.btnSubtitles
                    R.id.btnTracks, R.id.btnSubtitles -> R.id.btnPlayPause
                    R.id.btnGear -> R.id.btnForward
                    else -> R.id.btnTracks
                }
            }
        }

        val statusParams = statusView.layoutParams as FrameLayout.LayoutParams
        val statusTop = title.bottom + (8 * density).toInt()
        if (statusParams.topMargin != statusTop) {
            statusView.layoutParams = statusParams.apply { topMargin = statusTop }
        }
        val panel = findViewById<View>(R.id.playerBottomPanel)
        val margin = (scroll.layoutParams as FrameLayout.LayoutParams).bottomMargin
        val hint = findViewById<View>(R.id.playerActionHint)
        val hintParams = hint.layoutParams as FrameLayout.LayoutParams
        val hintMargin = edgeGap
        if (hintParams.bottomMargin != hintMargin) {
            hint.layoutParams = hintParams.apply { bottomMargin = hintMargin }
        }
        val belowTitle = (controlsOverlay.height - controlsOverlay.paddingBottom - title.bottom - margin - 12 * density).toInt()
        val cap = belowTitle
        val height = minOf(panel.measuredHeight, cap.coerceAtLeast((48 * density).toInt()))
        if (height > 0 && scroll.layoutParams.height != height) {
            scroll.layoutParams = scroll.layoutParams.apply { this.height = height }
        }
        updateSubtitleClearance()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (terminalPlaybackError) { finish(); return }
                val decision = remoteKeyRouter.route(
                    key = TvPlaybackKeyRouter.Key.BACK,
                    phase = TvPlaybackKeyRouter.Phase.DOWN,
                    repeatCount = 0,
                    eventTimeMs = SystemClock.elapsedRealtime(),
                    state = TvPlaybackKeyRouter.State(
                        controlsVisible = controlsOverlay.visibility == View.VISIBLE || findViewById<View>(R.id.skipSegment).hasFocus(),
                        seekActive = isSeeking || timelineScrubbing,
                        focus = playbackFocus(),
                    ),
                )
                decision.commands.forEach(::executeRemoteCommand)
                // The dispatcher is one logical gesture, not a DOWN/UP pair.
                remoteKeyRouter.reset()
            }
        })
    }

    private fun showScrubPreview(position: Long) {
        val target = position.coerceIn(0L, durationMs.coerceAtLeast(0L))
        seekOverlay.visibility = View.VISIBLE
        seekArrowView.text = ""
        seekTargetView.text = fmtPlayerTime(target)
        requestSeekPreview(target)
        seekDeltaView.text = if (durationMs > 0L) "${target * 100L / durationMs} %" else ""
        timePositionView.text = fmtPlayerTime(target)
    }

    private fun showControls(focusPlayPause: Boolean = false, fromRemote: Boolean = false) {
        if (!::controlsOverlay.isInitialized) return
        val wasHidden = controlsOverlay.visibility != View.VISIBLE
        controlsOverlay.animate().cancel()
        if (wasHidden) controlsOverlay.alpha = 0f
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.post { updateResponsiveControls() }
        controlsOverlay.animate().alpha(1f).setDuration(if (wasHidden) 170L else 80L).start()
        updateControlsMetadata()
        updateTimeline(reliablePositionMs())
        updatePlayPauseButton()
        updatePlayerActionHint(currentFocus)
        scheduleControlsHide()
        if (focusPlayPause) {
            val target = if (terminalPlaybackError) retryPlaybackButton else playPauseButton
            if (fromRemote) target.requestFocusFromTouch() else target.requestFocus()
        }
    }

    private fun hideControls() {
        if (!::controlsOverlay.isInitialized || isSeeking || timelineScrubbing) return
        handler.removeCallbacks(hideControlsRunnable)
        controlsOverlay.animate().cancel()
        controlsOverlay.alpha = 0f
        controlsOverlay.visibility = View.GONE
        updateSubtitleClearance()
        playerView.requestFocus()
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!isSeeking && !timelineScrubbing && shouldAutoHideControls()) {
            handler.postDelayed(hideControlsRunnable, CONTROLLER_TIMEOUT_MS.toLong())
        }
    }

    private fun shouldAutoHideControls(): Boolean = !terminalPlaybackError &&
        !playerChoiceMenuShowing && !findViewById<View>(R.id.skipSegment).hasFocus() &&
        player?.isPlaying == true && hasWindowFocus()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        if (hasFocus && ::controlsOverlay.isInitialized && controlsOverlay.visibility == View.VISIBLE) {
            scheduleControlsHide()
        }
    }

    private fun togglePlayPause() {
        if (terminalPlaybackError) { retryPlayback(); return }
        val p = player ?: run {
            if (seekRebuildRunnable != null) savedPlayWhenReady = !savedPlayWhenReady
            return
        }
        val focusPrimary = controlsOverlay.visibility != View.VISIBLE
        if (p.playbackState == Player.STATE_ENDED) {
            replayFromBeginning(p)
        } else if (p.playWhenReady) {
            p.pause()
        } else {
            p.play()
        }
        updatePlayPauseButton()
        showControls(focusPlayPause = focusPrimary)
        updateMediaSessionPlaybackState()
    }

    private fun playOrReplay() {
        if (terminalPlaybackError) { retryPlayback(); return }
        val p = player ?: run {
            if (seekRebuildRunnable != null) savedPlayWhenReady = true
            return
        }
        val focusPrimary = controlsOverlay.visibility != View.VISIBLE
        if (p.playbackState == Player.STATE_ENDED) replayFromBeginning(p) else p.play()
        updatePlayPauseButton()
        showControls(focusPlayPause = focusPrimary)
        updateMediaSessionPlaybackState()
    }

    private fun pausePlayback() {
        val focusPrimary = controlsOverlay.visibility != View.VISIBLE
        if (seekRebuildRunnable != null) savedPlayWhenReady = false
        player?.pause()
        updatePlayPauseButton()
        showControls(focusPlayPause = focusPrimary)
        updateMediaSessionPlaybackState()
    }

    private fun replayFromBeginning(p: ExoPlayer) {
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        nextEpisodeTick?.let { handler.removeCallbacks(it) }
        nextEpisodeTick = null
        nextEpisodeDialog?.dismiss()
        nextEpisodeDialog = null
        nextEpisodeHandled = false
        goToAbsolute(0L)
        p.play()
    }

    private fun updatePlayPauseButton() {
        if (!::playPauseButton.isInitialized) return
        val p = player
        val ended = p?.playbackState == Player.STATE_ENDED
        val playing = p?.playWhenReady == true && !ended
        (playPauseButton as PlayerIconButton).setIcon(
            if (playing) PlayerIconButton.Icon.PAUSE else PlayerIconButton.Icon.PLAY,
        )
        playPauseButton.contentDescription = when {
            ended -> "Rejouer"
            playing -> "Pause"
            else -> "Lecture"
        }
        if (playPauseButton.hasFocus()) updatePlayerActionHint(playPauseButton)
    }

    private fun updatePlayerActionHint(focused: View?) {
        val ids = setOf(R.id.btnRestart, R.id.btnRewind, R.id.btnPlayPause,
            R.id.btnForward, R.id.btnTracks, R.id.btnSubtitles, R.id.btnGear)
        findViewById<TextView>(R.id.playerActionHint).apply {
            text = if (PluxyApplication.playerActionHints(this@PlayerActivity) && focused?.id in ids)
                focused?.contentDescription ?: "" else ""
            visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateTimeline(positionMs: Long) {
        if (!::timeline.isInitialized) return
        val safeDuration = durationMs.coerceAtLeast(0L)
        val position = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        timeline.setDuration(safeDuration)
        timeline.setPosition(position)
        val buffered = PlaybackSeekPolicy.absolutePosition(streamPositionOffsetMs, player?.bufferedPosition ?: 0L).coerceAtLeast(position)
            .let { if (safeDuration > 0L) it.coerceAtMost(safeDuration) else it }
        timeline.setBufferedPosition(buffered)
        timePositionView.text = fmtPlayerTime(position)
        timeDurationView.text = fmtPlayerTime(safeDuration)
    }

    private fun updateControlsMetadata() {
        if (!::modeTextView.isInitialized) return
        mediaTitleView.text = LibraryOrganizer.playbackTitle(item)
        modeTextView.text = if (modeLabel.isBlank()) {
            activeStrategy.title
        } else {
            "$modeLabel · ${activeStrategy.title}"
        }
    }

    @Suppress("DEPRECATION") // Required for media-button delivery on minSdk 23-25.
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "PluxyPlayback").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playOrReplay()
                override fun onPause() = pausePlayback()
                override fun onStop() = pausePlayback()
                override fun onSeekTo(pos: Long) {
                    goToAbsolute(pos)
                    showControls()
                    updateMediaSessionPlaybackState()
                }

                override fun onRewind() {
                    goToAbsolute(reliablePositionMs() - 10_000L)
                    showControls()
                    updateMediaSessionPlaybackState()
                }

                override fun onFastForward() {
                    goToAbsolute(reliablePositionMs() + 10_000L)
                    showControls()
                    updateMediaSessionPlaybackState()
                }
            }, handler)
            isActive = true
        }
        updateMediaSessionMetadata()
        updateMediaSessionPlaybackState()
    }

    private fun updateMediaSessionMetadata() {
        if (!::item.isInitialized) return
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, LibraryOrganizer.playbackTitle(item))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
                .build(),
        )
    }

    private fun updateMediaSessionPlaybackState() {
        val p = player
        val state = when {
            p == null -> PlatformPlaybackState.STATE_CONNECTING
            p.playbackState == Player.STATE_BUFFERING -> PlatformPlaybackState.STATE_BUFFERING
            p.playbackState == Player.STATE_ENDED -> PlatformPlaybackState.STATE_STOPPED
            p.isPlaying -> PlatformPlaybackState.STATE_PLAYING
            else -> PlatformPlaybackState.STATE_PAUSED
        }
        val actions = PlatformPlaybackState.ACTION_PLAY or
            PlatformPlaybackState.ACTION_PAUSE or
            PlatformPlaybackState.ACTION_PLAY_PAUSE or
            PlatformPlaybackState.ACTION_SEEK_TO or
            PlatformPlaybackState.ACTION_REWIND or
            PlatformPlaybackState.ACTION_FAST_FORWARD
        mediaSession?.setPlaybackState(
            PlatformPlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    pendingSeekTargetMs ?: absolutePositionMs(),
                    if (p?.isPlaying == true) p.playbackParameters.speed else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    private fun setLoadingVisible(visible: Boolean) {
        if (::loadingIndicator.isInitialized) {
            loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /** Keep the TV awake for active viewing, including buffering with play intent. */
    private fun updateScreenAwake() {
        val p = player
        val viewing = activityStarted && !terminalPlaybackError && p != null &&
            (p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING))
        if (viewing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showTerminalPlaybackError(message: String, videoFailure: Boolean = false, audioFailure: Boolean = false) {
        savedPositionMs = reliablePositionMs().coerceAtLeast(0L)
        lastGoodPositionMs = savedPositionMs
        savedPlayWhenReady = player?.playWhenReady ?: savedPlayWhenReady
        terminalPlaybackError = true
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        handler.removeCallbacks(bufferWatchdog)
        cancelPendingSeek()
        player?.pause()
        setLoadingVisible(false)
        setStatus("")
        playbackErrorMessage.text = message
        val action = findViewById<Button>(R.id.btnAudioOutputError)
        val offerCompatibility = videoFailure && PlaybackFailurePolicy.canOfferVideoCompatibility(
            transcodingAvailable,
            AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this)) == AudioOutputMode.PASSTHROUGH,
        )
        action.visibility = if (offerCompatibility || videoFailure || audioFailure) View.VISIBLE else View.GONE
        action.text = when {
            offerCompatibility -> "Essayer en H.264 · 1080p"
            videoFailure -> "Ouvrir dans un autre lecteur"
            else -> getString(R.string.player_audio_output_action)
        }
        action.setOnClickListener {
            when {
                offerCompatibility -> {
                    forceDirectForLaunch = false
                    strategyOverride = PlaybackStrategy.COMPATIBILITY
                    reprepareForStrategy(PlaybackStrategy.COMPATIBILITY)
                }
                videoFailure -> openExternalPlayer()
                else -> pickAudioOutput(it)
            }
        }
        retryPlaybackButton.nextFocusDownId = if (action.visibility == View.VISIBLE) action.id else R.id.btnLeavePlayback
        findViewById<View>(R.id.btnLeavePlayback).nextFocusUpId =
            if (action.visibility == View.VISIBLE) action.id else R.id.btnRetryPlayback
        playbackErrorPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.playerBottomPanel).visibility = View.INVISIBLE
        showControls()
        retryPlaybackButton.requestFocus()
        updateScreenAwake()
    }

    private fun clearTerminalPlaybackError() {
        terminalPlaybackError = false
        if (!::playbackErrorPanel.isInitialized) return
        val restoreFocus = playbackErrorPanel.hasFocus()
        playbackErrorPanel.visibility = View.GONE
        findViewById<View>(R.id.playerBottomPanel).visibility = View.VISIBLE
        if (restoreFocus) playPauseButton.requestFocus()
    }

    private fun retryPlayback() {
        if (isFinishing || isDestroyed) return
        clearTerminalPlaybackError()
        retries = 0
        watchdogReloads = 0
        val canReloadCurrentAttempt = player != null && attempts.getOrNull(attemptIdx) != null
        // A first connection failure has not yet offered the saved library progress.
        // Existing playback instead resumes its local position without a new prompt.
        if (canReloadCurrentAttempt || savedPositionMs > 0L) resumeHandled = true
        showControls(focusPlayPause = true)
        setStatus(getString(R.string.player_retrying))
        if (canReloadCurrentAttempt) {
            loadAttempt(attemptIdx, restorePos = savedPositionMs, playWhenReady = savedPlayWhenReady)
        } else {
            prepareAndPlay(activeStrategy)
        }
    }

    private fun setStatus(message: String) {
        if (!::statusView.isInitialized) return
        statusView.text = message
        statusView.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showPlayerChoices(
        anchor: View,
        title: String,
        choices: List<CompactChoiceMenu.Choice>,
        selectedValue: String = "",
        dismissOnSelect: Boolean = true,
        onSelect: (String) -> Unit,
    ) {
        if (!activityStarted || isFinishing || isDestroyed || !anchor.isAttachedToWindow) return
        val generation = ++playerChoiceMenuGeneration
        playerChoiceMenu?.dismiss()
        showControls()
        controlsOverlay.animate().cancel()
        controlsOverlay.alpha = 1f
        playerChoiceMenuShowing = true
        handler.removeCallbacks(hideControlsRunnable)
        playerChoiceMenu = CompactChoiceMenu.show(
            activity = this,
            anchor = anchor,
            title = title,
            choices = choices,
            selectedValue = selectedValue,
            compact = true,
            dismissOnSelect = dismissOnSelect,
            onSelect = { value ->
                if (generation == playerChoiceMenuGeneration && activityStarted && !isFinishing && !isDestroyed) {
                    onSelect(value)
                }
            },
            onDismiss = {
                if (generation == playerChoiceMenuGeneration) {
                    playerChoiceMenu = null
                    playerChoiceMenuShowing = false
                    if (activityStarted && !isFinishing && !isDestroyed) scheduleControlsHide()
                }
            },
        )
        if (playerChoiceMenu == null) {
            playerChoiceMenuShowing = false
            scheduleControlsHide()
        }
    }

    private val skipDurationMs get() = durationMs.takeIf { it > 0 }
        ?: ((player?.duration?.takeIf { it > 0 } ?: 0) + streamPositionOffsetMs)
    private var skipChapters: List<MediaChapter> = emptyList()
    private var skipChaptersJob: Job? = null
    private var activeSkipSegment: SkipSegment? = null

    private fun loadSkipChapters(server: String = api.baseUrl) {
        skipChaptersJob?.cancel()
        skipChapters = emptyList()
        activeSkipSegment = null
        findViewById<View>(R.id.skipSegment).visibility = View.GONE
        val ownerId = item.id
        skipChaptersJob = lifecycleScope.launch {
            val chapterApi = PluxyApi(server)
            try {
                repeat(24) {
                    val chapters = kotlinx.coroutines.withTimeout(10_000) { chapterApi.chapters(ownerId) }
                    if (item.id != ownerId || api.baseUrl != server) return@launch
                    skipChapters = chapters
                    updateSkipSegment()
                    if (SkipSegments.detected(chapters, skipDurationMs).map { it.kind }.toSet().size == 2) return@launch
                    kotlinx.coroutines.delay(6_000)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { /* Chapitres facultatifs. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Un serveur ancien ne fournit pas d’analyse automatique. */ }
            finally { chapterApi.close() }
        }
    }

    private fun updateSkipSegment() {
        if (!::item.isInitialized || !::api.isInitialized) return
        val button = findViewById<Button>(R.id.skipSegment)
        val segments = SkipSegments.detected(skipChapters, skipDurationMs)
        activeSkipSegment = if (player?.playbackState == Player.STATE_READY && !terminalPlaybackError &&
            !isSeeking && !timelineScrubbing && !playerChoiceMenuShowing && nextEpisodeDialog?.isShowing != true)
            SkipSegments.active(segments, reliablePositionMs()) else null
        val visible = activeSkipSegment != null
        if (!visible && button.hasFocus()) {
            if (controlsOverlay.visibility == View.VISIBLE) playPauseButton.requestFocus() else playerView.requestFocus()
        }
        button.visibility = if (visible) View.VISIBLE else View.GONE
        button.text = if (activeSkipSegment?.kind == "intro") "Passer l’intro" else "Passer le générique"
        timeline.nextFocusUpId = if (visible) R.id.skipSegment else R.id.btnExitPlayback
    }

    private fun openMenu() {
        val entries = listOf(
            "Vitesse" to { pickSpeed() },
            "Format de l’image" to { pickAspect() },
            "Mode de lecture" to { pickPlaybackStrategy() },
            "Qualité de conversion" to { pickConversionQuality() },
            "Sortie audio" to { pickAudioOutput(findViewById(R.id.btnGear)) },
            getString(R.string.player_information_action) to { showDiagnostics() },
        )
        showPlayerChoices(
            findViewById(R.id.btnGear),
            "Lecture",
            entries.mapIndexed { index, entry -> CompactChoiceMenu.Choice(entry.first, index.toString()) },
        ) { value ->
            value.toIntOrNull()?.let { entries.getOrNull(it)?.second?.invoke() }
        }
    }

    /**
     * Filet de compatibilité pour VLC, Kodi ou un autre lecteur installé. Le flux brut
     * reste sur le serveur Pluxy sélectionné et aucune authentification n'est ajoutée.
     */
    private fun openExternalPlayer() {
        val directUrl = api.abs("/stream/direct/${item.id}")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(directUrl), "video/*")
            putExtra(Intent.EXTRA_TITLE, LibraryOrganizer.playbackTitle(item))
        }
        runCatching { startActivity(Intent.createChooser(intent, "Lire avec…")) }
            .onFailure {
                Toast.makeText(
                    this,
                    "Aucun lecteur externe compatible n'est installé.",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    /** Change de stratégie à chaud sans perdre l'instant en cours. */
    private fun pickPlaybackStrategy() {
        val choices = PlaybackStrategy.entries
        showPlayerChoices(
            findViewById(R.id.btnGear),
            "Mode de lecture",
            choices.map { CompactChoiceMenu.Choice(it.title, it.key, it.description) },
            activeStrategy.key,
        ) { value ->
            val selected = choices.firstOrNull { it.key == value } ?: return@showPlayerChoices
            if (selected == PlaybackStrategy.COMPATIBILITY && !transcodingAvailable) {
                Toast.makeText(this, "Ce serveur Android transmet l’original. La conversion nécessite un serveur PC Pluxy.", Toast.LENGTH_LONG).show()
                return@showPlayerChoices
            }
            if (selected == activeStrategy) return@showPlayerChoices
            PluxyApplication.setPlaybackStrategy(this, selected.key)
            forceDirectForLaunch = false
            strategyOverride = null
            reprepareForStrategy(selected)
        }
    }

    private fun pickConversionQuality() {
        if (conversionProfiles.isEmpty()) {
            Toast.makeText(this, "Les qualités de conversion nécessitent un serveur PC Pluxy à jour.", Toast.LENGTH_LONG).show()
            return
        }
        showPlayerChoices(findViewById(R.id.btnGear), "Qualité de conversion",
            listOf(CompactChoiceMenu.Choice("Automatique", "auto", "Meilleure qualité compatible, puis baisse si nécessaire")) +
                conversionProfiles.map { CompactChoiceMenu.Choice(it.label, it.id, "Qualité fixe · aucun changement automatique") },
            PluxyApplication.conversionQuality(this)) { value ->
            PluxyApplication.setConversionQuality(this, value)
            PluxyApplication.setPlaybackStrategy(this, PlaybackStrategy.COMPATIBILITY.key)
            forceDirectForLaunch = false
            strategyOverride = null
            reprepareForStrategy(PlaybackStrategy.COMPATIBILITY)
        }
    }

    private fun pickAudioOutput(anchor: View) {
        showPlayerChoices(anchor, "Sortie audio",
            AudioOutputMode.entries.map { CompactChoiceMenu.Choice(it.title, it.key, it.description) },
            PluxyApplication.audioOutputMode(this)) { value ->
            PluxyApplication.setAudioOutputMode(this, value)
            audioFallbackLevel = 0
            failedAudioCodec = null
            audioChoice = audioChoice?.copy(mimeType = null, channels = -1, trackId = null)
            if (AudioOutputMode.fromKey(value) == AudioOutputMode.PASSTHROUGH) playbackSpeed = 1f
            reprepareForStrategy(activeStrategy)
        }
    }

    private fun reprepareForStrategy(strategy: PlaybackStrategy) {
        cancelSeekRebuild()
        savedPositionMs = reliablePositionMs()
        if (!terminalPlaybackError) savedPlayWhenReady = player?.playWhenReady ?: savedPlayWhenReady
        if (savedPositionMs > 0L) lastGoodPositionMs = savedPositionMs
        saveProgress()
        resumeHandled = true
        prepareJob?.cancel()
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(bufferWatchdog)
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        cancelPendingSeek()
        stopCurrentHls()
        api = PluxyApi(api.baseUrl)
        progressSequence = 0L
        player?.release()
        player = null
        playerView.player = null
        buffer = null
        attempts.clear()
        attemptIdx = 0
        setStatus("Application du mode ${strategy.title}…")
        prepareAndPlay(strategy)
    }

    /** Ouvre l'écran de réglages avancés (serveur) sans quitter le lecteur.
     *  La lecture continue en arrière-plan ; on reprend à la même position au retour. */
    private fun openAdvancedSettings() {
        startActivity(android.content.Intent(this, com.pluxy.tv.ui.SettingsActivity::class.java))
    }

    // Création/destruction du player calées sur onStart/onStop : pas d'écran noir
    // au retour depuis l'arrière-plan (le player est recréé à la dernière position).
    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (!::item.isInitialized) return
        mediaSession?.isActive = true
        // Récupération du son si la sortie audio change (changement de source HDMI, ARC).
        runCatching { audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler) }
        val selected = strategyOverride ?: if (forceDirectForLaunch) PlaybackStrategy.DIRECT
            else PlaybackStrategy.fromKey(PluxyApplication.playbackStrategy(this))
        // A previous onStop cleanup can finish late. A new owner prevents it from
        // deleting the resumed HLS stream, even when its DELETE was delayed.
        api = PluxyApi(api.baseUrl)
        progressSequence = 0L
        buffer = null
        attempts.clear()
        attemptIdx = 0
        prepareAndPlay(selected)
        prefetchNextEpisode()
        showControls(focusPlayPause = currentFocus == null)
    }

    override fun onStop() {
        activityStarted = false
        dismissSeekOverlay()
        cancelSeekRebuild()
        playerChoiceMenuGeneration++
        playerChoiceMenu?.dismiss()
        playerChoiceMenu = null
        playerChoiceMenuShowing = false
        updateScreenAwake()
        super.onStop()
        resumeDialog?.dismiss()
        resumeDialog = null
        val interruptedNextEpisodePrompt = nextEpisodeDialog?.isShowing == true
        val interruptedNextEpisodeFetch = nextEpisodeJob?.isActive == true
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = null
        nextEpisodeDialog?.dismiss()
        nextEpisodeDialog = null
        nextEpisodeTick = null
        if (interruptedNextEpisodePrompt || interruptedNextEpisodeFetch) nextEpisodeHandled = false
        prepareJob?.cancel()
        prepareJob = null
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        handler.removeCallbacksAndMessages(null)
        retryRunnable = null
        remoteKeyRouter.reset()
        if (::playPauseButton.isInitialized) playPauseButton.isPressed = false
        mediaSession?.isActive = false
        cancelPendingSeek()
        handler.removeCallbacks(hideControlsRunnable)
        // Position FIABLE (jamais un 0 transitoire) pour reprendre au bon endroit.
        savedPositionMs = reliablePositionMs()
        if (!terminalPlaybackError) savedPlayWhenReady = player?.playWhenReady ?: savedPlayWhenReady
        saveProgress()
        // Nettoyages qui DOIVENT aboutir même si l'activité est détruite juste après.
        stopCurrentHls()
        player?.release()
        player = null
        playerView.player = null
    }

    override fun onDestroy() {
        playerChoiceMenuGeneration++
        playerChoiceMenu?.dismiss()
        playerChoiceMenu = null
        playerChoiceMenuShowing = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun stopCurrentHls() {
        if (!::item.isInitialized || !::api.isInitialized) return
        val previousApi = api
        val previousId = item.id
        ioScope.launch { previousApi.stopHls(previousId) }
    }

    /** Première fois : décision serveur + buffer + chaîne de repli, puis lecture.
     *  La phase de décision est RÉESSAYÉE (un appel de contrôle qui échoue de façon
     *  transitoire ne doit pas laisser l'écran figé sur « Analyse du flux… »). */
    private fun prepareAndPlay(
        strategy: PlaybackStrategy = PlaybackStrategy.fromKey(
            PluxyApplication.playbackStrategy(this),
        ),
    ) {
        prepareJob?.cancel()
        clearTerminalPlaybackError()
        activeStrategy = strategy
        setLoadingVisible(true)
        setStatus("Analyse du flux…")
        prepareJob = lifecycleScope.launch {
            repeat(PREPARE_ATTEMPTS) { attempt ->
                try {
                    val audioMode = AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this@PlayerActivity))
                    val decision = if (strategy == PlaybackStrategy.DIRECT && audioFallbackLevel == 0 && audioMode != AudioOutputMode.COMPATIBLE) PlaybackDecision(
                        mode = "direct_play", streamUrl = "/stream/direct/${item.id}", delivery = "direct",
                        reasons = listOf("Lecture du fichier original demandée, sans conversion."),
                    ) else api.decide(item.id, audioFallbackLevel, failedAudioCodec, strategy == PlaybackStrategy.DIRECT)
                    val runtime = api.clientRuntime()
                    conversionProfiles = ConversionPolicy.compatible(runtime.conversionProfiles, item, DeviceProfile.capabilities())
                    if (conversionProfiles.isNotEmpty() && PluxyApplication.conversionQuality(this@PlayerActivity) != "auto" &&
                        conversionProfiles.none { it.id == PluxyApplication.conversionQuality(this@PlayerActivity) }) {
                        PluxyApplication.setConversionQuality(this@PlayerActivity, "auto")
                        Toast.makeText(this@PlayerActivity, "La qualité choisie n’est pas disponible ici. Conversion automatique sélectionnée.", Toast.LENGTH_LONG).show()
                    }
                    if (audioMode == AudioOutputMode.PASSTHROUGH &&
                        ((strategy == PlaybackStrategy.DIRECT && decision.audioAction != "copy") ||
                            (conversionProfiles.isEmpty() && (decision.audioAction != "copy" || strategy == PlaybackStrategy.COMPATIBILITY)))) {
                        showTerminalPlaybackError("Ce flux demande une conversion audio. Le passthrough forcé conserve le son original. " +
                            "Choisissez une autre sortie audio ou le mode Vidéo originale.", audioFailure = true)
                        return@launch
                    }
                    if (strategy == PlaybackStrategy.DIRECT && !PlaybackPlanner.preservesOriginalVideo(decision)) {
                        showTerminalPlaybackError("Le serveur propose une conversion vidéo. Le mode Vidéo originale conserve la 4K/HDR. " +
                            "Vérifiez les réglages de transcodage du serveur ou choisissez le mode automatique.")
                        return@launch
                    }
                    val progress = ProgressOutbox.pendingResume(api.baseUrl, item.id)
                        ?: api.getProgress(item.id)

                    if (!decision.playable && strategy != PlaybackStrategy.DIRECT && conversionProfiles.isEmpty()) {
                        showUnsupportedDecision(decision)
                        return@launch
                    }

                    transcodingAvailable = runtime.transcodingAvailable
                    directStreamSeekAvailable = runtime.directStreamSeekAvailable
                    serverKind = runtime.serverKind
                    decisionToneMapped = decision.toneMap
                    reinforcedBufferActive = PlaybackPlanner.shouldStartWithReinforcedBuffer(
                        strategy,
                        runtime.transcodingAvailable,
                    )
                    networkLimitWarned = false
                    adaptiveHistory.clear()
                    lastAdaptiveChangeAtMs = SystemClock.elapsedRealtime()
                    lastQualifiedStallAtMs = lastAdaptiveChangeAtMs
                    buffer = if (reinforcedBufferActive) {
                        PlaybackPlanner.reinforcedBuffer(runtime.buffer)
                    } else {
                        runtime.buffer
                    }
                    attempts.clear()
                    attempts += PlaybackPlanner.build(
                        strategy = strategy,
                        itemId = item.id,
                        decision = decision,
                        transcodingAvailable = runtime.transcodingAvailable,
                        supportsHevc = DeviceProfile.capabilities().supportsHevc,
                        preserveAudio = audioMode == AudioOutputMode.PASSTHROUGH,
                        conversionProfiles = conversionProfiles,
                        conversionQuality = PluxyApplication.conversionQuality(this@PlayerActivity),
                    )
                    attemptIdx = 0
                    modeLabel = describeAttempt(attempts.first(), 0)
                    Logger.log(
                        "decide",
                        "strategy=${strategy.key} mode=${decision.mode} compat=${decision.compat} " +
                            "delivery=${decision.delivery} server=${runtime.serverKind} " +
                            "transcode=${runtime.transcodingAvailable} " +
                            "plan=${attempts.joinToString { it.tier.name }} " +
                            "reasons=${decision.reasons.joinToString(" | ")}",
                    )

                    buildPlayer()

                    if (!resumeHandled && progress.positionMs > 10_000 && !progress.watched) {
                        resumeHandled = true
                        if (autoResume) {
                            goToAbsolute(progress.positionMs)
                        } else {
                            askResume(progress.positionMs)
                        }
                    }
                    updateControlsMetadata()
                    setStatus("")
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("decide", "échec préparation (${attempt + 1}/$PREPARE_ATTEMPTS) : ${e.message}")
                    if (attempt < PREPARE_ATTEMPTS - 1) {
                        setStatus("Connexion au serveur… (${attempt + 1}/$PREPARE_ATTEMPTS)")
                        delay(1200L * (attempt + 1))
                    }
                }
            }
            if (!isFinishing) showTerminalPlaybackError(getString(R.string.player_connection_error))
        }
    }

    private fun showUnsupportedDecision(decision: com.pluxy.tv.api.PlaybackDecision) {
        setLoadingVisible(false)
        val ui = PlaybackDiagnostic.from(decision)
        setStatus(ui.title)
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Lecture non garantie")
            .setMessage(
                ui.detail + "\n\nCe serveur ne peut pas adapter le fichier. " +
                    "Le Direct Play peut tout de même être tenté.",
            )
            .setPositiveButton("Essayer en direct") { _, _ ->
                forceDirectForLaunch = true
                prepareAndPlay(PlaybackStrategy.DIRECT)
            }
            .setNeutralButton("Autre lecteur") { _, _ -> openExternalPlayer() }
            .setNegativeButton("Quitter") { _, _ -> finish() }
            .show()
    }

    private fun describeAttempt(attempt: PlaybackAttempt, index: Int = attemptIdx): String =
        buildString {
            append(attempt.label)
            if (decisionToneMapped && index == 0 && attempt.delivery == "hls") append(" · HDR→SDR")
            if (serverKind == "android") append(" · serveur Android")
            if (reinforcedBufferActive) append(" · tampon renforcé")
        }

    /** (Re)construit l'ExoPlayer depuis l'état conservé, à la dernière position. */
    private fun buildPlayer() {
        if (player != null || buffer == null) return
        setLoadingVisible(true)
        val currentBytes = java.util.concurrent.atomic.AtomicLong()
        transferBytes = currentBytes
        conversionBufferActive = attempts.getOrNull(attemptIdx)?.delivery == "conversion"
        val playbackBuffer = if (conversionBufferActive) buffer!!.copy(
            bufferForPlaybackMs = buffer!!.bufferForPlaybackMs.coerceAtMost(1_500),
            bufferForPlaybackAfterRebufferMs = buffer!!.bufferForPlaybackAfterRebufferMs.coerceAtMost(2_500),
        ) else buffer!!
        val exo = PluxyPlayerFactory.build(this, playbackBuffer, api.baseUrl, api.sessionId,
            sourceBitrateBps = if (isTranscode) 0L else (sourceBitrateMbps() * 1_000_000).toLong()) {
            currentBytes.addAndGet(it.toLong())
        }
        player = exo
        playerView.player = exo
        styleSubtitles()
        exo.addListener(playerListener())
        exo.playbackParameters = PlaybackParameters(playbackSpeed)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesDisabled).build()
        lastBuildAtMs = SystemClock.elapsedRealtime()
        retries = 0
        wasReady = false
        playStartedAtMs = 0L
        networkStallStartMs = 0L
        watchdogReloads = 0
        rebufferTimes.clear()
        stallWindow.clear()
        loadAttempt(attemptIdx, restorePos = savedPositionMs, playWhenReady = savedPlayWhenReady)
        handler.removeCallbacks(saveTick)
        handler.postDelayed(saveTick, 10_000)
        handler.removeCallbacks(positionTick)
        handler.postDelayed(positionTick, 1_000)
    }

    private fun askResume(positionMs: Long) {
        val mmss = "%02d:%02d".format(positionMs / 60000, (positionMs / 1000) % 60)
        val ownerApi = api
        val ownerItem = item.id
        resumeDialog?.dismiss()
        resumeDialog = AlertDialog.Builder(this)
            .setTitle("Reprendre la lecture ?")
            .setPositiveButton("Reprendre ($mmss)") { _, _ ->
                if (api === ownerApi && item.id == ownerItem) goToAbsolute(positionMs)
            }
            .setNegativeButton("Depuis le début") { _, _ ->
                if (api === ownerApi && item.id == ownerItem) goToAbsolute(0L)
            }
            .show()
    }

    /** Va à une position ABSOLUE. Seek natif : la playlist VOD couvre tout le film,
     *  ExoPlayer demande le segment cible (transcodé à la demande) -> lecture. */
    private fun goToAbsolute(targetMs: Long) {
        val t = targetMs.coerceIn(0, if (durationMs > 0) (durationMs - 250).coerceAtLeast(0) else Long.MAX_VALUE)
        val currentAttempt = attempts.getOrNull(attemptIdx)
        if (currentAttempt != null && PlaybackSeekPolicy.isRemux(currentAttempt.url) && !directStreamSeekAvailable) {
            Toast.makeText(this, "Mettez à jour le serveur PC pour naviguer dans ce flux.", Toast.LENGTH_LONG).show()
            return
        }
        if (currentAttempt?.delivery == "direct" && player?.playbackState == Player.STATE_READY &&
            player?.isCurrentMediaItemSeekable == false && !directStreamSeekAvailable) {
            val message = if (serverKind == "pc") "Ce fichier n'a pas d'index de navigation. Mettez à jour le serveur PC."
                else "Ce fichier n'a pas d'index de navigation. Utilisez un fichier MKV/MP4 indexé."
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        lastSeekAtMs = SystemClock.elapsedRealtime()
        // La cible EXPLICITE devient la position fiable : après un seek (surtout vers ~0),
        // currentPosition reste masqué à l'ancienne valeur pendant tout le buffering ;
        // sans ça un repli rétablirait l'ANCIENNE position (et annulerait le retour début).
        markSeekTarget(t)
        // Un second geste pendant la libération audio ne relance pas deux moteurs.
        // Le runnable déjà prévu reprend simplement la cible la plus récente.
        if (seekRebuildRunnable != null) return
        player?.let { p ->
            val attempt = attempts.getOrNull(attemptIdx)
            if (PlaybackSeekRecoveryPolicy.needsAudioReset(attempt?.delivery in setOf("direct", "conversion"),
                    p.audioFormat?.sampleMimeType,
                    AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this)) == AudioOutputMode.PASSTHROUGH)) {
                rebuildAfterSeek(t, separateAudioRelease = true, recoveryCount = 0)
                saveProgress()
                return
            }
            if (attempt != null && PlaybackSeekPolicy.isRemux(attempt.url)) {
                loadAttempt(attemptIdx, restorePos = t)
                saveProgress()
                return
            }
            if (attempt?.delivery == "direct" && p.playbackState == Player.STATE_READY && !p.isCurrentMediaItemSeekable) {
                if (serverKind == "pc" && transcodingAvailable && directStreamSeekAvailable) {
                    attempts[attemptIdx] = attempt.copy(url = "/stream/remux/${item.id}?copy_audio=true",
                        label = "Direct Stream · vidéo originale")
                    loadAttempt(attemptIdx, restorePos = t)
                } else {
                    Toast.makeText(this, "Ce fichier ne fournit pas d'index de navigation. Essayez un fichier MKV/MP4 indexé.", Toast.LENGTH_LONG).show()
                }
                return
            }
            // EXACT uniquement pour l'action explicite de l'utilisateur. On revient
            // aussitôt au point de synchro proche pour ne pas ralentir les reprises,
            // reconstructions et replis automatiques ultérieurs.
            p.setSeekParameters(if (item.isHdr || (item.height ?: 0) >= 2160) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
            p.seekTo(t)
            p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
        saveProgress()
    }

    /** Fixe le plancher de position fiable sur une cible de seek explicite. */
    private fun markSeekTarget(targetMs: Long) {
        lastGoodPositionMs = targetMs
        savedPositionMs = targetMs
        pendingSeekTargetMs = targetMs
    }

    private fun cancelSeekRebuild() {
        seekRebuildRunnable?.let { handler.removeCallbacks(it) }
        seekRebuildRunnable = null
    }

    /** Un AudioTrack direct peut garder les compteurs du précédent après flush.
     * Libérer les renderers et laisser la liaison se stabiliser évite leur chevauchement. */
    private fun rebuildAfterSeek(targetMs: Long, separateAudioRelease: Boolean, recoveryCount: Int) {
        val previous = player ?: return
        savedPlayWhenReady = previous.playWhenReady
        markSeekTarget(targetMs)
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(bufferWatchdog)
        handler.removeCallbacks(audioRecoverRunnable)
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        cancelPendingSeek()
        cancelSeekRebuild()
        lastBuildAtMs = SystemClock.elapsedRealtime()
        playerView.player = null
        player = null
        previous.release()
        setLoadingVisible(true)
        setStatus("Reprise à ${fmtPlayerTime(targetMs)} · ${attempts.getOrNull(attemptIdx)?.label ?: "vidéo"}…")
        val rebuild = object : Runnable {
            override fun run() {
                if (seekKeyHeld && activityStarted) {
                    handler.postDelayed(this, SEEK_PREVIEW_TICK_MS)
                    return
                }
                seekRebuildRunnable = null
                if (activityStarted && !isFinishing && buffer != null && attempts.isNotEmpty()) {
                    savedPositionMs = pendingSeekTargetMs ?: targetMs
                    buildPlayer()
                    watchdogReloads = recoveryCount
                }
            }
        }
        seekRebuildRunnable = rebuild
        updateMediaSessionPlaybackState()
        if (separateAudioRelease) handler.postDelayed(rebuild, 1_200L) else rebuild.run()
    }

    private fun buildMediaItem(url: String, delivery: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(api.abs(url)))
        if (delivery == "hls") b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (delivery == "conversion") b.setMimeType(MimeTypes.VIDEO_MATROSKA)
        fun subtitleUri(index: Int): Uri {
            val uri = Uri.parse(api.abs("/stream/subs/${item.id}/$index"))
            return if (streamPositionOffsetMs > 0) uri.buildUpon()
                .appendQueryParameter("start_ms", streamPositionOffsetMs.toString()).build() else uri
        }
        val subs = item.subtitles
        if (subs.isNotEmpty()) {
            b.setSubtitleConfigurations(subs.mapIndexed { i, t ->
                MediaItem.SubtitleConfiguration.Builder(subtitleUri(t.index))
                    .setMimeType(subMime(t.format))
                    .setLanguage(t.lang ?: "und")
                    .setLabel(t.label.ifBlank { t.lang?.uppercase() ?: "Sous-titres" })
                    // Un seul sous-titre marqué par défaut (le 1er fr, sinon le 1er).
                    .setSelectionFlags(if (i == preferredSubIndex(subs)) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
        } else if (item.externalSubs.isNotEmpty()) {
            // Repli ancien format (chemins seuls) : on suppose SRT.
            b.setSubtitleConfigurations(item.externalSubs.indices.map { idx ->
                MediaItem.SubtitleConfiguration.Builder(subtitleUri(idx))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("und")
                    .setSelectionFlags(0).build()
            })
        }
        return b.build()
    }

    private fun preferredSubIndex(subs: List<com.pluxy.tv.api.SubtitleTrack>): Int {
        val fr = subs.indexOfFirst { it.lang?.startsWith("fr") == true }
        return if (fr >= 0) fr else 0
    }

    private fun subMime(fmt: String) = when (fmt.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun styleSubtitles() {
        playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false)
            setFractionalTextSize(0.058f)
            setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        }
        playerView.post { updateSubtitleClearance() }
    }

    private fun updateSubtitleClearance() {
        val subtitles = playerView.subtitleView ?: return
        if (subtitles.height <= 0) return
        val controlsVisible = ::controlsOverlay.isInitialized && controlsOverlay.visibility == View.VISIBLE &&
            findViewById<View>(R.id.playerBottomPanel).visibility == View.VISIBLE
        val subtitlePosition = IntArray(2)
        val panelPosition = IntArray(2)
        subtitles.getLocationInWindow(subtitlePosition)
        val panel = findViewById<View>(R.id.playerPanelScroll)
        panel.getLocationInWindow(panelPosition)
        val gap = (28 * resources.displayMetrics.density).toInt()
        val clearance = if (controlsVisible && panel.height > 0)
            (subtitlePosition[1] + subtitles.height - panelPosition[1] + gap)
                .coerceIn(0, (subtitles.height * 0.45f).toInt()) else 0
        if (subtitles.paddingBottom != clearance) {
            subtitles.setPadding(subtitles.paddingLeft, subtitles.paddingTop, subtitles.paddingRight, clearance)
        }
        // La taille du texte reste stable quand la zone réservée aux commandes change.
        subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, subtitles.height * 0.058f)
    }

    // ----- Sélection de pistes ------------------------------------------- //
    private data class TrackOpt(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

    private fun tracksOf(type: Int): List<TrackOpt> {
        val p = player ?: return emptyList()
        val out = mutableListOf<TrackOpt>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeUnless { it.isBlank() || it.equals("und", true) }
                    ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.FRENCH) }
                    ?.replaceFirstChar { it.titlecase(java.util.Locale.FRENCH) }
                    ?: getString(R.string.player_language_unspecified)
                val label = when (type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val ch = when (f.channelCount) {
                            1 -> getString(R.string.player_audio_mono)
                            2 -> getString(R.string.player_audio_stereo)
                            in 3..Int.MAX_VALUE -> getString(R.string.player_audio_channels, f.channelCount)
                            else -> ""
                        }
                        listOf(f.label?.takeIf { it.isNotBlank() } ?: lang, codecShort(f.sampleMimeType), ch)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    else -> f.label ?: lang
                }
                out.add(TrackOpt(label, g, i, g.isTrackSelected(i)))
            }
        }
        return out
    }

    private fun pickTrack(type: Int, title: String) {
        val p = player ?: return
        val opts = tracksOf(type)
        if (opts.isEmpty()) { Toast.makeText(this, "Pistes en cours d'analyse…", Toast.LENGTH_SHORT).show(); return }
        showPlayerChoices(
            findViewById(R.id.btnTracks),
            title,
            opts.mapIndexed { index, option -> CompactChoiceMenu.Choice(option.label, index.toString()) },
            opts.indexOfFirst { it.selected }.toString(),
        ) { value ->
            if (player !== p) return@showPlayerChoices
            val o = value.toIntOrNull()?.let(opts::getOrNull) ?: return@showPlayerChoices
            val choice = trackChoice(o.group.getTrackFormat(o.index))
            if (type == C.TRACK_TYPE_AUDIO) audioChoice = choice else subtitleChoice = choice
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
                .setTrackTypeDisabled(type, false).build()
        }
    }

    private fun pickSubtitles() {
        val p = player ?: return
        val opts = tracksOf(C.TRACK_TYPE_TEXT)
        showPlayerChoices(
            findViewById(R.id.btnSubtitles),
            "Sous-titres",
            listOf(CompactChoiceMenu.Choice("Désactivés", "off")) +
                opts.mapIndexed { index, option -> CompactChoiceMenu.Choice(option.label, index.toString()) },
            if (subtitlesDisabled) "off" else opts.indexOfFirst { it.selected }.toString(),
        ) { value ->
            if (player !== p) return@showPlayerChoices
            val o = if (value == "off") null else {
                value.toIntOrNull()?.let(opts::getOrNull) ?: return@showPlayerChoices
            }
            val b = p.trackSelectionParameters.buildUpon()
            subtitlesDisabled = value == "off"
            if (subtitlesDisabled) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else if (o != null) {
                subtitleChoice = trackChoice(o.group.getTrackFormat(o.index))
                b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            p.trackSelectionParameters = b.build()
        }
    }

    private fun pickSpeed() {
        val p = player ?: return
        showPlayerChoices(
            findViewById(R.id.btnGear),
            "Vitesse de lecture",
            speeds.map { CompactChoiceMenu.Choice("${it}x", it.toString()) },
            speeds[speeds.indexOf(playbackSpeed).coerceAtLeast(0)].toString(),
        ) { value ->
            val selected = speeds.firstOrNull { it.toString() == value } ?: return@showPlayerChoices
            if (player === p) {
                playbackSpeed = selected
                p.playbackParameters = PlaybackParameters(playbackSpeed)
            }
        }
    }

    private fun pickAspect() {
        showPlayerChoices(findViewById(R.id.btnGear), "Format de l’image",
            aspectModes.mapIndexed { index, mode -> CompactChoiceMenu.Choice(mode.second, index.toString()) },
            aspectIdx.toString(), dismissOnSelect = false) { value ->
            value.toIntOrNull()?.takeIf { it in aspectModes.indices }?.let {
                aspectIdx = it
                playerView.resizeMode = aspectModes[it].first
            }
        }
    }

    /** Aller à un instant : seek natif (Direct Play) ou RELANCE du transcodage. */
    private fun seekToTime() {
        val totalMin = (durationMs / 60000).toInt().coerceAtLeast(1)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "minute (0–$totalMin)"
        }
        AlertDialog.Builder(this)
            .setTitle("Aller à la minute…")
            .setView(input)
            .setPositiveButton("Aller") { _, _ ->
                val min = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                goToAbsolute(min.toLong() * 60000)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDiagnostics() {
        val p = player
        val vf = p?.videoFormat
        val af = p?.audioFormat
        val absPos = absolutePositionMs()
        val conversionLabel =
            if (transcodingAvailable) "conversion disponible" else "Direct Play uniquement"
        val info = buildString {
            appendLine("Serveur : ${PluxyApplication.serverBaseUrl(this@PlayerActivity)}")
            appendLine("Sortie audio : ${AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this@PlayerActivity)).title} · repli $audioFallbackLevel")
            appendLine("Film    : ${item.title}")
            appendLine("Choix   : ${activeStrategy.title}")
            appendLine("Moteur  : ${if (serverKind == "android") "Android" else "PC"} · $conversionLabel")
            appendLine("Mode    : $modeLabel${if (isTranscode) " · transcodage" else ""}")
            appendLine("Position: ${fmt(absPos)} / ${fmt(durationMs)}")
            appendLine("Vidéo   : ${vf?.let { "${it.sampleMimeType?.substringAfter('/')?.uppercase()} ${it.width}x${it.height} @${it.frameRate.toInt()}fps" } ?: "—"}")
            appendLine("Audio   : ${af?.let { "${it.sampleMimeType?.substringAfter('/')?.uppercase()} ${it.channelCount}ch ${it.sampleRate}Hz" } ?: "—"}")
            appendLine("Buffer  : ${((p?.totalBufferedDuration ?: 0L) / 1000)} s en avance")
            appendLine("Renfort : ${if (reinforcedBufferActive) "actif" else "veille automatique"}")
            appendLine("Débit   : ~${vf?.bitrate?.let { it / 1_000_000 } ?: af?.bitrate?.let { it / 1000 } ?: 0} ${if (vf?.bitrate != null) "Mbps" else "kbps"}")
            appendLine()
            appendLine("— Journal —")
            append(Logger.dump().takeLast(2000))
        }
        AlertDialog.Builder(this)
            .setTitle("Infos & logs réseau")
            .setMessage(info)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Effacer logs") { _, _ -> Logger.clear() }
            .show()
    }

    private fun fmt(ms: Long): String =
        "%02d:%02d:%02d".format(ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60)

    /** Horodatage du lecteur : MM:SS, puis H:MM:SS seulement lorsque nécessaire. */
    private fun fmtPlayerTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        return if (hours > 0L) "$hours:%02d:%02d".format(minutes, seconds)
        else "%02d:%02d".format(safe / 60_000L, seconds)
    }

    private fun codecShort(mime: String?): String = when {
        mime == null -> ""
        mime.contains("eac3", true) -> "EAC3"
        mime.contains("ac3", true) -> "AC3"
        mime.contains("dts", true) -> "DTS"
        mime.contains("truehd", true) -> "TrueHD"
        mime.contains("aac", true) || mime.contains("mp4a", true) -> "AAC"
        else -> mime.substringAfter('/').uppercase()
    }

    // ----- Erreurs / repli ----------------------------------------------- //
    private fun trackChoice(format: androidx.media3.common.Format) = PlaybackTrackChoice(
        format.language, format.label, format.sampleMimeType, format.channelCount, format.id,
    )

    private fun applyTrackChoices() {
        val p = player ?: return
        var builder = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesDisabled)
        for ((type, choice) in listOf(C.TRACK_TYPE_AUDIO to audioChoice, C.TRACK_TYPE_TEXT to subtitleChoice)) {
            if (choice == null || (type == C.TRACK_TYPE_TEXT && subtitlesDisabled)) continue
            val candidate = tracksOf(type).maxByOrNull {
                choice.matchScore(trackChoice(it.group.getTrackFormat(it.index)))
            }
            if (candidate != null && choice.matchScore(trackChoice(candidate.group.getTrackFormat(candidate.index))) > 0) {
                builder = builder.setOverrideForType(TrackSelectionOverride(
                    candidate.group.mediaTrackGroup, listOf(candidate.index),
                )).setTrackTypeDisabled(type, false)
            }
        }
        val params = builder.build()
        if (p.trackSelectionParameters != params) p.trackSelectionParameters = params
    }

    private fun playerListener() = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            applyTrackChoices()
            if (audioAdaptationPending) return
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty() && audio.none { group -> (0 until group.length).any { group.isTrackSupported(it) } }) {
                val mime = audio.first().getTrackFormat(0).sampleMimeType
                if (tryAudioAdaptation(mime)) return
                if (audioFallbackLevel >= 2 || !transcodingAvailable || AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this@PlayerActivity)) != AudioOutputMode.AUTO) {
                    showTerminalPlaybackError("La piste audio n'est pas prise en charge par cet appareil ou sa sortie HDMI. " +
                        "Choisissez Automatique ou Audio compatible dans Sortie audio, ou essayez un autre lecteur.", audioFailure = true)
                    return
                }
            }
        }
        override fun onPlaybackStateChanged(state: Int) {
            updatePlayPauseButton()
            updateMediaSessionPlaybackState()
            updateScreenAwake()
            when (state) {
                Player.STATE_BUFFERING -> {
                    setLoadingVisible(true)
                    handler.removeCallbacks(hideControlsRunnable)
                    setStatus("Mise en mémoire tampon… ($modeLabel)")
                    // Rebuffering RÉSEAU = on lisait (READY + playWhenReady), le tampon
                    // s'est vidé, ET la lecture tournait de façon CONTINUE depuis assez
                    // longtemps. Sont donc exclus :
                    //   - le démarrage initial et chaque seek (grâce de seek) ;
                    //   - les salves de buffering qui suivent un seek (tampon mince le
                    //     temps que le transcodage reprenne de l'avance) : la lecture n'a
                    //     alors duré que quelques secondes -> sous le seuil de stabilité.
                    // C'est ce qui évitait de croire à tort « réseau saturé » quand on
                    // recule plusieurs fois de -10 s.
                    val p = player
                    val now = SystemClock.elapsedRealtime()
                    val pastSeek = now - lastSeekAtMs > SEEK_GRACE_MS
                    val playedLongEnough =
                        playStartedAtMs > 0 && now - playStartedAtMs >= MIN_STABLE_PLAY_MS
                    if (wasReady && p?.playWhenReady == true && pastSeek && playedLongEnough) {
                        networkStallStartMs = now     // début d'un stall réseau qualifié
                        onRebuffer()
                    }
                    wasReady = false
                    playStartedAtMs = 0L          // la lecture continue s'interrompt ici
                    // Surveille un chargement qui ne démarre pas / ne progresse plus
                    // (typiquement une lecture directe figée sans erreur) -> repli auto.
                    startBufferWatchdog()
                }
                Player.STATE_READY -> {
                    pendingSeekTargetMs = null
                    setLoadingVisible(false)
                    retries = 0; setStatus("")
                    handler.removeCallbacks(bufferWatchdog)   // le chargement a abouti
                    wasReady = true
                    // Fin d'un rebuffering réseau qualifié -> on cumule sa durée (détection
                    // de la saturation sévère, rebufferings rares mais longs).
                    if (networkStallStartMs > 0L) onNetworkStallEnd()
                    // (Re)départ d'une plage de lecture continue : on mesure sa durée
                    // pour distinguer un vrai rebuffering réseau d'une reprise post-seek.
                    if (playStartedAtMs == 0L) playStartedAtMs = SystemClock.elapsedRealtime()
                    applyTrackChoices()
                }
                Player.STATE_ENDED -> {
                    setLoadingVisible(false)
                    handler.removeCallbacks(hideControlsRunnable)
                    showControls(focusPlayPause = true)
                    handler.removeCallbacks(bufferWatchdog)
                    setStatus("Lecture terminée")
                    offerNextEpisode()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButton()
            updateMediaSessionPlaybackState()
            updateScreenAwake()
            if (isPlaying) scheduleControlsHide()
            else handler.removeCallbacks(hideControlsRunnable)
        }

        /** Source de vérité UNIQUE des seeks : couvre le seek progressif, les boutons
         *  natifs (recul/avance), « aller à un instant » et toute reprise interne.
         *  On marque l'instant du seek et on remet à zéro la plage de lecture continue,
         *  pour que la salve de buffering qui suit ne soit pas comptée comme un
         *  rebuffering réseau (cause de la rétrogradation intempestive quand on recule
         *  plusieurs fois). */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                markSeekTarget(PlaybackSeekPolicy.absolutePosition(streamPositionOffsetMs, newPosition.positionMs))
                lastSeekAtMs = SystemClock.elapsedRealtime()
                playStartedAtMs = 0L
                networkStallStartMs = 0L          // le buffering qui suit est lié au seek
            }
        }

        /** Pause / reprise utilisateur. À la PAUSE, la lecture continue s'arrête : on
         *  remet à zéro l'horloge de stabilité pour qu'un buffering au moment de la
         *  REPRISE (tampon dilué pendant une longue pause, session de transcodage en
         *  veille) ne soit pas pris pour un rebuffering réseau. Ne se déclenche PAS sur
         *  un simple buffering (playWhenReady reste vrai) -> n'interfère pas avec la
         *  détection réseau. */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayPauseButton()
            updateMediaSessionPlaybackState()
            updateScreenAwake()
            if (!playWhenReady) {
                handler.removeCallbacks(hideControlsRunnable)
            }
            playStartedAtMs = if (
                playWhenReady && player?.playbackState == Player.STATE_READY
            ) {
                SystemClock.elapsedRealtime()
            } else {
                0L
            }
            // Reprise PENDANT un buffering (une pause ne change pas l'état BUFFERING, donc
            // aucun nouveau STATE_BUFFERING ne ré-arme le chien de garde) : on le ré-arme
            // ici pour ne pas compter le temps de pause dans le budget « bloqué ».
            if (playWhenReady && player?.playbackState == Player.STATE_BUFFERING) {
                startBufferWatchdog()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            setLoadingVisible(false)
            updateMediaSessionPlaybackState()
            Logger.log("error", "${error.errorCodeName} (attempt $attemptIdx)")
            val rendererMime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
            Logger.log("error", "renderer=$rendererMime detail=${error.cause?.message?.take(180)}")
            learnDeterministicFormatFailure(error)
            if (audioAdaptationPending) return
            if (tryAudioAdaptation(rendererMime)) return
            if (rendererMime?.startsWith("audio/") == true) {
                showTerminalPlaybackError("La lecture du son a échoué sur cet appareil ou sa sortie HDMI. " +
                    "Changer la résolution vidéo ne corrige pas ce problème. Essayez un autre lecteur ou une autre piste audio.", audioFailure = true)
                return
            }
            val httpError = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val httpStatus = httpError?.responseCode
            if (PlaybackFailurePolicy.requiresUserActionForPath(httpError?.dataSpec?.uri?.path, httpStatus)) {
                showTerminalPlaybackError(getString(
                    if (httpStatus == 401 || httpStatus == 403) R.string.player_access_error
                    else R.string.player_file_missing_error,
                ))
                return
            }
            // 1) Avance dans la chaîne de repli (décodage/manifeste/HTTP).
            if (attemptIdx + 1 < attempts.size) {
                setStatus("${attempts[attemptIdx + 1].label} (repli)…")
                loadAttempt(attemptIdx + 1, restorePos = reliablePositionMs())
                return
            }
            if (PlaybackFailurePolicy.isVideoDecoderFailure(error.errorCode, rendererMime)) {
                val format = listOfNotNull(item.videoCodec?.uppercase(),
                    com.pluxy.tv.ui.ArtworkPolicy.resolutionLabel(item.width, item.height),
                    "HDR".takeIf { item.isHdr }).joinToString(" · ")
                showTerminalPlaybackError("Cet appareil ne parvient pas à décoder la vidéo ($format). " +
                    "Le fichier original est conservé. Vous pouvez choisir une version compatible ou essayer un autre lecteur.",
                    videoFailure = true)
                return
            }
            // 2) Chaîne épuisée : on RECHARGE la tentative courante (pas un prepare()
            //    nu qui rejouerait le même flux en échec), avec backoff borné.
            if (retries < 3) {
                retries++
                setLoadingVisible(true)
                setStatus("Reconnexion… ($retries/3)")
                val pos = reliablePositionMs()
                retryRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { if (!isFinishing) loadAttempt(attemptIdx, restorePos = pos) }
                retryRunnable = r
                handler.postDelayed(r, 1500L * retries)
            } else {
                showTerminalPlaybackError(getString(R.string.player_stream_error))
            }
        }
    }

    private fun tryAudioAdaptation(mimeType: String?): Boolean {
        if (!AudioOutputPolicy.canAdapt(activeStrategy,
                AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this)), transcodingAvailable, audioFallbackLevel, mimeType)
        ) return false
        val owner = player ?: return false
        val ownerItem = item.id
        val ownerApi = api
        audioFallbackLevel++
        failedAudioCodec = AudioOutputPolicy.codecForMime(mimeType)
        audioAdaptationPending = true
        // Keep the language choice, but don't force the failed TrueHD/DTS format again.
        audioChoice = audioChoice?.copy(mimeType = null, channels = -1, trackId = null)
        Logger.log("audio", "adaptation audio demandée mime=$mimeType ; capacités vidéo conservées")
        setLoadingVisible(true)
        setStatus("Adaptation du son — vidéo originale si compatible…")
        // Renderer callbacks must finish before releasing their player.
        handler.post {
            audioAdaptationPending = false
            if (activityStarted && !isFinishing && !isDestroyed &&
                player === owner && item.id == ownerItem && api === ownerApi
            ) reprepareForStrategy(activeStrategy)
        }
        return true
    }

    private fun learnDeterministicFormatFailure(error: PlaybackException) {
        val current = attempts.getOrNull(attemptIdx) ?: return
        if (current.delivery != "direct") return
        val containerFailure =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        if (!containerFailure && !PlaybackFailurePolicy.isVideoRenderer((error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType)) return
        val formatFailure =
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        val profileFailure =
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        val decodingFailure = formatFailure || profileFailure || containerFailure ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        if (!decodingFailure) return
        val normalizedCodec = item.videoCodec?.lowercase()
        val codec = when {
            containerFailure -> null
            profileFailure && normalizedCodec in setOf("hevc", "h265") && item.isHdr -> "hevc10"
            profileFailure -> null // résolution/profil exact inconnu : ne pas condamner tout le codec
            else -> item.videoCodec
        }
        val container = item.container.takeIf { containerFailure }
        if (codec.isNullOrBlank() && container.isNullOrBlank()) return
        tvProfileStore.recordFormatFailure(
            codec = codec,
            container = container,
            deterministic = formatFailure || profileFailure || containerFailure,
        )
        Logger.log(
            "profile",
            "échec format appris codec=$codec conteneur=$container certain=${formatFailure || profileFailure || containerFailure}",
        )
    }

    private fun prefetchNextEpisode() {
        nextEpisodePrefetchJob?.cancel()
        prefetchedNextEpisode = null
        nextEpisodePrefetched = false
        if (item.kind != "series") return
        val ownerApi = api
        val current = item
        nextEpisodePrefetchJob = lifecycleScope.launch {
            try {
                val library = CatalogueRepository.cached(ownerApi.baseUrl, "series")?.items
                    ?: ownerApi.listItems(LibraryRequest(kind = "series"))
                if (api === ownerApi && item.id == current.id) {
                    prefetchedNextEpisode = EpisodeQueue.next(current, library)
                    nextEpisodePrefetched = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // La lecture continue ; une nouvelle tentative reste possible à la fin.
            }
        }
    }

    private fun offerNextEpisode() {
        if (nextEpisodeHandled || item.kind != "series" || isFinishing) return
        nextEpisodeHandled = true
        val ownerApi = api
        val finishedItem = item
        nextEpisodeJob?.cancel()
        nextEpisodeJob = lifecycleScope.launch {
            nextEpisodePrefetchJob?.join()
            val next = if (nextEpisodePrefetched) prefetchedNextEpisode else {
                var library: List<PluxyItem>? = null
                for (attempt in 0 until NEXT_EPISODE_FETCH_ATTEMPTS) {
                    try {
                        library = ownerApi.listItems(LibraryRequest(kind = "series"))
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (attempt + 1 < NEXT_EPISODE_FETCH_ATTEMPTS) delay(750L)
                    }
                }
                val fetchedLibrary = library ?: run {
                    nextEpisodeHandled = false
                    return@launch
                }
                EpisodeQueue.next(finishedItem, fetchedLibrary)
            }
            if (api !== ownerApi || item.id != finishedItem.id ||
                player?.playbackState != Player.STATE_ENDED) return@launch
            if (
                next == null || isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                if (next != null) nextEpisodeHandled = false
                return@launch
            }
            showNextEpisodeDialog(next)
        }
    }

    private fun showNextEpisodeDialog(next: PluxyItem) {
        setStatus("")
        nextEpisodeDialog?.dismiss()
        val automatic = PluxyApplication.autoPlayNextEpisode(this)
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_next_episode)
        dialog.setCanceledOnTouchOutside(false)
        dialog.findViewById<TextView>(R.id.nextEpisodeTitle).text = cleanEpisodeTitle(next, 0)
        dialog.findViewById<TextView>(R.id.nextEpisodeCode).text =
            listOfNotNull(next.seriesName, episodeCode(next)).joinToString(" · ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.findViewById<View>(R.id.nextEpisodePlay).defaultFocusHighlightEnabled = false
            dialog.findViewById<View>(R.id.nextEpisodeStay).defaultFocusHighlightEnabled = false
        }
        val artwork = dialog.findViewById<ImageView>(R.id.nextEpisodeArtwork)
        com.pluxy.tv.ui.EpisodeArtwork.load(artwork, next, api.baseUrl)
        val countdown = dialog.findViewById<TextView>(R.id.nextEpisodeCountdown)
        countdown.text = if (automatic) getString(R.string.player_next_countdown, NEXT_EPISODE_DELAY_SECONDS)
            else getString(R.string.player_next_manual)
        nextEpisodeDialog = dialog
        dialog.setOnShowListener {
            dialog.findViewById<Button>(R.id.nextEpisodePlay).apply {
                isFocusableInTouchMode = resources.configuration.uiMode and
                    Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
                setOnClickListener {
                    playNextEpisode(next)
                }
                requestFocus()
            }
            dialog.findViewById<Button>(R.id.nextEpisodeStay).setOnClickListener {
                dialog.dismiss()
                setStatus("Lecture terminée")
            }
            if (automatic) {
                val deadline = SystemClock.elapsedRealtime() + NEXT_EPISODE_DELAY_SECONDS * 1_000L
                val tick = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing || !activityStarted) return
                        val seconds = episodeCountdownSeconds(deadline, SystemClock.elapsedRealtime())
                        if (seconds <= 0) playNextEpisode(next)
                        else {
                            countdown.text = getString(R.string.player_next_countdown, seconds)
                            handler.postDelayed(this, 1_000L)
                        }
                    }
                }
                nextEpisodeTick = tick
                handler.postDelayed(tick, 1_000L)
            }
        }
        dialog.setOnDismissListener {
            nextEpisodeTick?.let { handler.removeCallbacks(it) }
            nextEpisodeTick = null
            if (nextEpisodeDialog === dialog) nextEpisodeDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val density = resources.displayMetrics.density
            val displayWidth = resources.displayMetrics.widthPixels
            val edge = resources.getDimensionPixelSize(R.dimen.media_viewer_screen_pad)
            setLayout(minOf((440 * density).toInt(), displayWidth - edge * 2), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.END)
            attributes = attributes.apply {
                x = edge
                y = resources.getDimensionPixelSize(R.dimen.player_edge_gap)
                dimAmount = 0f
            }
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.elevation = 0f
        }
        hideControls()
    }

    private fun playNextEpisode(
        next: PluxyItem,
        markPreviousCompleted: Boolean = true,
        serverUrl: String = api.baseUrl,
    ) {
        cancelSeekRebuild()
        pendingSeekTargetMs = null
        val previousId = item.id
        val previousDuration = durationMs
        val previousApi = api
        if (!markPreviousCompleted) saveProgress()
        resumeDialog?.dismiss()
        resumeDialog = null
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = null
        nextEpisodeTick?.let { handler.removeCallbacks(it) }
        nextEpisodeTick = null
        nextEpisodeDialog?.dismiss()
        nextEpisodeDialog = null
        prepareJob?.cancel()
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(bufferWatchdog)
        cancelPendingSeek()
        player?.release()
        player = null
        playerView.player = null
        if (markPreviousCompleted && previousDuration > 0L) {
            ProgressOutbox.enqueue(this, previousApi, previousId, previousDuration,
                previousDuration, ++progressSequence)
        }
        ioScope.launch { previousApi.stopHls(previousId) }

        item = next
        loadSkipChapters(serverUrl)
        audioFallbackLevel = 0
        failedAudioCodec = null
        intent.putExtra(EXTRA_ITEM, itemAdapter.toJson(next))
        api = PluxyApi(serverUrl)
        progressSequence = 0L
        buffer = null
        attempts.clear()
        attemptIdx = 0
        savedPositionMs = 0L
        savedPlayWhenReady = true
        lastGoodPositionMs = 0L
        lastSeekAtMs = 0L
        startFromBeginning = !markPreviousCompleted && intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        autoResume = !markPreviousCompleted && !startFromBeginning && intent.getBooleanExtra(EXTRA_AUTO_RESUME, false)
        resumeHandled = startFromBeginning
        intent.putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
        intent.putExtra(EXTRA_AUTO_RESUME, autoResume)
        if (!markPreviousCompleted) {
            audioChoice = null
            subtitleChoice = null
            subtitlesDisabled = PluxyApplication.subsMode(this) == "off"
            playbackSpeed = 1f
        }
        nextEpisodeHandled = false
        networkConstraintRecorded = false
        stableProfileRecorded = false
        mediaTitleView.text = LibraryOrganizer.playbackTitle(item)
        timePositionView.text = fmtPlayerTime(0L)
        timeDurationView.text = fmtPlayerTime(durationMs)
        timeline.setDuration(durationMs.coerceAtLeast(0L))
        timeline.setPosition(0L)
        updateMediaSessionMetadata()
        setLoadingVisible(true)
        setStatus("Préparation de l'épisode suivant…")
        prepareAndPlay(strategyOverride ?: if (forceDirectForLaunch) PlaybackStrategy.DIRECT
            else PlaybackStrategy.fromKey(PluxyApplication.playbackStrategy(this)))
        prefetchNextEpisode()
    }

    // ---- Chien de garde : chargement bloqué ------------------------------- //
    private fun startBufferWatchdog() {
        val p = player ?: return
        handler.removeCallbacks(bufferWatchdog)
        bufWatchLastPos = p.bufferedPosition.coerceAtLeast(0)
        bufWatchLastBytes = transferBytes.get()
        bufWatchProgressAtMs = SystemClock.elapsedRealtime()
        bufWatchStartedAtMs = bufWatchProgressAtMs
        handler.postDelayed(bufferWatchdog, BUF_WATCH_POLL_MS)
    }

    private val bufferWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_BUFFERING) return   // chargement terminé
            if (!p.playWhenReady) {                                 // en pause : on attend
                handler.postDelayed(this, BUF_WATCH_POLL_MS); return
            }
            val now = SystemClock.elapsedRealtime()
            val pos = p.bufferedPosition.coerceAtLeast(0)
            val bytes = transferBytes.get()
            if (pos > bufWatchLastPos + 200 ||
                bytes - bufWatchLastBytes >= PlaybackBufferWatchPolicy.BYTE_PROGRESS_THRESHOLD
            ) {
                bufWatchLastPos = pos
                bufWatchLastBytes = bytes
                bufWatchProgressAtMs = now
            }
            if (PlaybackSeekRecoveryPolicy.renderedBufferStuck(
                    now - bufWatchStartedAtMs, p.totalBufferedDuration,
                    !isTranscode, now - lastSeekAtMs < 60_000L,
                    networkLoading = p.isLoading,
                ) || PlaybackBufferWatchPolicy.isStuck(
                    now - bufWatchStartedAtMs, now - bufWatchProgressAtMs, isTranscode,
                    conversion = attempts.getOrNull(attemptIdx)?.delivery == "conversion",
                )) { onLoadStuck(); return }
            handler.postDelayed(this, BUF_WATCH_POLL_MS)
        }
    }

    /** Chargement bloqué : Auto descend d'abord vers le meilleur palier adaptatif.
     *  Les autres modes suivent leur chaîne de secours, avec des relances bornées. */
    private fun onLoadStuck() {
        handler.removeCallbacks(bufferWatchdog)
        val pos = reliablePositionMs()
        Logger.log("stuck", "chargement bloqué mode=${if (isTranscode) "hls" else "direct"} attempt=$attemptIdx pos=$pos")
        val attempt = attempts.getOrNull(attemptIdx)
        val recentSeek = pendingSeekTargetMs != null || SystemClock.elapsedRealtime() - lastSeekAtMs < 60_000L
        when (PlaybackSeekRecoveryPolicy.action(attempt?.delivery == "direct", recentSeek,
                attempt?.let { PlaybackSeekPolicy.isRemux(it.url) } == true,
                serverKind == "pc" && transcodingAvailable && directStreamSeekAvailable, watchdogReloads)) {
            PlaybackSeekRecoveryPolicy.Action.REBUILD -> {
                Logger.log("seek", "rendu bloqué -> nouveau moteur à $pos, vidéo/audio conservés")
                rebuildAfterSeek(pos, separateAudioRelease = true, recoveryCount = 1)
                return
            }
            PlaybackSeekRecoveryPolicy.Action.TEMPORAL_COPY -> {
                watchdogReloads = 2
                attempts[attemptIdx] = attempt!!.copy(url = "/stream/remux/${item.id}?copy_audio=true",
                    label = "Direct Stream · vidéo et audio d’origine")
                Logger.log("seek", "index direct bloqué -> copie temporelle à $pos")
                markSeekTarget(pos)
                rebuildAfterSeek(pos, separateAudioRelease = true, recoveryCount = 2)
                return
            }
            PlaybackSeekRecoveryPolicy.Action.NORMAL_FALLBACK -> Unit
        }
        when {
            adaptiveConversion && maybeDownshift(learnNetworkConstraint = networkStallStartMs > 0L) -> Unit
            attemptIdx + 1 < attempts.size -> {
                setStatus("Chargement lent — bascule…")
                loadAttempt(attemptIdx + 1, restorePos = pos)
            }
            watchdogReloads < 2 -> {
                watchdogReloads++
                setStatus("Chargement bloqué — nouvelle tentative…")
                loadAttempt(attemptIdx, restorePos = pos)
            }
            else -> {
                showTerminalPlaybackError(getString(R.string.player_stalled_error))
            }
        }
    }

    private fun loadAttempt(
        idx: Int,
        restorePos: Long,
        playWhenReady: Boolean = player?.playWhenReady ?: savedPlayWhenReady,
    ) {
        val p = player ?: return
        clearTerminalPlaybackError()
        setLoadingVisible(true)
        // Réinitialise le compteur d'erreurs UNIQUEMENT en changeant de palier : sinon un
        // rechargement du MÊME palier (retry d'erreur ou chien de garde) remettrait le
        // compteur à zéro et la borne « retries < 3 » ne serait jamais atteinte -> boucle
        // de re-préparation ~toutes les 1,5 s au dernier palier. (buildPlayer remet déjà
        // retries=0 pour un démarrage neuf.)
        if (idx != attemptIdx) retries = 0
        attemptIdx = idx
        if ((attempts[idx].delivery == "conversion") != conversionBufferActive) {
            savedPositionMs = restorePos
            savedPlayWhenReady = playWhenReady
            p.release()
            player = null
            playerView.player = null
            buildPlayer()
            return
        }
        retryRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(bufferWatchdog)
        // Annule un seek progressif EN ATTENTE : sans ça son commit différé s'appliquerait
        // au flux rechargé avec une base périmée -> saut de position (parfois au début).
        cancelPendingSeek()
        // Nouveau flux : on repart d'un compteur de rebuffering vierge, et la salve de
        // BUFFERING qui suit prepare()/seek ne doit pas être comptée comme un rebuffer.
        rebufferTimes.clear()
        stallWindow.clear()
        networkStallStartMs = 0L
        wasReady = false
        playStartedAtMs = 0L
        lastSeekAtMs = SystemClock.elapsedRealtime()
        val attempt = attempts[idx]
        modeLabel = describeAttempt(attempt, idx)
        updateControlsMetadata()
        Logger.log(
            "attempt",
            "index=$idx tier=${attempt.tier} delivery=${attempt.delivery} url=${attempt.url}",
        )
        val temporal = PlaybackSeekPolicy.isRemux(attempt.url)
        if (restorePos > 0L) pendingSeekTargetMs = restorePos
        streamPositionOffsetMs = if (temporal && directStreamSeekAvailable) restorePos.coerceAtLeast(0L) else 0L
        if (temporal && restorePos > 0 && !directStreamSeekAvailable) {
            setStatus("Mettez à jour le serveur PC pour reprendre ce flux.")
            setLoadingVisible(false)
            return
        }
        val url = if (temporal) Uri.parse(attempt.url).buildUpon()
            .appendQueryParameter("start_ms", streamPositionOffsetMs.toString()).build().toString() else attempt.url
        Logger.log("seek", "load absolute=$restorePos offset=$streamPositionOffsetMs temporal=$temporal")
        p.setMediaItem(buildMediaItem(url, attempt.delivery), if (temporal) 0L else restorePos.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = playWhenReady
    }

    /** Comptabilise un rebuffering (au DÉBUT du buffering) et déclenche une bascule si
     *  les rebufferings sont trop FRÉQUENTS sur la fenêtre. */
    private fun onRebuffer() {
        val now = SystemClock.elapsedRealtime()
        lastQualifiedStallAtMs = now
        rebufferTimes.addLast(now)
        while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > REBUFFER_WINDOW_MS)
            rebufferTimes.removeFirst()
        Logger.log("rebuffer", "count=${rebufferTimes.size}/$REBUFFER_THRESHOLD attempt=$attemptIdx")
        if (rebufferTimes.size >= REBUFFER_THRESHOLD) maybeDownshift()
    }

    /** À la FIN d'un rebuffering réseau qualifié : cumule sa durée sur la fenêtre et
     *  bascule si le temps total passé à attendre dépasse le budget. Capte la saturation
     *  sévère (rebufferings rares mais longs) que le simple comptage d'événements rate :
     *  plus le débit s'effondre, plus chaque attente s'allonge, donc MOINS d'événements
     *  tiennent dans la fenêtre — le cumul de durée, lui, ne souffre pas de ce biais. */
    private fun onNetworkStallEnd() {
        val now = SystemClock.elapsedRealtime()
        lastQualifiedStallAtMs = now
        stallWindow.addLast(now to (now - networkStallStartMs))
        networkStallStartMs = 0L
        while (stallWindow.isNotEmpty() && now - stallWindow.first().first > REBUFFER_WINDOW_MS)
            stallWindow.removeFirst()
        val totalStall = stallWindow.sumOf { it.second }
        Logger.log("stall", "cumul=${totalStall / 1000}s/${STALL_BUDGET_MS / 1000}s attempt=$attemptIdx")
        if (totalStall >= STALL_BUDGET_MS) maybeDownshift()
    }

    /** Descend d'un palier de qualité pour tenir le débit réseau (sans perdre la position).
     *  Direct Play -> « main » (HEVC/HDR plafonné) -> « compat » (1080p). Au plus bas
     *  palier, on ne fait rien (le compat 1080p tient sur n'importe quel réseau). */
    private fun maybeDownshift(learnNetworkConstraint: Boolean = true): Boolean {
        if (activeStrategy == PlaybackStrategy.DIRECT) {
            if (learnNetworkConstraint) warnNetworkLimitOnce()
            return false
        }
        if (!adaptiveConversion) return false
        val target = PlaybackPlanner.nextAdaptiveIndex(attempts, attemptIdx)
        if (target <= attemptIdx) {
            // Le serveur Android ne peut pas réencoder : on conserve donc strictement le
            // Direct Play, mais on reconstruit ExoPlayer avec un coussin réseau plus large.
            if (!transcodingAvailable && !reinforcedBufferActive && !isTranscode) {
                return enableReinforcedBuffer()
            }
            warnNetworkLimitOnce()
            return false
        }
        if (learnNetworkConstraint && attempts.getOrNull(attemptIdx)?.delivery != "conversion") recordNetworkConstraintOnce()
        rebufferTimes.clear()
        stallWindow.clear()
        networkStallStartMs = 0L
        val toCompat = attempts[target].tier == PlaybackTier.COMPATIBILITY
        val msg = if (attempts[target].delivery == "conversion") {
            "Qualité adaptée : ${attempts[target].label}…"
        } else if (!learnNetworkConstraint) {
            if (toCompat) "Lecture bloquée : essai du mode compatible 1080p…"
            else "Lecture bloquée : essai d'un flux adapté…"
        } else if (toCompat) "Réseau limité : passage en 1080p compatible…"
        else "Réseau instable : qualité adaptée au débit…"
        setStatus(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Logger.log("downshift", "-> attempt $target (${attempts[target].url})")
        adaptiveHistory.addLast(attemptIdx)
        lastAdaptiveChangeAtMs = SystemClock.elapsedRealtime()
        loadAttempt(target, restorePos = reliablePositionMs())
        return true
    }

    /**
     * Après dix minutes réellement stables, remonte d'un seul palier. L'hystérésis
     * évite les oscillations : chaque nouveau stall qualifié remet le délai à zéro.
     */
    private fun maybeRecoverQuality() {
        val p = player ?: return
        maybeRecordStableProfile(p)
        if (!adaptiveConversion || !p.isPlaying || adaptiveHistory.isEmpty()) return
        val current = attempts.getOrNull(attemptIdx) ?: return
        if (current.tier !in setOf(PlaybackTier.ADAPTED, PlaybackTier.COMPATIBILITY)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptiveChangeAtMs < QUALITY_RECOVERY_STABLE_MS ||
            now - lastQualifiedStallAtMs < QUALITY_RECOVERY_STABLE_MS
        ) return
        val target = PlaybackPlanner.recoveryTargetIndex(
            attempts,
            attemptIdx,
            adaptiveHistory.toList(),
        )
        if (target < 0) {
            adaptiveHistory.clear()
            return
        }
        adaptiveHistory.removeLast()
        lastAdaptiveChangeAtMs = now
        rebufferTimes.clear()
        stallWindow.clear()
        val msg = "Connexion stable : retour à ${attempts[target].label}…"
        setStatus(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Logger.log("upshift", "-> attempt $target (${attempts[target].url})")
        loadAttempt(target, restorePos = reliablePositionMs())
    }

    private fun recordNetworkConstraintOnce() {
        if (networkConstraintRecorded) return
        val bitrate = sourceBitrateMbps()
        if (bitrate <= 0.0) return
        networkConstraintRecorded = true
        tvProfileStore.recordNetworkConstraint(bitrate)
        Logger.log("profile", "plafond réseau appris depuis ${"%.1f".format(bitrate)} Mbps")
    }

    private fun maybeRecordStableProfile(p: ExoPlayer) {
        if (stableProfileRecorded || !p.isPlaying || playStartedAtMs <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - playStartedAtMs < QUALITY_RECOVERY_STABLE_MS ||
            now - lastQualifiedStallAtMs < QUALITY_RECOVERY_STABLE_MS
        ) return
        val bitrate = sourceBitrateMbps()
        if (bitrate <= 0.0) return
        stableProfileRecorded = true
        tvProfileStore.recordStablePlayback(bitrate)
        Logger.log("profile", "lecture stable validée à ${"%.1f".format(bitrate)} Mbps")
    }

    private fun sourceBitrateMbps(): Double =
        if (item.duration > 0.0 && item.size > 0L) item.size * 8.0 / item.duration / 1_000_000.0
        else 0.0

    /** Active une fois le tampon renforcé, en gardant le flux original et la position. */
    private fun enableReinforcedBuffer(): Boolean {
        val currentBuffer = buffer ?: return false
        savedPositionMs = reliablePositionMs()
        savedPlayWhenReady = player?.playWhenReady ?: savedPlayWhenReady
        if (savedPositionMs > 0L) {
            lastGoodPositionMs = savedPositionMs
        }
        buffer = PlaybackPlanner.reinforcedBuffer(currentBuffer)
        reinforcedBufferActive = true
        rebufferTimes.clear()
        stallWindow.clear()
        networkStallStartMs = 0L
        handler.removeCallbacks(bufferWatchdog)
        cancelPendingSeek()
        val msg = "Réseau instable : Direct Play conservé, tampon renforcé…"
        recordNetworkConstraintOnce()
        setStatus(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Logger.log("downshift", "serveur sans transcodage -> tampon Direct Play renforcé")
        // Différé d'un tour de boucle : cette méthode peut être appelée depuis un callback
        // du lecteur. Libérer ExoPlayer à l'intérieur de son propre callback est fragile.
        handler.post {
            if (isFinishing || isDestroyed) {
                return@post
            }
            player?.release()
            player = null
            playerView.player = null
            buildPlayer()
        }
        return true
    }

    private fun warnNetworkLimitOnce() {
        if (networkLimitWarned) return
        networkLimitWarned = true
        val msg = if (activeStrategy == PlaybackStrategy.DIRECT) {
            "Le débit reste insuffisant. Direct Play conservé : privilégiez Ethernet ou essayez le mode Automatique."
        } else if (!transcodingAvailable) {
            "Le fichier reste trop lourd pour ce réseau. Un serveur PC Pluxy peut réduire le débit."
        } else {
            "Le réseau reste insuffisant au palier compatible. Vérifiez le Wi-Fi ou l'Ethernet."
        }
        setStatus(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Logger.log("network", "palier minimal atteint: $msg")
    }

    private fun saveProgress() {
        if (player == null && pendingSeekTargetMs == null) return
        val pos = reliablePositionMs()
        val dur = durationMs.takeIf { it > 0 } ?: (player?.duration ?: 0L)
        // The activity can advance to the next episode before this IO job runs.
        val itemId = item.id
        if (dur > 0 && ::api.isInitialized) ProgressOutbox.enqueue(this, api,
            itemId, pos, dur, ++progressSequence)
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
        const val EXTRA_FORCE_DIRECT = "extra_force_direct"
        const val EXTRA_START_FROM_BEGINNING = "start_from_beginning"
        const val EXTRA_AUTO_RESUME = "auto_resume"
        private const val STATE_SESSION = "playback_session_state"
        // Bascule adaptative = simple FILET DE SECOURS : on ne descend d'un palier que si
        // ça bégaie vraiment souvent (réseau réellement insuffisant), pas sur un pic
        // ponctuel d'un gros fichier 4K — que le tampon costaud absorbe désormais.
        private const val REBUFFER_WINDOW_MS = 120_000L
        private const val REBUFFER_THRESHOLD = 5
        // Budget de temps cumulé en rebuffering réseau sur la fenêtre : au-delà, on
        // bascule même si le nombre d'événements n'a pas atteint le seuil (saturation
        // sévère = peu de rebufferings mais très longs). 30 s d'attente sur 120 s = ko.
        private const val STALL_BUDGET_MS = 30_000L
        // Un BUFFERING survenant juste après un seek n'est pas un rebuffer réseau.
        private const val SEEK_GRACE_MS = 2_500L
        // Fréquence d'inspection du chien de garde de chargement.
        private const val BUF_WATCH_POLL_MS = 2_000L
        // Nombre de tentatives de la phase de décision (appels de contrôle au serveur).
        private const val PREPARE_ATTEMPTS = 3
        // Délai d'auto-masquage des contrôles (assez long pour naviguer sereinement).
        private const val CONTROLLER_TIMEOUT_MS = 3_000
        private const val SEEK_PREVIEW_TICK_MS = 50L
        private const val SEEK_RELEASE_FALLBACK_MS = 900L
        private const val NEXT_EPISODE_DELAY_SECONDS = 5
        private const val NEXT_EPISODE_FETCH_ATTEMPTS = 2
        // Durée de lecture CONTINUE minimale avant qu'un buffering puisse compter comme
        // un rebuffer réseau. Les reprises post-seek (tampon mince, transcodage qui
        // rattrape) ne durent que quelques secondes -> exclues. Un vrai rebuffer réseau
        // survient après ≥15 s de lecture (seuil ExoPlayer de reprise) -> bien au-dessus.
        private const val MIN_STABLE_PLAY_MS = 10_000L
        // Remontée de qualité très conservatrice : un seul palier après dix minutes
        // sans stall qualifié, puis nouveau délai complet avant toute autre remontée.
        private const val QUALITY_RECOVERY_STABLE_MS = 10 * 60_000L
        // Scope process-wide pour les nettoyages réseau qui doivent aboutir
        // même après destruction de l'activité (libération NVENC, dernière position).
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
