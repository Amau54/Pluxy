package com.pluxy.tv.ui

import android.content.Intent
import android.content.res.Configuration
import android.animation.AnimatorInflater
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.LibraryRequest
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.RatingItem
import com.pluxy.tv.player.PlayerActivity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran série pensé pour la télécommande : saisons explicites, état de chargement/erreur,
 * restauration du focus par identifiant et cartes d'épisodes illustrées en 16:9.
 */
@OptIn(UnstableApi::class)
class SeriesActivity : ThemedActivity() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val api = PluxyApi.create()
    private lateinit var name: String
    private var key: String? = null
    private lateinit var list: RecyclerView
    private lateinit var adapter: EpisodeAdapter
    private lateinit var seasons: LinearLayout
    private lateinit var continueButton: Button
    private var allEpisodes: List<MediaItem> = emptyList()
    private var selectedSeason: Int? = null
    private var unnumberedSeasonSelected = false
    private var focusedEpisodeId: String? = null
    private var loadJob: Job? = null
    private var localBackdropUrl: String? = null
    private var focusedControlTag: String? = null
    private var episodeListVersion = 0L
    private val focusRequests = RecyclerFocusRequester()
    private val compactHeight: Boolean
        get() = resources.configuration.screenHeightDp in 1..600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        name = intent.getStringExtra(EXTRA_SERIES) ?: run {
            finish()
            return
        }
        key = intent.getStringExtra(EXTRA_KEY)?.trim()?.ifBlank { null }
        selectedSeason = savedInstanceState?.takeIf { it.containsKey(STATE_SEASON) }?.getInt(STATE_SEASON)
        unnumberedSeasonSelected = savedInstanceState?.getBoolean(STATE_UNNUMBERED) ?: false
        focusedEpisodeId = savedInstanceState?.getString(STATE_EPISODE)
        focusedControlTag = savedInstanceState?.getString(STATE_FOCUS)

        list = findViewById(R.id.episodes)
        seasons = findViewById(R.id.seasons)
        continueButton = findViewById(R.id.continueEpisode)
        continueButton.tag = "continue"
        continueButton.setOnFocusChangeListener { _, focused ->
            if (focused) focusedControlTag = "continue"
        }
        adapter = EpisodeAdapter(
            onClick =(::play),
            onFocused = {
                focusedEpisodeId = it.id
                focusedControlTag = "episode:${it.id}"
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<TextView>(R.id.seriesTitle).apply {
            text = displayName(name)
            contentDescription = text
        }
        findViewById<Button>(R.id.seriesRetry).setOnClickListener { load() }
        applyCompactHeightLayout()
        configurePhoneHeader()
    }

    override fun onResume() {
        super.onResume()
        // Un seul chargement initial, puis un rafraîchissement au retour du lecteur.
        load()
    }

    override fun onPause() {
        focusedEpisodeId = adapter.itemIdForView(list, currentFocus) ?: focusedEpisodeId
        episodeListVersion++
        focusRequests.cancel()
        loadJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        focusRequests.cancel()
        loadJob?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedSeason?.let { outState.putInt(STATE_SEASON, it) }
        outState.putBoolean(STATE_UNNUMBERED, unnumberedSeasonSelected)
        outState.putString(STATE_EPISODE, focusedEpisodeId)
        outState.putString(STATE_FOCUS, currentFocus?.tag as? String ?: focusedControlTag)
        super.onSaveInstanceState(outState)
    }

    private fun load() {
        loadJob?.cancel()
        showLoading(allEpisodes.isEmpty())
        loadJob = lifecycleScope.launch {
            val eps = try {
                val snapshot = CatalogueRepository.load(api.abs("/"), forceRefresh = allEpisodes.isNotEmpty(), mediaKind = "series") {
                    val reader = PluxyApi(api.baseUrl)
                    try { reader.listItems(LibraryRequest(kind = "series")) } finally { reader.close() }
                }
                withContext(Dispatchers.Default) { TvLibraryContent.seriesEpisodes(snapshot.items, key, name) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (allEpisodes.isEmpty()) {
                    showError(
                        "Impossible de charger les épisodes.\n" +
                            "Vérifiez le serveur puis réessayez.",
                    )
                } else {
                    showContent()
                    Toast.makeText(
                        this@SeriesActivity,
                        "Actualisation impossible · contenu conservé",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            if (eps.isEmpty()) {
                clearLibraryState()
                showError("Aucun épisode n'est disponible pour cette série.")
                return@launch
            }

            allEpisodes = eps
            val next = HomeOrganizer.resumeEpisode(eps) ?: return@launch
            val numbered = eps.mapNotNull { it.season }.distinct()
            val hasUnnumbered = eps.any { it.season == null }
            val selectionStillExists =
                (unnumberedSeasonSelected && hasUnnumbered) ||
                    (!unnumberedSeasonSelected && selectedSeason != null &&
                        selectedSeason in numbered)
            if (!selectionStillExists) {
                selectedSeason = next.season
                unnumberedSeasonSelected = next.season == null
            }
            val restoreControl = focusedControlTag
            bindLibraryState(eps, next)
            bindLocalBackdrop(eps)
            renderSeasonButtons()
            renderEpisodes(restoreControl)
            showContent()
            loadMetadata(eps.first())
        }
    }

    private fun bindLibraryState(eps: List<MediaItem>, next: MediaItem) {
        val watched = eps.count { it.watched }
        val seasonCount = eps.mapNotNull { it.season }.distinct().size
        findViewById<TextView>(R.id.seriesSub).text = buildString {
            append(eps.size)
            append(if (eps.size > 1) " épisodes" else " épisode")
            if (seasonCount > 0) {
                append(" · ")
                append(seasonCount)
                append(if (seasonCount > 1) " saisons" else " saison")
            }
            append(" · ")
            append(watched)
            append(if (watched > 1) " vus" else " vu")
        }
        continueButton.apply {
            visibility = View.VISIBLE
            text = when {
                next.inProgress && !next.watched ->
                    "Reprendre ${episodeCode(next)} · ${formatResume(next)}"
                eps.all { it.watched } -> "Relire ${episodeCode(next)}"
                else -> "Lire ${episodeCode(next)}"
            }
            contentDescription = "$text, ${cleanEpisodeTitle(next, eps.indexOf(next))}"
            setOnClickListener { play(next) }
        }
    }

    private fun bindLocalBackdrop(eps: List<MediaItem>) {
        val relative = eps.firstNotNullOfOrNull { it.thumbnailUrl } ?: return
        localBackdropUrl = runCatching {
            LanUrlPolicy.resolveSameServer(PluxyApplication.serverBaseUrl(this), relative)
        }.getOrNull() ?: return
        loadLocalBackdrop()
    }

    private fun loadLocalBackdrop() {
        val url = localBackdropUrl ?: return
        findViewById<ImageView>(R.id.backdrop).load(url, LanMediaImages.loader(this))
    }

    private suspend fun loadMetadata(seed: MediaItem) {
        val metadata = try {
            api.metadata(seed.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val overview = findViewById<TextView>(R.id.seriesOverview)
        if (metadata == null) {
            overview.visibility = View.GONE
            findViewById<LinearLayout>(R.id.seriesRatings).visibility = View.GONE
            return
        }
        LanUrlPolicy.safeTmdbImage(metadata.backdropUrl)?.let {
            findViewById<ImageView>(R.id.backdrop).load(it) {
                listener(onError = { _, _ -> loadLocalBackdrop() })
            }
        }
        overview.text = metadata.overview.orEmpty()
        overview.visibility =
            if (compactHeight || metadata.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        if (metadata.matched) findViewById<TextView>(R.id.seriesTitle).apply {
            text = metadata.title
            contentDescription = metadata.title
        }
        if (compactHeight) {
            findViewById<LinearLayout>(R.id.seriesRatings).visibility = View.GONE
        } else {
            buildRatings(metadata.ratings)
        }
    }

    private fun renderSeasonButtons() {
        seasons.removeAllViews()
        val available = allEpisodes.mapNotNull { it.season }.distinct().sorted()
        val hasUnnumbered = allEpisodes.any { it.season == null }
        val groupCount = available.size + if (hasUnnumbered) 1 else 0
        findViewById<View>(R.id.seasonScroller).visibility =
            if (groupCount > 1) View.VISIBLE else View.GONE
        if (groupCount <= 1) return
        for (season in available) {
            addSeasonButton(getString(R.string.season_number, season), season, false)
        }
        if (hasUnnumbered) {
            addSeasonButton(getString(R.string.season_other), null, true)
        }
    }

    private fun addSeasonButton(label: String, season: Int?, unnumbered: Boolean) {
        seasons.addView(Button(this).apply {
            tag = if (unnumbered) "season:other" else "season:$season"
            text = label
            isAllCaps = false
            isSelected = if (unnumbered) {
                unnumberedSeasonSelected
            } else {
                !unnumberedSeasonSelected && selectedSeason == season
            }
            setTextColor(getColorStateList(R.color.tab_text))
            setBackgroundResource(R.drawable.bg_tab)
            stateListAnimator = AnimatorInflater.loadStateListAnimator(
                this@SeriesActivity,
                R.animator.focus_lift_btn,
            )
            minimumWidth = 0
            minHeight = dp(48)
            textSize = resources.getDimension(R.dimen.auxiliary_meta_size) / resources.displayMetrics.scaledDensity
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnFocusChangeListener { _, focused ->
                if (focused) focusedControlTag = tag as String
            }
            setOnClickListener {
                selectedSeason = season
                unnumberedSeasonSelected = unnumbered
                renderSeasonButtons()
                focusedEpisodeId = null
                focusedControlTag = null
                renderEpisodes(restoreControl = null)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(10) })
    }

    private fun renderEpisodes(restoreControl: String?) {
        focusRequests.cancel()
        val version = ++episodeListVersion
        val filtered = episodesForSeason(
            allEpisodes,
            selectedSeason,
            unnumberedSeasonSelected,
        )
        val focusId = focusedEpisodeId
            ?.takeIf { id -> filtered.any { it.id == id } }
            ?: HomeOrganizer.resumeEpisode(filtered)?.id
        adapter.submitList(filtered) {
            if (version != episodeListVersion || isFinishing || isDestroyed) return@submitList
            val control = when {
                restoreControl == "continue" -> continueButton
                restoreControl?.startsWith("season:") == true -> seasons.findViewWithTag<View>(restoreControl)
                else -> null
            }
            if (control?.visibility == View.VISIBLE && control.requestFocus()) return@submitList
            focusId?.let { focusEpisode(it, version) }
        }
    }

    private fun focusEpisode(itemId: String, version: Long) {
        focusRequests.request(list, position = { adapter.positionOf(itemId) },
            isCurrent = { version == episodeListVersion && !isFinishing && !isDestroyed }) {
            it.requestFocus()
        }
    }

    private fun showLoading(blocking: Boolean) {
        findViewById<ProgressBar>(R.id.seriesLoading).visibility =
            if (blocking) View.VISIBLE else View.GONE
        if (blocking) {
            findViewById<TextView>(R.id.seriesMessage).visibility = View.GONE
            findViewById<Button>(R.id.seriesRetry).visibility = View.GONE
            list.visibility = View.INVISIBLE
        }
    }

    private fun showError(message: String) {
        episodeListVersion++
        focusRequests.cancel()
        findViewById<ProgressBar>(R.id.seriesLoading).visibility = View.GONE
        findViewById<TextView>(R.id.seriesMessage).apply {
            text = message
            visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.seriesRetry).apply {
            visibility = View.VISIBLE
            requestFocus()
        }
        list.visibility = View.INVISIBLE
    }

    private fun showContent() {
        findViewById<ProgressBar>(R.id.seriesLoading).visibility = View.GONE
        findViewById<TextView>(R.id.seriesMessage).visibility = View.GONE
        findViewById<Button>(R.id.seriesRetry).visibility = View.GONE
        list.visibility = View.VISIBLE
    }

    private fun clearLibraryState() {
        allEpisodes = emptyList()
        selectedSeason = null
        unnumberedSeasonSelected = false
        focusedEpisodeId = null
        focusedControlTag = null
        adapter.submitList(emptyList())
        continueButton.setOnClickListener(null)
        continueButton.visibility = View.GONE
        seasons.removeAllViews()
        findViewById<View>(R.id.seasonScroller).visibility = View.GONE
        findViewById<TextView>(R.id.seriesSub).text = ""
        findViewById<LinearLayout>(R.id.seriesRatings).apply {
            removeAllViews()
            visibility = View.GONE
        }
        findViewById<TextView>(R.id.seriesOverview).apply {
            text = ""
            visibility = View.GONE
        }
        localBackdropUrl = null
        findViewById<ImageView>(R.id.backdrop).apply {
            dispose()
            setImageDrawable(null)
        }
    }

    private fun buildRatings(ratings: List<RatingItem>) {
        val row = findViewById<LinearLayout>(R.id.seriesRatings)
        row.removeAllViews()
        if (ratings.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        for (rating in ratings) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
            }
            cell.addView(TextView(this).apply {
                text = rating.value
                setTextColor(pluxyColor(R.color.accent_bright))
                textSize = resources.getDimension(R.dimen.auxiliary_meta_size) / resources.displayMetrics.scaledDensity
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            })
            cell.addView(TextView(this).apply {
                text = " · " + rating.source
                setTextColor(pluxyColor(R.color.text_secondary))
                textSize = resources.getDimension(R.dimen.auxiliary_meta_size) / resources.displayMetrics.scaledDensity
                gravity = Gravity.CENTER
            })
            row.addView(cell)
        }
    }

    private fun play(item: MediaItem) {
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ITEM, json)
                .putExtra(PlayerActivity.EXTRA_AUTO_RESUME, item.inProgress),
        )
    }

    private fun applyCompactHeightLayout() {
        if (!compactHeight) return
        findViewById<View>(R.id.seriesRatings).visibility = View.GONE
        findViewById<View>(R.id.seriesOverview).visibility = View.GONE
        findViewById<View>(R.id.seriesContent).setPadding(
            resources.getDimensionPixelSize(R.dimen.screen_pad),
            dp(18),
            resources.getDimensionPixelSize(R.dimen.screen_pad),
            dp(12),
        )
        list.setPadding(0, 0, 0, dp(8))
    }

    private fun configurePhoneHeader() {
        val television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        if (television || resources.configuration.smallestScreenWidthDp >= 600) return
        findViewById<TextView>(R.id.seriesTitle).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = text
        }
        continueButton.layoutParams = continueButton.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        val content = findViewById<View>(R.id.seriesContent)
        val header = findViewById<View>(R.id.seriesHeaderContent)
        val scroll = findViewById<ScrollView>(R.id.seriesHeaderScroll)
        // Le synopsis et les grands caractères restent accessibles sans faire
        // disparaître les saisons et la liste d'épisodes sous l'écran.
        val resizeHeader = {
            if (content.height > 0 && header.height > 0) {
                val limit = (content.height * 0.40f).toInt().coerceAtLeast(dp(80))
                val height = minOf(header.height, limit)
                if (scroll.layoutParams.height != height) {
                    scroll.layoutParams = scroll.layoutParams.apply { this.height = height }
                }
            }
        }
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeHeader() }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeHeader() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_SEASON = "series_season"
        private const val STATE_UNNUMBERED = "series_unnumbered"
        private const val STATE_EPISODE = "series_episode"
        private const val STATE_FOCUS = "series_focus"
        const val EXTRA_SERIES = "extra_series_name"
        const val EXTRA_KEY = "extra_series_key"

        fun displayName(value: String): String =
            value.split(" ").joinToString(" ") { word ->
                if (word.isNotEmpty()) word.replaceFirstChar { it.uppercase() } else word
            }
    }
}

private class EpisodeAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onFocused: (MediaItem) -> Unit,
) : ListAdapter<MediaItem, EpisodeAdapter.VH>(DIFF) {

    init {
        setHasStableIds(true)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.epThumbnail)
        val num: TextView = view.findViewById(R.id.epNum)
        val title: TextView = view.findViewById(R.id.epTitle)
        val meta: TextView = view.findViewById(R.id.epMeta)
        val resume: ProgressBar = view.findViewById(R.id.epResume)
        val watched: TextView = view.findViewById(R.id.epWatched)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun getItemId(position: Int): Long =
        stableLongId("episode:${getItem(position).id}")

    override fun onBindViewHolder(holder: VH, position: Int) {
        val episode = getItem(position)
        holder.itemView.tag = "episode:${episode.id}"
        val title = cleanEpisodeTitle(episode, position)
        holder.num.text = episodeCode(episode)
        holder.title.text = title
        holder.meta.text = episodeMeta(episode)
        holder.watched.visibility = if (episode.watched) View.VISIBLE else View.GONE
        holder.resume.visibility =
            if (episode.inProgress && episode.progress > 0.02) View.VISIBLE else View.GONE
        holder.resume.progress = (episode.progress * 100).toInt().coerceIn(1, 100)
        holder.itemView.alpha = if (episode.watched) 0.78f else 1f
        holder.itemView.contentDescription =
            "${episodeCode(episode)}, $title, ${holder.meta.text}"
        holder.itemView.setOnClickListener { onClick(episode) }
        holder.itemView.setOnFocusChangeListener { _, focused ->
            if (focused) onFocused(episode)
        }

        val relative = episode.thumbnailUrl
        val context = holder.itemView.context
        val url = relative?.let {
            runCatching {
                LanUrlPolicy.resolveSameServer(PluxyApplication.serverBaseUrl(context), it)
            }.getOrNull()
        }
        holder.thumbnail.contentDescription = null
        if (url == null) {
            holder.thumbnail.dispose()
            holder.thumbnail.setImageResource(R.drawable.ic_episode_placeholder)
        } else {
            holder.thumbnail.load(url, LanMediaImages.loader(context)) {
                placeholder(R.drawable.ic_episode_placeholder)
                error(R.drawable.ic_episode_placeholder)
                crossfade(true)
            }
        }
    }

    fun positionOf(itemId: String): Int = currentList.indexOfFirst { it.id == itemId }

    fun itemIdForView(list: RecyclerView, view: View?): String? {
        if (view == null) return null
        val holder = list.findContainingViewHolder(view) ?: return null
        val position = holder.bindingAdapterPosition
        return currentList.getOrNull(position)?.id
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem == newItem
        }
    }
}

internal fun episodeCode(item: MediaItem): String =
    if (item.season != null && item.episode != null) {
        "S%02dE%02d".format(item.season, item.episode)
    } else {
        "Épisode ${item.episode ?: 1}"
    }

internal fun cleanEpisodeTitle(item: MediaItem, fallbackPosition: Int): String {
    val marker = Regex("(?i)s\\d{1,2}[ ._-]?e\\d{1,2}|\\b\\d{1,2}x\\d{2}\\b").find(item.title)
    var title = if (marker != null) item.title.substring(marker.range.last + 1) else item.title
    title = title.replace(Regex("[._]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '_', '.')
    return title.ifBlank { "Épisode ${item.episode ?: fallbackPosition + 1}" }
}

private fun episodeMeta(item: MediaItem): String {
    val values = mutableListOf<String>()
    val minutes = (item.duration / 60).toInt()
    if (minutes > 0) values += "$minutes min"
    when {
        item.watched -> values += "Vu"
        item.inProgress && item.progress > 0.02 ->
            values += "${(item.progress * 100).toInt().coerceIn(1, 99)} % regardé"
        else -> values += "Non regardé"
    }
    item.height?.let {
        values += when {
            it >= 2000 -> "4K"
            it >= 1060 -> "1080p"
            it >= 700 -> "720p"
            else -> "${it}p"
        }
    }
    return values.joinToString(" · ")
}

private fun formatResume(item: MediaItem): String {
    val seconds = (item.duration * item.progress).toLong().coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%02d:%02d".format(minutes, secs)
}

internal fun episodesForSeason(
    episodes: List<MediaItem>,
    selectedSeason: Int?,
    unnumberedSelected: Boolean,
): List<MediaItem> = when {
    unnumberedSelected -> episodes.filter { it.season == null }
    selectedSeason != null -> episodes.filter { it.season == selectedSeason }
    else -> episodes
}
