package com.pluxy.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch

/** Écran d'accueil Android TV : grille des films (affiches TMDB). */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView

    private val setupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loadLibrary() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        // Colonnes adaptatives : ~1 affiche / 150dp de large (mobile portrait ≈ 2-3, TV ≈ 6+).
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = (dp / 150).toInt().coerceIn(2, 8)
        grid.layoutManager = GridLayoutManager(this, cols)

        findViewById<Button>(R.id.changeServer).setOnClickListener { openSetup() }

        if (!PluxyApplication.isConfigured(this)) {
            openSetup()                         // 1er lancement : découverte serveur
        } else {
            loadLibrary()
        }
    }

    private fun openSetup() {
        setupLauncher.launch(Intent(this, ServerSetupActivity::class.java))
    }

    private fun loadLibrary() {
        if (!PluxyApplication.isConfigured(this)) return
        findViewById<TextView>(R.id.serverLabel).text = PluxyApplication.serverName(this)
        val api = PluxyApi.create()
        lifecycleScope.launch {
            try {
                val items = api.listItems()
                if (items.isEmpty()) {
                    empty.text = "Aucun média. Scannez votre bibliothèque depuis l'UI serveur."
                    empty.visibility = View.VISIBLE
                    grid.adapter = null
                } else {
                    empty.visibility = View.GONE
                    grid.adapter = MediaAdapter(items) { open(it) }
                }
            } catch (e: Exception) {
                empty.text = "Serveur injoignable : ${e.message}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun open(item: MediaItem) {
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        startActivity(Intent(this, DetailsActivity::class.java)
            .putExtra(DetailsActivity.EXTRA_ITEM, json))
    }
}

private class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val hdr: TextView = v.findViewById(R.id.hdrBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val media = items[position]
        h.title.text = media.title
        val res = media.height?.let { "${it}p" } ?: ""
        h.meta.text = listOfNotNull(media.year?.toString(), media.videoCodec?.uppercase(), res)
            .joinToString(" · ")
        h.hdr.visibility = if (media.isHdr) View.VISIBLE else View.GONE
        if (media.posterUrl != null) {
            h.poster.load(media.posterUrl) { crossfade(true) }
        } else {
            h.poster.setImageResource(R.drawable.ic_banner)
        }
        h.itemView.setOnClickListener { onClick(media) }
    }
}
