package com.pluxy.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.CastMember
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.MovieMetadata
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.player.PlayerActivity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch

/** Fiche détaillée façon Plex : synopsis, casting, bande-annonce, lecture. */
@UnstableApi
class DetailsActivity : AppCompatActivity() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var item: MediaItem
    private val api = PluxyApi.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        val json = intent.getStringExtra(EXTRA_ITEM) ?: run { finish(); return }
        item = moshi.adapter(MediaItem::class.java).fromJson(json) ?: run { finish(); return }

        // Affichage immédiat (titre brut) puis enrichissement.
        findViewById<TextView>(R.id.title).text = item.title
        findViewById<RecyclerView>(R.id.cast).layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val playBtn = findViewById<Button>(R.id.play)
        playBtn.setOnClickListener { play() }
        playBtn.requestFocus()
        findViewById<Button>(R.id.trailer).visibility = View.GONE
        findViewById<Button>(R.id.playSettings).setOnClickListener { showPlaybackSettings() }

        loadMetadata()
    }

    /** Réglages de lecture EN AMONT : langue audio préférée + sous-titres. */
    private fun showPlaybackSettings() {
        val langs = listOf("fr" to "Français", "en" to "English", "es" to "Español",
            "de" to "Deutsch", "it" to "Italiano", "" to "Original (auto)")
        val current = PluxyApplication.audioLang(this)
        var pickedLang = current
        AlertDialog.Builder(this)
            .setTitle("Langue audio préférée")
            .setSingleChoiceItems(langs.map { it.second }.toTypedArray(),
                langs.indexOfFirst { it.first == current }.coerceAtLeast(0)) { _, w ->
                pickedLang = langs[w].first
            }
            .setPositiveButton("Suivant") { _, _ -> chooseSubsThen(pickedLang) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooseSubsThen(lang: String) {
        val modes = listOf("auto" to "Sous-titres : automatiques", "off" to "Sous-titres : désactivés")
        val current = PluxyApplication.subsMode(this)
        AlertDialog.Builder(this)
            .setTitle("Sous-titres")
            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(),
                modes.indexOfFirst { it.first == current }.coerceAtLeast(0)) { d, w ->
                PluxyApplication.setPlaybackPrefs(this, lang, modes[w].first)
                Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .show()
    }

    private fun loadMetadata() {
        val status = findViewById<TextView>(R.id.status)
        status.text = "Chargement des informations…"
        lifecycleScope.launch {
            val meta = try { api.metadata(item.id) } catch (e: Exception) { null }
            status.text = when {
                meta == null -> "Métadonnées indisponibles."
                !meta.matched -> "Film non identifié (titre déduit du fichier)."
                else -> ""
            }
            if (meta != null) bind(meta)
        }
    }

    private fun bind(m: MovieMetadata) {
        findViewById<TextView>(R.id.title).text = m.title
        findViewById<ImageView>(R.id.poster).apply {
            m.posterUrl?.let { load(it) { crossfade(true) } }
        }
        m.backdropUrl?.let { findViewById<ImageView>(R.id.backdrop).load(it) }

        val sub = listOfNotNull(
            m.year?.toString(),
            m.runtime?.let { "${it} min" },
            m.rating?.let { "★ $it" },
            m.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString("  ·  ")
        findViewById<TextView>(R.id.sub).text = sub

        findViewById<TextView>(R.id.tagline).apply {
            text = m.tagline ?: ""; visibility = if (m.tagline.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val hasOverview = !m.overview.isNullOrBlank()
        findViewById<TextView>(R.id.overview).text = m.overview ?: ""
        findViewById<TextView>(R.id.overview).visibility = if (hasOverview) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.overviewLabel).visibility = if (hasOverview) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.director).apply {
            text = m.director?.let { "Réalisé par $it" } ?: ""
            visibility = if (m.director.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        findViewById<RecyclerView>(R.id.cast).adapter = CastAdapter(m.cast)

        // Bande-annonce
        val trailerBtn = findViewById<Button>(R.id.trailer)
        if (m.trailerUrl != null) {
            trailerBtn.visibility = View.VISIBLE
            trailerBtn.setOnClickListener { openTrailer(m.trailerUrl) }
        } else {
            trailerBtn.visibility = View.GONE
        }
    }

    private fun play() {
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM, json))
    }

    private fun openTrailer(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Aucune application pour ouvrir la bande-annonce", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
    }
}

private class CastAdapter(private val cast: List<CastMember>) :
    RecyclerView.Adapter<CastAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val profile: ImageView = v.findViewById(R.id.profile)
        val actor: TextView = v.findViewById(R.id.actor)
        val character: TextView = v.findViewById(R.id.character)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false)
        return VH(v)
    }

    override fun getItemCount() = cast.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cast[position]
        h.actor.text = c.name
        h.character.text = c.character ?: ""
        if (c.profileUrl != null) h.profile.load(c.profileUrl) { crossfade(true) }
        else h.profile.setImageDrawable(null)   // garde le fond gris #161a23
    }
}
