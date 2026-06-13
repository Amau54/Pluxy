package com.pluxy.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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

    // ---- Bascule adaptative au débit réseau (façon Plex « optimize for connection ») //
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
            // On GARDE les contrôles affichés : le seek se fait « sur la barre », et la
            // masquer ici ferait perdre le focus de la timeline en plein appui maintenu.
            seekBaseMs = p.currentPosition
            seekAccumMs = 0L
            isSeeking = true
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
        lastSeekAtMs = SystemClock.elapsedRealtime()
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
        // Le seek progressif ne s'active QUE lorsque l'utilisateur est « sur la barre »
        // (contrôles ouverts ET focus sur la timeline). Hors de là, on NE capture PAS
        // les flèches : la navigation native fait son travail (déplacer le focus entre
        // les boutons, ou afficher les contrôles en lecture nue) -> les flèches ne
        // cassent plus les menus/boutons.
        if ((isLeft || isRight) && player != null && isSeekBarFocused()) {
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

    /** Vrai si les contrôles sont affichés ET le focus est sur la barre de progression. */
    private fun isSeekBarFocused(): Boolean {
        if (!playerView.isControllerFullyVisible) return false
        return currentFocus?.id == androidx.media3.ui.R.id.exo_progress
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // La TV lance son économiseur d'écran / sa mise en veille après quelques
        // minutes SANS action télécommande. Pendant un film on ne touche à rien :
        // au bout de ~5 min le système enclenche la veille et MET LA LECTURE EN PAUSE,
        // puis recommence à chaque cycle. FLAG_KEEP_SCREEN_ON signale au système que
        // l'écran est activement utilisé -> ni veille ni économiseur tant que le
        // lecteur est ouvert. media3 PlayerView ne le pose pas tout seul.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                // Paliers de repli ET de bascule adaptative réseau :
                //  - « main »   = HEVC/HDR transcodé à débit plafonné (qualité quasi
                //    intacte). Pertinent seulement si l'appareil décode bien la source
                //    (décision en lecture directe) ; inutile/incompatible sinon.
                //  - « compat » = H.264 1080p universel, dernier recours garanti lisible.
                val mainHls = "/stream/hls/${item.id}/main/index.m3u8"
                val compatHls = "/stream/hls/${item.id}/compat/index.m3u8"
                val deviceDecodesSource = decision.delivery == "direct"
                if (deviceDecodesSource && attempts.none { it.first == mainHls })
                    attempts.add(mainHls to "hls")
                if (attempts.none { it.first == compatHls })
                    attempts.add(compatHls to "hls")
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
        wasReady = false
        playStartedAtMs = 0L
        networkStallStartMs = 0L
        rebufferTimes.clear()
        stallWindow.clear()
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
        lastSeekAtMs = SystemClock.elapsedRealtime()
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
                Player.STATE_BUFFERING -> {
                    statusView.text = "Mise en mémoire tampon… ($modeLabel)"
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
                }
                Player.STATE_READY -> {
                    retries = 0; statusView.text = ""
                    wasReady = true
                    // Fin d'un rebuffering réseau qualifié -> on cumule sa durée (détection
                    // de la saturation sévère, rebufferings rares mais longs).
                    if (networkStallStartMs > 0L) onNetworkStallEnd()
                    // (Re)départ d'une plage de lecture continue : on mesure sa durée
                    // pour distinguer un vrai rebuffering réseau d'une reprise post-seek.
                    if (playStartedAtMs == 0L) playStartedAtMs = SystemClock.elapsedRealtime()
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
            playStartedAtMs =
                if (playWhenReady && player?.playbackState == Player.STATE_READY)
                    SystemClock.elapsedRealtime() else 0L
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
        // Nouveau flux : on repart d'un compteur de rebuffering vierge, et la salve de
        // BUFFERING qui suit prepare()/seek ne doit pas être comptée comme un rebuffer.
        rebufferTimes.clear()
        stallWindow.clear()
        networkStallStartMs = 0L
        wasReady = false
        playStartedAtMs = 0L
        lastSeekAtMs = SystemClock.elapsedRealtime()
        val (url, delivery) = attempts[idx]
        p.setMediaItem(buildMediaItem(url, delivery))
        p.prepare()
        if (restorePos > 0) p.seekTo(restorePos)
        p.playWhenReady = true
    }

    /** Comptabilise un rebuffering (au DÉBUT du buffering) et déclenche une bascule si
     *  les rebufferings sont trop FRÉQUENTS sur la fenêtre. */
    private fun onRebuffer() {
        val now = SystemClock.elapsedRealtime()
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
    private fun maybeDownshift() {
        val curUrl = attempts.getOrNull(attemptIdx)?.first ?: return
        val target = when {
            !curUrl.contains("/hls/") -> attempts.indexOfFirst { it.first.contains("/main/") }
            curUrl.contains("/main/") -> attempts.indexOfFirst { it.first.contains("/compat/") }
            else -> -1
        }
        if (target <= attemptIdx) return            // déjà au palier le plus bas adapté
        rebufferTimes.clear()
        stallWindow.clear()
        networkStallStartMs = 0L
        val toCompat = attempts[target].first.contains("/compat/")
        val msg = if (toCompat) "Réseau limité : passage en 1080p compatible…"
                  else "Réseau instable : qualité adaptée au débit…"
        modeLabel = if (toCompat) "Compatibilité 1080p (réseau)" else "HDR · débit plafonné (réseau)"
        statusView.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Logger.log("downshift", "-> attempt $target (${attempts[target].first})")
        loadAttempt(target, restorePos = absolutePositionMs())
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
        // Durée de lecture CONTINUE minimale avant qu'un buffering puisse compter comme
        // un rebuffer réseau. Les reprises post-seek (tampon mince, transcodage qui
        // rattrape) ne durent que quelques secondes -> exclues. Un vrai rebuffer réseau
        // survient après ≥15 s de lecture (seuil ExoPlayer de reprise) -> bien au-dessus.
        private const val MIN_STABLE_PLAY_MS = 10_000L
        // Scope process-wide pour les nettoyages réseau qui doivent aboutir
        // même après destruction de l'activité (libération NVENC, dernière position).
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
