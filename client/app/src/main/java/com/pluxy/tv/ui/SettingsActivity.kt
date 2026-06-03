package com.pluxy.tv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.ServerConfig
import kotlinx.coroutines.launch

/**
 * Réglages avancés pilotables DIRECTEMENT depuis la TV (modifient le serveur via
 * PATCH /api/settings) : forcer le HDR, format/canaux audio, plafond de débit, qualité
 * d'encodage, lecture directe, buffer… Construit par programmation (pas de XML) pour
 * rester simple et navigable à la télécommande (chaque ligne est un bouton focusable).
 */
class SettingsActivity : AppCompatActivity() {

    private val api = PluxyApi.create()
    private lateinit var rows: LinearLayout
    private lateinit var statusView: TextView
    private var cfg: ServerConfig? = null
    private var busy = false

    private val accent = 0xFFE5A00D.toInt()
    private val bg = 0xFF0D0F14.toInt()
    private val card = 0xFF1A2030.toInt()
    private val sub = 0xFF8A93A6.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            setPadding(dp(28), dp(24), dp(28), dp(28))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        setContentView(scroll)

        title("⚙  Réglages avancés")
        statusView = TextView(this).apply {
            setTextColor(sub); textSize = 13f
            setPadding(0, 0, 0, dp(12))
            text = "Chargement…"
        }
        rows.addView(statusView)

        if (!PluxyApplication.isConfigured(this)) {
            statusView.text = "Aucun serveur configuré."
            return
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                cfg = api.getSettings()
                render()
            } catch (e: Exception) {
                statusView.text = "Serveur injoignable : ${e.message}"
            }
        }
    }

    private fun render() {
        val c = cfg ?: return
        // On retire tout sauf le titre (index 0) et le status (index 1).
        while (rows.childCount > 2) rows.removeViewAt(2)
        statusView.text = "Serveur : " +
            PluxyApplication.serverBaseUrl(this).removePrefix("http://")

        section("Image / HDR")
        enumRow("HDR", "transcoding", "hdr_tone_mapping", c.transcoding.hdrToneMapping,
            listOf(
                "Forcer le HDR (recommandé)" to "never",
                "Auto (selon l'écran détecté)" to "auto",
                "Toujours convertir en SDR" to "always",
            ),
            hint = "« Forcer » garantit que les films HDR restent en HDR sur la TV.")
        boolRow("Lecture directe (qualité d'origine)", "transcoding", "prefer_direct_play",
            c.transcoding.preferDirectPlay,
            hint = "Envoie le fichier brut quand la TV sait le lire (HDR/4K intacts, aucun bridage).")
        enumRow("Plafond de débit", "transcoding", "max_bitrate_mbps",
            c.transcoding.maxBitrateMbps.toString(),
            listOf("20 Mbps" to "20", "30 Mbps" to "30", "50 Mbps" to "50",
                "80 Mbps" to "80", "120 Mbps" to "120"),
            hint = "Appliqué seulement si la lecture directe est désactivée.")
        boolRow("Forcer le transcodage", "transcoding", "force_transcode",
            c.transcoding.forceTranscode,
            hint = "Re-encode toujours (utile si la TV peine sur certains fichiers).")
        boolRow("Accélération matérielle (NVENC)", "transcoding", "hardware_acceleration",
            c.transcoding.hardwareAcceleration,
            hint = "Transcodage par le GPU NVIDIA. À laisser activé.")
        enumRow("Qualité d'encodage", "transcoding", "preset", c.transcoding.preset,
            listOf("p1 (le plus rapide)" to "p1", "p3 (rapide)" to "p3",
                "p5 (équilibré)" to "p5", "p7 (qualité max)" to "p7"),
            hint = "Plus haut = meilleure image mais GPU plus sollicité.")

        section("Audio")
        enumRow("Codec audio de repli", "audio", "target_codec", c.audio.targetCodec,
            listOf("AC3 (universel ampli/TV)" to "ac3", "E-AC3 / Dolby Digital+" to "eac3",
                "AAC (toujours audible)" to "aac"),
            hint = "Utilisé quand l'audio d'origine n'est pas géré par l'appareil.")
        enumRow("Canaux audio", "audio", "target_channels", c.audio.targetChannels.toString(),
            listOf("Stéréo (2.0)" to "2", "5.1 (6 canaux)" to "6", "7.1 (8 canaux)" to "8"))
        enumRow("Débit audio", "audio", "bitrate_kbps", c.audio.bitrateKbps.toString(),
            listOf("384 kbps" to "384", "448 kbps" to "448", "640 kbps" to "640"))

        section("Réseau / lecture")
        boolRow("Lecture directe autorisée", "network", "direct_play_enabled",
            c.network.directPlayEnabled)
        boolRow("Remux autorisé (Direct Stream)", "network", "direct_stream_enabled",
            c.network.directStreamEnabled)
        enumRow("Durée des segments HLS", "network", "hls_segment_duration",
            c.network.hlsSegmentDuration.toString(),
            listOf("2 s (réactif)" to "2", "3 s (équilibré)" to "3",
                "4 s" to "4", "6 s (stable)" to "6"),
            hint = "Segments plus courts = seek plus réactif ; plus longs = plus stable.")
        enumRow("Mémoire tampon max", "client_buffer", "max_buffer_ms",
            c.clientBuffer.maxBufferMs.toString(),
            listOf("60 s" to "60000", "120 s (défaut)" to "120000",
                "180 s" to "180000", "240 s (Wi-Fi instable)" to "240000"),
            hint = "Plus de tampon = moins de coupures sur Wi-Fi faible (relancer la lecture).")

        // Bouton de fermeture en bas.
        val close = Button(this).apply {
            text = "Fermer"
            setTextColor(Color.WHITE)
            setBackgroundColor(card)
            setOnClickListener { finish() }
        }
        rows.addView(close, lp().also { it.topMargin = dp(20) })
    }

    // ---- Lignes ---------------------------------------------------------- //
    private fun title(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text
            setTextColor(accent); textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun section(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(accent); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun rowButton(label: String, value: String, hint: String?): Button {
        val b = Button(this)
        b.text = buildString {
            append(label); append("\n")
            append("▸ ").append(value)
            if (!hint.isNullOrBlank()) { append("\n"); append(hint) }
        }
        b.isAllCaps = false
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(card)
        b.setPadding(dp(18), dp(14), dp(18), dp(14))
        rows.addView(b, lp().also { it.topMargin = dp(8) })
        return b
    }

    private fun boolRow(label: String, section: String, key: String, value: Boolean,
                        hint: String? = null) {
        val b = rowButton(label, if (value) "Activé" else "Désactivé", hint)
        b.setOnClickListener {
            AlertDialog.Builder(this).setTitle(label)
                .setItems(arrayOf("Activé", "Désactivé")) { _, w ->
                    patch(section, key, (w == 0))
                }.show()
        }
    }

    private fun enumRow(label: String, section: String, key: String, current: String,
                        options: List<Pair<String, String>>, hint: String? = null) {
        val currentLabel = options.firstOrNull { it.second == current }?.first ?: current
        val b = rowButton(label, currentLabel, hint)
        b.setOnClickListener {
            val labels = options.map { (if (it.second == current) "✓ " else "   ") + it.first }
            AlertDialog.Builder(this).setTitle(label)
                .setItems(labels.toTypedArray()) { _, w ->
                    val v = options[w].second
                    // Valeur numérique -> envoyée comme nombre ; sinon comme chaîne.
                    val asInt = v.toIntOrNull()
                    if (asInt != null) patch(section, key, asInt) else patch(section, key, v)
                }.show()
        }
    }

    // ---- PATCH ----------------------------------------------------------- //
    private fun patch(section: String, key: String, value: Any) {
        if (busy) return
        busy = true
        val v = when (value) {
            is String -> "\"${value.replace("\"", "")}\""
            is Boolean -> value.toString()
            else -> value.toString()
        }
        val json = """{"$section":{"$key":$v}}"""
        lifecycleScope.launch {
            try {
                cfg = api.patchSettings(json)
                render()
                Toast.makeText(this@SettingsActivity, "Enregistré ✓", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity,
                    "Échec : ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
