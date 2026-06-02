package com.pluxy.tv.player

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.pluxy.tv.R
import com.pluxy.tv.api.MediaItem as PluxyItem
import com.pluxy.tv.api.PluxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private var player: ExoPlayer? = null
    private val api = PluxyApi.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.status)

        val itemJson = intent.getStringExtra(EXTRA_ITEM) ?: run { finish(); return }
        val item = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(PluxyItem::class.java).fromJson(itemJson) ?: run { finish(); return }

        startPlayback(item)
    }

    private fun startPlayback(item: PluxyItem) {
        statusView.text = "Analyse du flux…"
        lifecycleScope.launch {
            try {
                // 1. Le serveur décide Direct Play / Direct Stream / Transcode.
                val decision = api.decide(item.id)
                // 2. Récupère les réglages de buffer paramétrés dans l'UI serveur.
                val runtime = api.clientRuntime()

                val exo = PluxyPlayerFactory.build(this@PlayerActivity, runtime.buffer)
                player = exo
                playerView.player = exo

                val mediaItem = buildMediaItem(item, decision.streamUrl, decision.delivery)
                exo.setMediaItem(mediaItem)
                exo.addListener(playerListener(decision.mode))
                exo.playWhenReady = true
                exo.prepare()

                statusView.text = "Mode : ${decision.mode}" +
                    if (decision.toneMap) " · HDR→SDR" else ""
            } catch (e: Exception) {
                statusView.text = "Erreur : ${e.message}"
                Toast.makeText(this@PlayerActivity, "Connexion serveur impossible", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Construit le MediaItem + injecte les sous-titres externes (.srt) sans burn-in. */
    private fun buildMediaItem(item: PluxyItem, streamUrl: String, delivery: String): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(api.abs(streamUrl)))

        if (delivery == "hls") {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        // Sous-titres : un track par fichier sidecar, géré nativement par ExoPlayer.
        if (item.externalSubs.isNotEmpty()) {
            val subs = item.externalSubs.indices.map { idx ->
                MediaItem.SubtitleConfiguration.Builder(
                    Uri.parse(api.abs("/stream/subs/${item.id}/$idx"))
                )
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)   // .srt
                    .setLanguage("fr")
                    .setSelectionFlags(0)
                    .build()
            }
            builder.setSubtitleConfigurations(subs)
        }
        return builder.build()
    }

    private fun playerListener(mode: String) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            statusView.text = when (state) {
                Player.STATE_BUFFERING -> "Mise en mémoire tampon… ($mode)"
                Player.STATE_READY -> ""           // masqué pendant la lecture
                Player.STATE_ENDED -> "Lecture terminée"
                else -> statusView.text
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            statusView.text = "Erreur lecture : ${error.errorCodeName}"
        }
    }

    override fun onStop() {
        super.onStop()
        // Demande au serveur de tuer la session de transcodage pour libérer le GPU.
        intent.getStringExtra(EXTRA_ITEM)
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
    }
}
