package com.pluxy.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pluxy.tv.R
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.player.PlayerActivity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch

/** Écran d'accueil Android TV : grille des films indexés par le serveur. */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private val api = PluxyApi.create()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val grid = findViewById<RecyclerView>(R.id.grid)
        val empty = findViewById<TextView>(R.id.empty)
        grid.layoutManager = GridLayoutManager(this, 4)

        lifecycleScope.launch {
            try {
                val items = api.listItems()
                if (items.isEmpty()) {
                    empty.text = "Aucun média. Scannez votre bibliothèque depuis l'UI serveur."
                    empty.visibility = View.VISIBLE
                } else {
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
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM, json))
    }
}

private class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val media = items[position]
        h.title.text = media.title
        val res = media.height?.let { px -> "${px}p" } ?: ""
        val hdr = if (media.isHdr) " · HDR" else ""
        h.meta.text = "${media.container.uppercase()} · ${media.videoCodec ?: "?"}$hdr $res".trim()
        h.itemView.setOnClickListener { onClick(media) }
        h.itemView.isFocusable = true
    }
}
