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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Écran d'accueil Android TV / mobile : grille des films (affiches TMDB). */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var swipe: SwipeRefreshLayout

    private var loadJob: Job? = null
    private var loadedOnce = false

    private val setupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loadedOnce = false; loadItems() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        swipe = findViewById(R.id.swipe)

        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        grid.layoutManager = GridLayoutManager(this, (dp / 150).toInt().coerceIn(2, 8))

        findViewById<Button>(R.id.changeServer).setOnClickListener { openSetup() }
        findViewById<Button>(R.id.reload).setOnClickListener { refresh() }
        swipe.setOnRefreshListener { refresh() }
        swipe.setColorSchemeColors(0xFFE5A00D.toInt())

        if (!PluxyApplication.isConfigured(this)) openSetup()
    }

    override fun onResume() {
        super.onResume()
        // Charge UNE fois (préserve le scroll au retour ; rafraîchir = bouton/swipe).
        if (PluxyApplication.isConfigured(this) && !loadedOnce) loadItems()
    }

    private fun openSetup() = setupLauncher.launch(Intent(this, ServerSetupActivity::class.java))

    /** Relance un scan serveur puis recharge en pollant le statut (pas de delay fixe). */
    private fun refresh() {
        if (!PluxyApplication.isConfigured(this)) { openSetup(); return }
        swipe.isRefreshing = true
        loadJob?.cancel()
        val api = PluxyApi.create()
        loadJob = lifecycleScope.launch {
            runCatching { api.rescan() }
            repeat(10) {                       // poll jusqu'à 10 s max
                fetchAndShow(api)
                if (!runCatching { api.scanStatus().scanning }.getOrDefault(false)) return@repeat
                delay(1000)
            }
            swipe.isRefreshing = false
        }
    }

    private fun loadItems() {
        if (!PluxyApplication.isConfigured(this)) return
        findViewById<TextView>(R.id.serverLabel).text = PluxyApplication.serverName(this)
        loadJob?.cancel()
        val api = PluxyApi.create()
        loadJob = lifecycleScope.launch { fetchAndShow(api); loadedOnce = true }
    }

    private suspend fun fetchAndShow(api: PluxyApi) {
        try {
            val items = groupSimilar(api.listItems())
            if (items.isEmpty()) {
                empty.text = "Aucun média. Ajoutez des dossiers et scannez depuis l'UI serveur."
                empty.visibility = View.VISIBLE
                grid.adapter = null
            } else {
                empty.visibility = View.GONE
                grid.adapter = MediaAdapter(items) { open(it) }
            }
        } catch (e: Exception) {
            if (grid.adapter == null) {
                empty.text = "Serveur injoignable : ${e.message}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    /** Trie pour regrouper les titres similaires (sagas, multi-parties) côte à côte. */
    private fun groupSimilar(items: List<MediaItem>): List<MediaItem> =
        items.sortedWith(compareBy({ normKey(it.title) }, { it.year ?: 0 }))

    private fun normKey(title: String): String =
        title.lowercase()
            .replace(Regex("^(le |la |les |l'|the |a |an |un |une |des )"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()

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
        if (media.posterUrl != null) h.poster.load(media.posterUrl) { crossfade(true) }
        else h.poster.setImageResource(R.drawable.ic_banner)
        h.itemView.setOnClickListener { onClick(media) }
    }
}
