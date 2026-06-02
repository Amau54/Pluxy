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
import com.pluxy.tv.R
import com.pluxy.tv.api.MediaItem as PluxyItem
import com.pluxy.tv.api.PluxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private var player: ExoPlayer? = null
    private val api = PluxyApi.create()
    private lateinit var item: PluxyItem

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var triedDirect = false
    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajusté",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré",
    )
    private var aspectIdx = 0
    private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private val saveTick = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.status)

        val json = intent.getStringExtra(EXTRA_ITEM) ?: run { finish(); return }
        item = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(PluxyItem::class.java).fromJson(json) ?: run { finish(); return }

        findViewById<Button>(R.id.btnAudio).setOnClickListener { pickTrack(C.TRACK_TYPE_AUDIO, "Piste audio") }
        findViewById<Button>(R.id.btnSubs).setOnClickListener { pickSubtitles() }
        findViewById<Button>(R.id.btnSpeed).setOnClickListener { pickSpeed() }
        findViewById<Button>(R.id.btnAspect).setOnClickListener { cycleAspect() }

        startPlayback()
    }

    private fun startPlayback() {
        statusView.text = "Analyse du flux…"
        lifecycleScope.launch {
            try {
                val decision = api.decide(item.id)
                val runtime = api.clientRuntime()
                val progress = api.getProgress(item.id)

                val exo = PluxyPlayerFactory.build(this@PlayerActivity, runtime.buffer)
                player = exo
                playerView.player = exo
                styleSubtitles()

                exo.setMediaItem(buildMediaItem(item, decision.streamUrl, decision.delivery))
                exo.addListener(playerListener(decision.mode))
                exo.prepare()
                exo.playWhenReady = true

                // Reprise (façon Plex) si position significative et film non terminé.
                if (progress.positionMs > 10_000 && !progress.watched) {
                    askResume(progress.positionMs)
                }
                statusView.text = "Mode : ${decision.mode}" + if (decision.toneMap) " · HDR→SDR" else ""
                handler.postDelayed(saveTick, 10_000)
            } catch (e: Exception) {
                statusView.text = "Erreur : ${e.message}"
                Toast.makeText(this@PlayerActivity, "Connexion serveur impossible", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askResume(positionMs: Long) {
        val mmss = "%02d:%02d".format(positionMs / 60000, (positionMs / 1000) % 60)
        AlertDialog.Builder(this)
            .setTitle("Reprendre la lecture ?")
            .setPositiveButton("Reprendre ($mmss)") { _, _ -> player?.seekTo(positionMs) }
            .setNegativeButton("Depuis le début") { _, _ -> player?.seekTo(0) }
            .show()
    }

    private fun buildMediaItem(it: PluxyItem, streamUrl: String, delivery: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(api.abs(streamUrl)))
        if (delivery == "hls") b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (it.externalSubs.isNotEmpty()) {
            b.setSubtitleConfigurations(it.externalSubs.indices.map { idx ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.abs("/stream/subs/${it.id}/$idx")))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("fr")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            })
        }
        return b.build()
    }

    private fun styleSubtitles() {
        playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setFractionalTextSize(0.058f)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
                )
            )
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
        if (opts.isEmpty()) { Toast.makeText(this, "Aucune piste", Toast.LENGTH_SHORT).show(); return }
        val labels = opts.map { (if (it.selected) "✓ " else "   ") + it.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, which ->
            val o = opts[which]
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
                .setTrackTypeDisabled(type, false)
                .build()
        }.show()
    }

    private fun pickSubtitles() {
        val p = player ?: return
        val opts = tracksOf(C.TRACK_TYPE_TEXT)
        val labels = (listOf("Désactivés") + opts.map { (if (it.selected) "✓ " else "   ") + it.label }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Sous-titres").setItems(labels) { _, which ->
            if (which == 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            } else {
                val o = opts[which - 1]
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
            }
        }.show()
    }

    private fun pickSpeed() {
        val p = player ?: return
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Vitesse de lecture").setItems(labels) { _, which ->
            p.playbackParameters = PlaybackParameters(speeds[which])
        }.show()
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectModes.size
        playerView.resizeMode = aspectModes[aspectIdx].first
        Toast.makeText(this, "Format : ${aspectModes[aspectIdx].second}", Toast.LENGTH_SHORT).show()
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

    // ----- Cycle de vie / erreurs ---------------------------------------- //
    private fun playerListener(mode: String) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            statusView.text = when (state) {
                Player.STATE_BUFFERING -> "Mise en mémoire tampon… ($mode)"
                Player.STATE_READY -> { retries = 0; "" }
                Player.STATE_ENDED -> "Lecture terminée"
                else -> statusView.text
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 1) Premier échec (HLS/transcode/manifeste KO) -> repli Direct Play.
            if (!triedDirect) {
                triedDirect = true
                statusView.text = "Lecture directe (repli)…"
                playDirect()
                return
            }
            // 2) Sinon : reconnexion automatique avec backoff, façon Plex.
            if (retries < 3) {
                retries++
                statusView.text = "Reconnexion… ($retries/3)"
                handler.postDelayed({ player?.prepare(); player?.play() }, 1500L * retries)
            } else {
                statusView.text = "Erreur lecture : ${error.errorCodeName}"
            }
        }
    }

    /** Repli : relit le fichier brut (Direct Play) sans passer par HLS/transcode. */
    private fun playDirect() {
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        p.setMediaItem(buildMediaItem(item, "/stream/direct/${item.id}", "direct"))
        p.prepare()
        if (pos > 0) p.seekTo(pos)
        p.playWhenReady = true
    }

    private fun saveProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.coerceAtLeast(0)
        if (pos > 0 && dur > 0) lifecycleScope.launch { api.setProgress(item.id, pos, dur) }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        lifecycleScope.launch { api.stopHls(item.id) }   // libère la session NVENC
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
    }
}
