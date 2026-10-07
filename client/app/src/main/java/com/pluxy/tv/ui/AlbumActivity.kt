package com.pluxy.tv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.LibraryRequest
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un album ouvre ses pistes ou ses photos, sans encombrer la bibliothèque. */
class AlbumActivity : ThemedActivity() {
    private val api = PluxyApi.create()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var list: RecyclerView
    private lateinit var photoAdapter: LibraryAdapter
    private lateinit var trackAdapter: AlbumTrackAdapter
    private var loadJob: Job? = null
    private var albumKey = ""
    private var kind = ""
    private var collectionId = ""
    private var items: List<MediaItem> = emptyList()
    private var focusedId: String? = null
    private val focusRequests = RecyclerFocusRequester()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        collectionId = intent.getStringExtra(EXTRA_COLLECTION_ID).orEmpty()
        if (albumKey.isBlank() || kind !in setOf("audio", "photo")) { finish(); return }
        setContentView(R.layout.activity_album)
        focusedId = savedInstanceState?.getString(STATE_FOCUSED)
        findViewById<ImageButton>(R.id.albumBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.albumTitle).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<Button>(R.id.albumRetry).setOnClickListener { load() }
        list = findViewById(R.id.albumItems)
        if (kind == "audio") {
            trackAdapter = AlbumTrackAdapter(onClick = ::open, onFocused = { focusedId = it.id })
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = trackAdapter
        } else {
            val tv = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
            val compact = intent.getStringExtra(EXTRA_DENSITY) == "compact"
            val columns = (resources.configuration.screenWidthDp / if (compact) 125 else if (tv) 160 else 170).coerceAtLeast(2)
            val layout = GridLayoutManager(this, columns)
            photoAdapter = LibraryAdapter(
                environment = CardEnvironment(PluxyApplication.advancedMode(this), LanMediaImages.loader(this)),
                onMedia = ::open, onQuickPlay = ::open, onSeries = {}, onHeaderToggle = {},
                onFocused = { _, row -> focusedId = (row as? LibRow.MediaRow)?.item?.id },
            )
            list.layoutManager = layout
            photoAdapter.configureSpans(layout)
            list.adapter = photoAdapter
        }
        findViewById<Button>(R.id.albumPlay).apply {
            visibility = if (kind == "audio") View.VISIBLE else View.GONE
            setOnClickListener { items.firstOrNull { it.available }?.let(::open) }
        }
    }

    override fun onResume() { super.onResume(); load() }

    override fun onPause() {
        loadJob?.cancel()
        focusRequests.cancel()
        super.onPause()
    }

    override fun onDestroy() { loadJob?.cancel(); focusRequests.cancel(); api.close(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FOCUSED, focusedId)
        super.onSaveInstanceState(outState)
    }

    private fun load() {
        loadJob?.cancel()
        findViewById<ProgressBar>(R.id.albumLoading).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.albumMessage).visibility = View.GONE
        findViewById<Button>(R.id.albumRetry).visibility = View.GONE
        loadJob = lifecycleScope.launch {
            val album = try {
                val snapshot = CatalogueRepository.load(api.abs("/"), mediaKind = kind) {
                    val reader = PluxyApi(api.baseUrl)
                    try { reader.listItems(LibraryRequest(kind = kind)) } finally { reader.close() }
                }
                withContext(Dispatchers.Default) {
                    val available = if (collectionId.isBlank()) snapshot.items else {
                        LibraryPreferences.collections(this@AlbumActivity, api.baseUrl).firstOrNull { it.id == collectionId }
                            ?.let { TvLibraryContent.collectionItems(snapshot.items, it) }.orEmpty()
                    }
                    TvLibraryContent.albumRows(available, kind).firstOrNull { it.key == albumKey }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (items.isEmpty()) showError(getString(R.string.album_load_failed))
                else {
                    findViewById<ProgressBar>(R.id.albumLoading).visibility = View.GONE
                    Toast.makeText(this@AlbumActivity, R.string.album_refresh_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            findViewById<ProgressBar>(R.id.albumLoading).visibility = View.GONE
            if (album == null || album.items.isEmpty()) {
                items = emptyList()
                if (kind == "audio") trackAdapter.submitList(emptyList()) else photoAdapter.submitList(emptyList())
                showError(getString(R.string.album_empty)); return@launch
            }
            items = album.items
            findViewById<TextView>(R.id.albumTitle).text = album.name
            findViewById<TextView>(R.id.albumSubtitle).text = album.subtitle
            findViewById<Button>(R.id.albumPlay).isEnabled = items.any { it.available }
            if (kind == "audio") {
                val cover = findViewById<ImageView>(R.id.albumCover)
                cover.visibility = View.VISIBLE
                cover.setImageDrawable(BrandedArtworkDrawable(album.name, "MUSIQUE", null, resources.displayMetrics.scaledDensity))
                album.thumbnail?.let { relative ->
                    runCatching { LanUrlPolicy.resolveSameServer(PluxyApplication.serverBaseUrl(this@AlbumActivity), relative) }
                        .getOrNull()?.let { cover.load(it, LanMediaImages.loader(this@AlbumActivity)) }
                }
                trackAdapter.multipleDiscs = items.any { (it.discNumber ?: 1) > 1 }
                trackAdapter.submitList(items) { restoreTrackFocus() }
            } else {
                photoAdapter.submitList(items.map { LibRow.MediaRow(it) }) {
                    focusedId?.let { photoAdapter.restoreFocus(list, "media:$it") }
                }
            }
        }
    }

    private fun restoreTrackFocus() {
        val id = focusedId ?: return
        focusRequests.request(list, position = { trackAdapter.currentList.indexOfFirst { it.id == id } }) { it.requestFocus() }
    }

    private fun showError(message: String) {
        findViewById<ProgressBar>(R.id.albumLoading).visibility = View.GONE
        findViewById<TextView>(R.id.albumMessage).apply { text = message; visibility = View.VISIBLE }
        findViewById<Button>(R.id.albumRetry).visibility = View.VISIBLE
    }

    private fun open(item: MediaItem) {
        focusedId = item.id
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        startActivity(if (kind == "audio") Intent(this, AudioPlayerActivity::class.java)
            .putExtra(AudioPlayerActivity.EXTRA_ITEM, json)
            .putExtra(AudioPlayerActivity.EXTRA_COLLECTION_ID, collectionId)
        else Intent(this, PhotoActivity::class.java).putExtra(PhotoActivity.EXTRA_ITEM, json)
            .putExtra(PhotoActivity.EXTRA_ALBUM_KEY, albumKey)
            .putExtra(PhotoActivity.EXTRA_COLLECTION_ID, collectionId))
    }

    companion object {
        const val EXTRA_KEY = "album_key"
        const val EXTRA_KIND = "album_kind"
        const val EXTRA_TITLE = "album_title"
        const val EXTRA_DENSITY = "album_density"
        const val EXTRA_COLLECTION_ID = "album_collection_id"
        private const val STATE_FOCUSED = "album_focused"
    }
}

private class AlbumTrackAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onFocused: (MediaItem) -> Unit,
) : ListAdapter<MediaItem, AlbumTrackAdapter.Holder>(object : DiffUtil.ItemCallback<MediaItem>() {
    override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
}) {
    var multipleDiscs = false
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = stableLongId("track:${getItem(position).id}")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_album_track, parent, false),
    )
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.number.text = item.trackNumber?.takeIf { it > 0 }?.toString() ?: "—"
        holder.title.text = item.title
        holder.subtitle.text = listOfNotNull(
            if (multipleDiscs) "Disque ${item.discNumber?.takeIf { it > 0 } ?: 1}" else item.artist,
            if (!item.available) holder.itemView.context.getString(R.string.media_temporarily_unavailable) else null,
        ).joinToString(" · ")
        holder.subtitle.visibility = if (holder.subtitle.text.isBlank()) View.GONE else View.VISIBLE
        val seconds = item.duration.toInt().coerceAtLeast(0)
        holder.duration.text = if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else "—"
        holder.itemView.contentDescription = "${item.title}, ${holder.subtitle.text}, ${holder.duration.text}"
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnFocusChangeListener { _, focused -> if (focused) onFocused(item) }
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.trackNumber)
        val title: TextView = view.findViewById(R.id.trackTitle)
        val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
        val duration: TextView = view.findViewById(R.id.trackDuration)
    }
}
