package com.pluxy.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
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
import com.pluxy.tv.util.Logger
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
    private val isTranscode get() = attempts.getOrNull(attemptIdx)?.second == "hls"
    // La playlist VOD couvre tout le film : position et durée sont absolues.
    private fun absolutePositionMs(): Long = player?.currentPosition ?: 0L
    private val durationMs get() = (item.duration * 1000).toLong()

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

    // ---- Seek progressif ------------------------------------------------- //
    // Accumule le décalage souhaité SANS toucher à ExoPlayer immédiatement.
    // Un Runnable différé commit le seek réel après une courte inactivité.
    // Pendant l'accumulation, un overlay centré affiche la direction, le delta
    // et la position cible — le tout disparaît dès que le seek est appliqué.
    private var isSeeking = false
    private var seekBaseMs = 0L        // position au début de la séquence de touches
    private var seekAccumMs = 0L       // delta accumulé (peut être négatif)

    private lateinit var seekOverlay: LinearLayout
    private lateinit var seekArrowView: TextView
    private lateinit var seekDeltaView: TextView
    private lateinit var seekTargetView: TextView

    /** Délai avant de vraiment appliquer le seek.
     *  Plus long en transcodage HLS pour éviter de redémarrer FFmpeg trop tôt. */
    private val seekCommitDelayMs get() = if (isTranscode) 700L else 350L

    /** Paliers de déplacement par événement D-pad selon le temps de maintien.
     *  Android génère les répétitions à ~50 ms/unité après un délai initial de ~400 ms.
     *  repeatCount 0 = premier appui (tap court) ; 20+ ≈ > 1,4 s maintenu. */
    private fun holdIncrement(repeatCount: Int): Long = when {
        repeatCount == 0  -> 10_000L   // tap : 10 s
        repeatCount <= 3  -> 15_000L   // maintenu ~400-550 ms : 15 s / répét.
        repeatCount <= 8  -> 30_000L   // maintenu ~600 ms-1 s : 30 s / répét.
        repeatCount <= 20 -> 60_000L   // maintenu ~1-1,9 s : 1 min / répét.
        else              -> 120_000L  // maintenu > 2 s : 2 min / répét.
    }

    /** Applique un pas de seek dans la direction indiquée et programme le commit. */
    private fun seekStep(dir: Int, repeatCount: Int) {
        val p = player ?: return
        if (!isSeeking) {
            // Première touche de cette séquence : mémoriser la position de départ.
            seekBaseMs = p.currentPosition
            seekAccumMs = 0L
            isSeeking = true
            playerView.hideController()   // pas de double-affichage contrôleur + overlay
        }
        seekAccumMs += holdIncrement(repeatCount) * dir
        val dur = durationMs.takeIf { it > 0 } ?: (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val target = (seekBaseMs + seekAccumMs).coerceIn(0L, dur)

        showSeekOverlay(dir, seekAccumMs, target)

        handler.removeCallbacks(seekCommitRunnable)
        handler.postDelayed(seekCommitRunnable, seekCommitDelayMs)
    }

    private val seekCommitRunnable = Runnable {
        val p = player ?: run { dismissSeekOverlay(); return@Runnable }
        val dur = durationMs.takeIf { it > 0 } ?: (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val target = (seekBaseMs + seekAccumMs).coerceIn(0L, dur)
        p.seekTo(target)
        isSeeking = false
        seekAccumMs = 0L
        dismissSeekOverlay()
    }

    private fun showSeekOverlay(dir: Int, accumMs: Long, targetMs: Long) {
        seekOverlay.visibility = View.VISIBLE
        seekArrowView.text = if (dir > 0) "⏩" else "⏪"
        val sign  = if (accumMs >= 0) "+" else "−"
        val secs  = abs(accumMs) / 1000
        seekDeltaView.text = when {
            secs < 60  -> "$sign${secs}s"
            secs % 60 == 0L -> "$sign${secs / 60}min"
            else -> "$sign${secs / 60}min ${secs % 60}s"
        }
        seekTargetView.text = "→ ${fmt(targetMs)}"
    }

    private fun dismissSeekOverlay() {
        seekOverlay.visibility = View.GONE
    }

    /** Intercepte les flèches D-pad gauche/droite pour le seek progressif.
     *  Consomme l'événement (return true) afin que PlayerView ne bouge pas le focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isLeft  = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val isRight = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if ((isLeft || isRight) && player != null) {
            // ACTION_DOWN (répété tant que la touche est maintenue) → accumule.
            if (event.action == KeyEvent.ACTION_DOWN) {
                seekStep(if (isRight) 1 else -1, event.repeatCount)
                return true
            }
            // ACTION_UP → on consomme pour éviter que PlayerView repositionne le focus.
            if (event.action == KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
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

        // Overlay seek progressif.
        seekOverlay   = findViewById(R.id.seekOverlay)
        seekArrowView = findViewById(R.id.seekArrow)
        seekDeltaView = findViewById(R.id.seekDelta)
        seekTargetView= findViewById(R.id.seekTarget)

        // Roue crantée : visible UNIQUEMENT quand les contrôles du lecteur sont
        // affichés (au tap sur l'écran), comme un lecteur moderne.
        val gear = findViewById<Button>(R.id.btnGear)
        gear.setOnClickListener { openMenu() }
        gear.visibility = View.GONE
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { vis -> gear.visibility = vis }
        )
        playerView.controllerShowTimeoutMs = 3500
    }

    /** Menu unique (roue crantée) regroupant toutes les options de lecture. */
    private fun openMenu() {
        val ready = player?.playbackState == Player.STATE_READY
        val entries = listOf(
            "🎚  Piste audio"        to { pickTrack(C.TRACK_TYPE_AUDIO, "Piste audio") },
            "💬  Sous-titres"        to { pickSubtitles() },
            "⏩  Vitesse de lecture"  to { pickSpeed() },
            "🖼  Format d'image"     to { cycleAspect() },
            "⏱  Aller à un instant…" to { seekToTime() },
            "⚙  Réglages avancés"   to { openAdvancedSettings() },
            "ℹ  Infos & logs réseau" to { showDiagnostics() },
        )
        val labels = entries.map { it.first + if (!ready && it.first.contains("audio")) "  (analyse…)" else "" }
        AlertDialog.Builder(this).setTitle("Options de lecture")
            .setItems(labels.toTypedArray()) { _, w -> entries[w].second() }
            .show()
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
        if (buffer == null) prepareAndPlay() else buildPlayer()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        retryRunnable = null
        isSeeking = false
        dismissSeekOverlay()
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
                Logger.log("decide", "mode=${decision.mode} compat=${decision.compat} url=${decision.streamUrl} reasons=${decision.reasons.joinToString(" | ")}")

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
            .setPositiveButton("Reprendre ($mmss)") { _, _ -> goToAbsolute(positionMs) }
            .setNegativeButton("Depuis le début") { _, _ -> goToAbsolute(0L) }
            .show()
    }

    /** Va à une position ABSOLUE. Seek natif : la playlist VOD couvre tout le film,
     *  ExoPlayer demande le segment cible (transcodé à la demande) -> lecture. */
    private fun goToAbsolute(targetMs: Long) {
        val t = targetMs.coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        player?.seekTo(t)
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
        val info = buildString {
            appendLine("Serveur : ${PluxyApplication.serverBaseUrl(this@PlayerActivity)}")
            appendLine("Film    : ${item.title}")
            appendLine("Mode    : $modeLabel${if (isTranscode) " · transcodage" else ""}")
            appendLine("Position: ${fmt(absPos)} / ${fmt(durationMs)}")
            appendLine("Vidéo   : ${vf?.let { "${it.sampleMimeType?.substringAfter('/')?.uppercase()} ${it.width}x${it.height} @${it.frameRate.toInt()}fps" } ?: "—"}")
            appendLine("Audio   : ${af?.let { "${it.sampleMimeType?.substringAfter('/')?.uppercase()} ${it.channelCount}ch ${it.sampleRate}Hz" } ?: "—"}")
            appendLine("Buffer  : ${((p?.totalBufferedDuration ?: 0L) / 1000)} s en avance")
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
    private var subsApplied = false
    private fun playerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> statusView.text = "Mise en mémoire tampon… ($modeLabel)"
                Player.STATE_READY -> {
                    retries = 0; statusView.text = ""
                    // Préférence « sous-titres désactivés » appliquée une fois.
                    if (!subsApplied) {
                        subsApplied = true
                        if (PluxyApplication.subsMode(this@PlayerActivity) == "off")
                            player?.let {
                                it.trackSelectionParameters = it.trackSelectionParameters
                                    .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            }
                    }
                }
                Player.STATE_ENDED -> statusView.text = "Lecture terminée"
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Logger.log("error", "${error.errorCodeName} (attempt $attemptIdx)")
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
        if (player == null) return
        // Position ABSOLUE (offset de seek transcodé + position courante).
        val pos = absolutePositionMs()
        val dur = durationMs.takeIf { it > 0 } ?: (player?.duration ?: 0L)
        if (pos > 0 && dur > 0) ioScope.launch { api.setProgress(item.id, pos, dur) }
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
        // Scope process-wide pour les nettoyages réseau qui doivent aboutir
        // même après destruction de l'activité (libération NVENC, dernière position).
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
