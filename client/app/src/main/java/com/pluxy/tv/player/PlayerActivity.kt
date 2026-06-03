package com.pluxy.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.BufferConfig
import com.pluxy.tv.api.MediaItem as PluxyItem
import com.pluxy.tv.api.PluxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private var player: ExoPlayer? = null
    private val api = PluxyApi.create()
    private lateinit var item: PluxyItem

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retries = 0

    // État conservé entre onStop/onStart (recréation du player sans tout refaire).
    private var buffer: BufferConfig? = null
    private val attempts = mutableListOf<Pair<String, String>>()   // (url, delivery)
    private var attemptIdx = 0
    private var savedPositionMs = 0L
    private var resumeHandled = false
    private var modeLabel = ""

    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajusté",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré",
    )
    private var aspectIdx = 0
    private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private val saveTick = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.status)

        if (!PluxyApplication.isConfigured(this)) {
            Toast.makeText(this, "Aucun serveur configuré", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val json = intent.getStringExtra(EXTRA_ITEM) ?: run { finish(); return }
        item = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(PluxyItem::class.java).fromJson(json) ?: run { finish(); return }

        findViewById<Button>(R.id.btnAudio).setOnClickListener { pickTrack(C.TRACK_TYPE_AUDIO, "Piste audio") }
        findViewById<Button>(R.id.btnSubs).setOnClickListener { pickSubtitles() }
        findViewById<Button>(R.id.btnSpeed).setOnClickListener { pickSpeed() }
        findViewById<Button>(R.id.btnAspect).setOnClickListener { cycleAspect() }
        setTrackButtonsEnabled(false)
    }

    // Création/destruction du player calées sur onStart/onStop : pas d'écran noir
    // au retour depuis l'arrière-plan (le player est recréé à la dernière position).
    override fun onStart() {
        super.onStart()
        if (buffer == null) prepareAndPlay() else buildPlayer()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        retryRunnable = null
        player?.let { savedPositionMs = it.currentPosition.coerceAtLeast(0) }
        saveProgress()
        // Nettoyages qui DOIVENT aboutir même si l'activité est détruite juste après.
        ioScope.launch { api.stopHls(item.id) }
        player?.release()
        player = null
    }

    /** Première fois : décision serveur + buffer + chaîne de repli, puis lecture. */
    private fun prepareAndPlay() {
        statusView.text = "Analyse du flux…"
        lifecycleScope.launch {
            try {
                val decision = api.decide(item.id)
                buffer = api.clientRuntime().buffer
                val progress = api.getProgress(item.id)

                attempts.clear()
                attempts.add(decision.streamUrl to decision.delivery)
                if (decision.delivery != "direct")
                    attempts.add("/stream/direct/${item.id}" to "direct")
                attempts.add("/stream/hls/${item.id}/compat/index.m3u8" to "hls")
                attemptIdx = 0
                modeLabel = decision.mode + if (decision.toneMap) " · HDR→SDR" else ""

                buildPlayer()

                if (!resumeHandled && progress.positionMs > 10_000 && !progress.watched) {
                    resumeHandled = true
                    askResume(progress.positionMs)
                }
                statusView.text = "Mode : $modeLabel"
            } catch (e: Exception) {
                statusView.text = "Erreur : ${e.message}"
            }
        }
    }

    /** (Re)construit l'ExoPlayer depuis l'état conservé, à la dernière position. */
    private fun buildPlayer() {
        if (player != null || buffer == null) return
        val exo = PluxyPlayerFactory.build(this, buffer!!)
        player = exo
        playerView.player = exo
        styleSubtitles()
        exo.addListener(playerListener())
        retries = 0
        loadAttempt(attemptIdx, restorePos = savedPositionMs)
        handler.removeCallbacks(saveTick)
        handler.postDelayed(saveTick, 10_000)
    }

    private fun askResume(positionMs: Long) {
        val mmss = "%02d:%02d".format(positionMs / 60000, (positionMs / 1000) % 60)
        AlertDialog.Builder(this)
            .setTitle("Reprendre la lecture ?")
            .setPositiveButton("Reprendre ($mmss)") { _, _ -> player?.seekTo(positionMs) }
            .setNegativeButton("Depuis le début") { _, _ -> player?.seekTo(0) }
            .show()
    }

    private fun buildMediaItem(url: String, delivery: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(api.abs(url)))
        if (delivery == "hls") b.setMimeType(MimeTypes.APPLICATION_M3U8)
        val subs = item.subtitles
        if (subs.isNotEmpty()) {
            b.setSubtitleConfigurations(subs.mapIndexed { i, t ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.abs("/stream/subs/${item.id}/${t.index}")))
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
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.abs("/stream/subs/${item.id}/$idx")))
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
                val lang = f.language?.uppercase() ?: "—"
                val label = when (type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val ch = if (f.channelCount > 0) "${f.channelCount}ch" else ""
                        listOf(lang, codecShort(f.sampleMimeType), ch).filter { it.isNotBlank() }.joinToString(" · ")
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
        val labels = opts.map { (if (it.selected) "✓ " else "   ") + it.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, which ->
            val o = opts[which]
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
                .setTrackTypeDisabled(type, false).build()
        }.show()
    }

    private fun pickSubtitles() {
        val p = player ?: return
        val opts = tracksOf(C.TRACK_TYPE_TEXT)
        val labels = (listOf("Désactivés") + opts.map { (if (it.selected) "✓ " else "   ") + it.label }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Sous-titres").setItems(labels) { _, which ->
            val b = p.trackSelectionParameters.buildUpon()
            if (which == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else {
                val o = opts[which - 1]
                b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            p.trackSelectionParameters = b.build()
        }.show()
    }

    private fun pickSpeed() {
        val p = player ?: return
        AlertDialog.Builder(this).setTitle("Vitesse de lecture")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, which ->
                p.playbackParameters = PlaybackParameters(speeds[which])
            }.show()
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectModes.size
        playerView.resizeMode = aspectModes[aspectIdx].first
        Toast.makeText(this, "Format : ${aspectModes[aspectIdx].second}", Toast.LENGTH_SHORT).show()
    }

    private fun setTrackButtonsEnabled(on: Boolean) {
        intArrayOf(R.id.btnAudio, R.id.btnSubs).forEach { findViewById<Button>(it).isEnabled = on }
    }

    private fun codecShort(mime: String?): String = when {
        mime == null -> ""
        mime.contains("ac3", true) -> "AC3"
        mime.contains("eac3", true) -> "EAC3"
        mime.contains("dts", true) -> "DTS"
        mime.contains("truehd", true) -> "TrueHD"
        mime.contains("aac", true) -> "AAC"
        else -> mime.substringAfter('/').uppercase()
    }

    // ----- Erreurs / repli ----------------------------------------------- //
    private fun playerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> statusView.text = "Mise en mémoire tampon… ($modeLabel)"
                Player.STATE_READY -> { retries = 0; statusView.text = ""; setTrackButtonsEnabled(true) }
                Player.STATE_ENDED -> statusView.text = "Lecture terminée"
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 1) Avance dans la chaîne de repli (décodage/manifeste/HTTP).
            if (attemptIdx + 1 < attempts.size) {
                statusView.text = when (attempts[attemptIdx + 1].second) {
                    "direct" -> "Lecture directe (repli)…"
                    else -> "Conversion compatible H.264 (repli)…"
                }
                loadAttempt(attemptIdx + 1, restorePos = player?.currentPosition ?: 0L)
                return
            }
            // 2) Chaîne épuisée : on RECHARGE la tentative courante (pas un prepare()
            //    nu qui rejouerait le même flux en échec), avec backoff borné.
            if (retries < 3) {
                retries++
                statusView.text = "Reconnexion… ($retries/3)"
                val pos = player?.currentPosition ?: savedPositionMs
                retryRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { if (!isFinishing) loadAttempt(attemptIdx, restorePos = pos) }
                retryRunnable = r
                handler.postDelayed(r, 1500L * retries)
            } else {
                statusView.text = "Erreur lecture : ${error.errorCodeName}"
            }
        }
    }

    private fun loadAttempt(idx: Int, restorePos: Long) {
        val p = player ?: return
        attemptIdx = idx
        retries = 0
        retryRunnable?.let { handler.removeCallbacks(it) }
        val (url, delivery) = attempts[idx]
        p.setMediaItem(buildMediaItem(url, delivery))
        p.prepare()
        if (restorePos > 0) p.seekTo(restorePos)
        p.playWhenReady = true
    }

    private fun saveProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        // En HLS, p.duration == TIME_UNSET : on retombe sur la durée connue du média.
        val dur = if (p.duration > 0) p.duration else (item.duration * 1000).toLong()
        if (pos > 0 && dur > 0) ioScope.launch { api.setProgress(item.id, pos, dur) }
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
        // Scope process-wide pour les nettoyages réseau qui doivent aboutir
        // même après destruction de l'activité (libération NVENC, dernière position).
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
