package com.pluxy.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.SystemClock
import android.util.TypedValue
import android.graphics.Paint
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.dispose
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.MediaItem
import kotlin.math.abs
import kotlin.math.ceil

internal data class CardEnvironment(
    val advancedMode: Boolean,
    val lanImages: ImageLoader,
    val photoFolderInHeader: Boolean = false,
    val compact: Boolean = true,
)

internal class LibraryAdapter(
    environment: CardEnvironment,
    private val onMedia: (MediaItem) -> Unit,
    private val onQuickPlay: (MediaItem) -> Unit,
    private val onSeries: (LibRow.SeriesRow) -> Unit,
    private val onHeaderToggle: (LibRow.Header) -> Unit,
    private val onFocused: (LibraryFocusTarget, LibRow) -> Unit,
    private val onAlbum: (LibRow.AlbumRow) -> Unit = {},
    private val onCollection: (LibRow.CollectionRow) -> Unit = {},
    private val onLongPress: (LibRow) -> Unit = {},
) : ListAdapter<LibRow, RecyclerView.ViewHolder>(ROW_DIFF) {
    private var environment = environment
    private val focusRequests = RecyclerFocusRequester()

    init {
        setHasStableIds(true)
    }

    fun updateEnvironment(value: CardEnvironment) {
        if (environment == value) return
        environment = value
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).stableKey)

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is LibRow.Header) TYPE_HEADER else TYPE_CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
        } else {
            CardViewHolder(inflater.inflate(R.layout.item_media, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (holder is HeaderViewHolder && row is LibRow.Header) {
            holder.title.text = row.title
            val tree = row.id.startsWith("photo-tree:")
            holder.itemView.findViewById<ImageView>(R.id.sectionFolder).visibility = if (tree && row.collapsible) View.VISIBLE else View.GONE
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.itemView.resources.getDimension(
                if (tree) R.dimen.section_header_tree_title_size else R.dimen.section_header_title_size))
            (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { margins ->
                val density = holder.itemView.resources.displayMetrics.density
                margins.topMargin = ((if (tree && row.depth > 0) 0 else 6) * density).toInt()
                margins.bottomMargin = ((if (tree) 0 else 4) * density).toInt()
            }
            val inset = (12 + row.depth.coerceAtMost(5) * 14) * holder.itemView.resources.displayMetrics.density
            holder.itemView.setPaddingRelative(inset.toInt(), holder.itemView.paddingTop,
                (12 * holder.itemView.resources.displayMetrics.density).toInt(), holder.itemView.paddingBottom)
            holder.title.maxLines = if (row.collapsible) 2 else 1
            holder.chevron.visibility = if (row.collapsible) View.VISIBLE else View.GONE
            holder.chevron.rotation = if (row.expanded) 90f else 0f
            holder.itemView.isFocusable = row.collapsible
            holder.itemView.isClickable = row.collapsible
            holder.itemView.minimumHeight = holder.itemView.resources.getDimensionPixelSize(
                if (tree) R.dimen.section_header_tree_height else if (row.collapsible) R.dimen.section_header_touch_height else R.dimen.section_header_plain_height,
            )
            holder.itemView.background = if (row.collapsible) {
                holder.itemView.context.getDrawable(R.drawable.bg_section_header)
            } else null
            holder.itemView.setOnFocusChangeListener { _, focused ->
                if (focused && row.collapsible) onFocused(LibraryFocusTarget(null, row.stableKey), row)
            }
            holder.itemView.contentDescription = row.title
            ViewCompat.setAccessibilityHeading(holder.itemView, true)
            ViewCompat.setStateDescription(holder.itemView, if (row.collapsible) {
                holder.itemView.context.getString(
                    if (row.expanded) R.string.section_expanded else R.string.section_collapsed,
                )
            } else null)
            holder.itemView.setOnClickListener(if (row.collapsible) {
                View.OnClickListener { onHeaderToggle(row) }
            } else null)
            if (row.collapsible) {
                ViewCompat.replaceAccessibilityAction(
                    holder.itemView,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    holder.itemView.context.getString(
                        if (row.expanded) R.string.section_collapse_action else R.string.section_expand_action,
                    ),
                ) { _, _ -> onHeaderToggle(row); true }
            } else {
                ViewCompat.removeAccessibilityAction(
                    holder.itemView, AccessibilityNodeInfoCompat.ACTION_CLICK,
                )
            }
            return
        }
        bindCard(
            holder = holder as CardViewHolder,
            row = row,
            environment = environment,
            onMedia = onMedia,
            onQuickPlay = onQuickPlay,
            onSeries = onSeries,
            onFocused = { onFocused(LibraryFocusTarget(null, it.stableKey), it) },
            onAlbum = onAlbum,
            onCollection = onCollection,
            onLongPress = onLongPress,
        )
    }

    fun configureSpans(layout: GridLayoutManager) {
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position in 0 until itemCount && getItem(position) is LibRow.Header) {
                    layout.spanCount
                } else {
                    1
                }
        }
    }

    fun restoreFocus(recycler: RecyclerView, rowKey: String) {
        val row = currentList.firstOrNull { it.stableKey == rowKey }
            ?: currentList.firstOrNull { it !is LibRow.Header || it.collapsible }
            ?: return
        focusRequests.request(recycler, position = { currentList.indexOfFirst { it.stableKey == row.stableKey } }) {
            it.requestFocus()
        }
    }

    override fun onCurrentListChanged(previousList: MutableList<LibRow>, currentList: MutableList<LibRow>) {
        focusRequests.cancel()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        focusRequests.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
    }
}

internal class HomeShelfAdapter(
    environment: CardEnvironment,
    private val onMedia: (MediaItem) -> Unit,
    private val onQuickPlay: (MediaItem) -> Unit,
    private val onSeries: (LibRow.SeriesRow) -> Unit,
    private val onFocused: (LibraryFocusTarget, LibRow) -> Unit,
    private val onAlbum: (LibRow.AlbumRow) -> Unit = {},
    private val onCollection: (LibRow.CollectionRow) -> Unit = {},
    private val onLongPress: (LibRow) -> Unit = {},
) : ListAdapter<HomeShelf, HomeShelfAdapter.ShelfViewHolder>(SHELF_DIFF) {
    private val recycledCards = RecyclerView.RecycledViewPool()
    private val horizontalPositions = mutableMapOf<String, ShelfScrollAnchor>()
    private var pendingFocus: PendingShelfFocus? = null
    private var focusVersion = 0L
    private val focusRequests = RecyclerFocusRequester()
    private var environment = environment

    init {
        setHasStableIds(true)
    }

    fun updateEnvironment(value: CardEnvironment) {
        if (environment == value) return
        environment = value
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemId(position: Int): Long = stableLongId("shelf:${getItem(position).id}")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_shelf, parent, false)
        val holder = ShelfViewHolder(view)
        holder.cards.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        holder.cards.setRecycledViewPool(recycledCards)
        holder.cards.setHasFixedSize(false)
        holder.cards.itemAnimator = null
        holder.adapter = ShelfCardAdapter(
            environment = environment,
            onMedia = onMedia,
            onQuickPlay = onQuickPlay,
            onSeries = onSeries,
            onAlbum = onAlbum,
            onCollection = onCollection,
            onLongPress = onLongPress,
            onFocused = { row ->
                holder.shelfId?.let { onFocused(LibraryFocusTarget(it, row.stableKey), row) }
            },
        )
        holder.cards.adapter = holder.adapter
        holder.cards.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!holder.bindingRows) rememberPosition(holder)
            }
        })
        return holder
    }

    override fun onBindViewHolder(holder: ShelfViewHolder, position: Int) {
        val shelf = getItem(position)
        if (!holder.bindingRows) rememberPosition(holder)
        val changedShelf = holder.shelfId != shelf.id
        if (changedShelf) holder.adapter.cancelFocusRestore()
        val changedKeys = holder.adapter.currentList.map { it.stableKey } != shelf.rows.map { it.stableKey }
        val saved = horizontalPositions[shelf.id]
        val bindingVersion = ++holder.bindingVersion
        holder.bindingRows = true
        holder.shelfId = shelf.id
        holder.title.text = shelf.title
        val landscape = shelf.id in setOf("continue", "up_next", "movies:continue")
        holder.adapter.updateAspect(if (landscape) ArtworkPolicy.Aspect.LANDSCAPE else ArtworkPolicy.Aspect.POSTER)
        val minimumHeight = holder.cards.resources.getDimensionPixelSize(
            if (landscape) R.dimen.library_landscape_shelf_height else R.dimen.library_shelf_height,
        )
        val resources = holder.cards.resources
        val cardWidth = (resources.getDimensionPixelSize(
            if (landscape) R.dimen.library_landscape_card_width else R.dimen.library_card_width,
        ) * if (environment.compact) 1f else 1.1f).toInt()
        val artworkHeight = cardWidth * if (landscape) 9f / 16f else 1.5f
        val paint = Paint()
        paint.textSize = resources.getDimension(R.dimen.card_title_size)
        val titleLineHeight = paint.fontMetrics.run { bottom - top }
        paint.textSize = resources.getDimension(R.dimen.card_meta_size)
        val metaLineHeight = paint.fontMetrics.run { bottom - top }
        // Seul le texte grandit avec les préférences d'accessibilité ; les affiches
        // gardent leur taille. Inclure les deux lignes, la métadonnée et le focus.
        val contentHeight = artworkHeight + titleLineHeight * 2 + metaLineHeight +
            30f * resources.displayMetrics.density
        val height = maxOf(minimumHeight, ceil(contentHeight).toInt())
        if (holder.cards.layoutParams.height != height) {
            holder.cards.layoutParams = holder.cards.layoutParams.apply { this.height = height }
        }
        holder.adapter.updateEnvironment(environment)
        holder.adapter.submitList(shelf.rows) {
            if (holder.bindingVersion != bindingVersion || holder.shelfId != shelf.id) return@submitList
            holder.cards.post {
                if (holder.bindingVersion != bindingVersion || holder.shelfId != shelf.id) return@post
                // Un simple changement de progression ne doit jamais remettre le rail à zéro.
                if (changedShelf || changedKeys) {
                    val anchor = resolveShelfScrollAnchor(saved, shelf.rows.map { it.stableKey })
                    (holder.cards.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        anchor?.position ?: 0, anchor?.offsetPx ?: 0,
                    )
                }
                holder.bindingRows = false
                applyPendingFocus(holder, shelf)
            }
        }
    }

    override fun onViewRecycled(holder: ShelfViewHolder) {
        if (!holder.bindingRows) rememberPosition(holder)
        holder.bindingVersion++
        holder.bindingRows = false
        holder.adapter.cancelFocusRestore()
        holder.shelfId = null
        super.onViewRecycled(holder)
    }

    private fun rememberPosition(holder: ShelfViewHolder) {
        val shelfId = holder.shelfId ?: return
        if (holder.cards.hasPendingAdapterUpdates() || holder.cards.isComputingLayout) return
        val layout = holder.cards.layoutManager as? LinearLayoutManager ?: return
        val position = layout.findFirstVisibleItemPosition()
        val view = layout.findViewByPosition(position) ?: return
        val key = view.tag as? String ?: return
        val itemPosition = holder.cards.getChildAdapterPosition(view)
        if (holder.adapter.currentList.getOrNull(itemPosition)?.stableKey != key) return
        val margins = view.layoutParams as RecyclerView.LayoutParams
        val offset = if (holder.cards.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            holder.cards.width - holder.cards.paddingRight - layout.getDecoratedRight(view) - margins.rightMargin
        } else layout.getDecoratedLeft(view) - margins.leftMargin - holder.cards.paddingLeft
        horizontalPositions[shelfId] = ShelfScrollAnchor(key, itemPosition, offset)
    }

    fun restoreFocus(recycler: RecyclerView, target: LibraryFocusTarget) {
        focusRequests.cancel()
        val version = ++focusVersion
        pendingFocus = null
        if (currentList.isEmpty()) return
        val requestedShelfId = target.shelfId
        val exactPosition = currentList.indexOfFirst { it.id == requestedShelfId }
        val position = if (exactPosition != RecyclerView.NO_POSITION) {
            exactPosition
        } else {
            nearestShelfPosition(requestedShelfId)
        }
        val shelf = currentList[position]
        pendingFocus = PendingShelfFocus(
            shelfId = shelf.id,
            rowKey = target.rowKey.takeIf { exactPosition != RecyclerView.NO_POSITION },
            version = version,
        )
        focusRequests.request(recycler, position = { currentList.indexOfFirst { it.id == shelf.id } },
            isCurrent = { version == focusVersion }) { shelfView ->
            val holder = recycler.getChildViewHolder(shelfView) as? ShelfViewHolder
                ?: return@request false
            if (holder.shelfId == shelf.id && holder.adapter.represents(shelf.rows)) {
                applyPendingFocus(holder, shelf)
                true
            } else false
        }
    }

    private fun applyPendingFocus(holder: ShelfViewHolder, shelf: HomeShelf) {
        val pending = pendingFocus?.takeIf { it.shelfId == shelf.id && it.version == focusVersion } ?: return
        pendingFocus = null
        holder.adapter.restoreFocusOrFirst(holder.cards, pending.rowKey) {
            pending.version == focusVersion && holder.shelfId == shelf.id && holder.adapter.represents(shelf.rows)
        }
    }

    override fun onCurrentListChanged(previousList: MutableList<HomeShelf>, currentList: MutableList<HomeShelf>) {
        focusVersion++
        pendingFocus = null
        focusRequests.cancel()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        focusVersion++
        pendingFocus = null
        focusRequests.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun nearestShelfPosition(missingShelfId: String?): Int {
        val requestedId = missingShelfId ?: return 0
        val requestedOrder = SHELF_ORDER.indexOf(requestedId).takeIf { it >= 0 } ?: return 0
        return currentList.indices.minByOrNull { index ->
            val order = SHELF_ORDER.indexOf(currentList[index].id).takeIf { it >= 0 } ?: Int.MAX_VALUE
            if (order == Int.MAX_VALUE) order else abs(order - requestedOrder)
        } ?: 0
    }

    private data class PendingShelfFocus(val shelfId: String, val rowKey: String?, val version: Long)

    internal class ShelfViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.shelfTitle)
        val cards: RecyclerView = view.findViewById(R.id.shelfCards)
        lateinit var adapter: ShelfCardAdapter
        var shelfId: String? = null
        var bindingVersion = 0L
        var bindingRows = false
    }

    private companion object {
        val SHELF_ORDER = listOf("continue", "up_next", "recent", "movies", "series")
    }
}

internal class ShelfCardAdapter(
    environment: CardEnvironment,
    private val onMedia: (MediaItem) -> Unit,
    private val onQuickPlay: (MediaItem) -> Unit,
    private val onSeries: (LibRow.SeriesRow) -> Unit,
    private val onFocused: (LibRow) -> Unit,
    private val onAlbum: (LibRow.AlbumRow) -> Unit = {},
    private val onCollection: (LibRow.CollectionRow) -> Unit = {},
    private val onLongPress: (LibRow) -> Unit = {},
) : ListAdapter<LibRow, CardViewHolder>(ROW_DIFF) {
    private var environment = environment
    private var aspect = ArtworkPolicy.Aspect.POSTER
    private val focusRequests = RecyclerFocusRequester()

    init {
        setHasStableIds(true)
    }

    fun updateEnvironment(value: CardEnvironment) {
        if (environment == value) return
        environment = value
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).stableKey)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        val density = parent.resources.displayMetrics.density
        val width = (parent.resources.getDimensionPixelSize(R.dimen.library_card_width) *
            if (environment.compact) 1f else 1.1f).toInt()
        val margin = ((if (environment.compact) 4 else 6) * density).toInt()
        view.layoutParams = RecyclerView.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(margin, margin, margin, margin)
        }
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val width = (holder.itemView.resources.getDimensionPixelSize(
            if (aspect == ArtworkPolicy.Aspect.LANDSCAPE) R.dimen.library_landscape_card_width else R.dimen.library_card_width,
        ) * if (environment.compact) 1f else 1.1f).toInt()
        if (holder.itemView.layoutParams.width != width) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply { this.width = width }
        }
        bindCard(
            holder,
            getItem(position),
            environment,
            onMedia,
            onQuickPlay,
            onSeries,
            onFocused,
            aspect,
            onAlbum,
            onCollection,
            onLongPress,
        )
    }

    fun updateAspect(value: ArtworkPolicy.Aspect) {
        if (aspect == value) return
        aspect = value
        notifyItemRangeChanged(0, itemCount)
    }

    fun represents(rows: List<LibRow>): Boolean = currentList == rows

    fun restoreFocusOrFirst(recycler: RecyclerView, rowKey: String?, isCurrent: () -> Boolean) {
        val row = currentList.firstOrNull { it.stableKey == rowKey } ?: currentList.firstOrNull() ?: return
        focusRequests.request(recycler, position = { currentList.indexOfFirst { it.stableKey == row.stableKey } },
            isCurrent = isCurrent) { it.requestFocus() }
    }

    fun cancelFocusRestore() = focusRequests.cancel()

    override fun onCurrentListChanged(previousList: MutableList<LibRow>, currentList: MutableList<LibRow>) {
        focusRequests.cancel()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        focusRequests.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}

internal class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val title: TextView = view.findViewById(R.id.sectionTitle)
    val chevron: ImageView = view.findViewById(R.id.sectionChevron)
}

internal class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val poster: PosterImageView = view.findViewById(R.id.poster)
    val title: TextView = view.findViewById(R.id.title)
    val meta: TextView = view.findViewById(R.id.meta)
    val hdr: TextView = view.findViewById(R.id.hdrBadge)
    val watched: TextView = view.findViewById(R.id.watchedBadge)
    val resume: ProgressBar = view.findViewById(R.id.resumeBar)
}

private fun bindCard(
    holder: CardViewHolder,
    row: LibRow,
    environment: CardEnvironment,
    onMedia: (MediaItem) -> Unit,
    onQuickPlay: (MediaItem) -> Unit,
    onSeries: (LibRow.SeriesRow) -> Unit,
    onFocused: (LibRow) -> Unit,
    forcedAspect: ArtworkPolicy.Aspect? = null,
    onAlbum: (LibRow.AlbumRow) -> Unit = {},
    onCollection: (LibRow.CollectionRow) -> Unit = {},
    onLongPress: (LibRow) -> Unit = {},
) {
    holder.itemView.tag = row.stableKey
    holder.itemView.setOnFocusChangeListener { _, focused ->
        if (focused) onFocused(row)
    }
    holder.itemView.setOnLongClickListener { onLongPress(row); true }
    when (row) {
        is LibRow.MediaRow -> bindMediaCard(
            holder, row.item, null, false, environment, onMedia, onQuickPlay, forcedAspect,
        )
        is LibRow.QuickPlayRow -> bindMediaCard(
            holder, row.item, row.subtitle, true, environment, onMedia, onQuickPlay, forcedAspect,
        )
        is LibRow.SeriesRow -> bindSeriesCard(holder, row, environment, onSeries, forcedAspect)
        is LibRow.AlbumRow -> bindAlbumCard(holder, row, environment, onAlbum)
        is LibRow.CollectionRow -> bindCollectionCard(holder, row, environment, onCollection)
        is LibRow.Header -> Unit
    }
}

private fun bindMediaCard(
    holder: CardViewHolder,
    media: MediaItem,
    contextualSubtitle: String?,
    quickPlay: Boolean,
    environment: CardEnvironment,
    onMedia: (MediaItem) -> Unit,
    onQuickPlay: (MediaItem) -> Unit,
    forcedAspect: ArtworkPolicy.Aspect?,
) {
    val displayTitle = LibraryOrganizer.displayTitle(media)
    holder.title.text = displayTitle
    val details = contextualSubtitle?.takeIf { it.isNotBlank() } ?: if (media.kind == "photo" && environment.photoFolderInHeader) "" else mediaMeta(
        media,
        environment.advancedMode,
    )
    holder.meta.text = if (!media.available) {
        listOf(holder.itemView.context.getString(R.string.media_temporarily_unavailable), details)
            .filter { it.isNotBlank() }.joinToString(" · ")
    } else details
    holder.meta.visibility = if (holder.meta.text.isBlank()) View.GONE else View.VISIBLE

    val isVideo = media.kind in setOf("movie", "series", "clip")
    holder.hdr.visibility = if (isVideo && media.isHdr) View.VISIBLE else View.GONE
    holder.watched.visibility = if (isVideo && media.watched) View.VISIBLE else View.GONE
    holder.resume.visibility =
        if (isVideo && media.inProgress && media.progress > 0.02) View.VISIBLE else View.GONE
    holder.resume.progress = (media.progress * 100).toInt().coerceIn(1, 100)
    holder.poster.alpha = if (isVideo && media.watched) 0.68f else 1f

    val localThumbnail = resolveLanThumbnail(holder.itemView, media.thumbnailUrl)
    val plan = ArtworkPolicy.plan(
        kind = media.kind,
        width = media.width,
        height = media.height,
        tmdbPosterUrl = LanUrlPolicy.safeTmdbImage(media.posterUrl),
        localThumbnailUrl = localThumbnail,
    )
    loadArtwork(holder, media.id, displayTitle, plan, environment, forcedAspect)
    holder.itemView.contentDescription = buildString {
        append(displayTitle)
        if (holder.meta.text.isNotBlank()) append(", ${holder.meta.text}")
        if (media.inProgress) append(", lecture en cours")
        if (media.watched) append(", vu")
    }
    holder.itemView.setOnClickListener {
        if (quickPlay) onQuickPlay(media) else onMedia(media)
    }
}

private fun bindSeriesCard(
    holder: CardViewHolder,
    series: LibRow.SeriesRow,
    environment: CardEnvironment,
    onSeries: (LibRow.SeriesRow) -> Unit,
    forcedAspect: ArtworkPolicy.Aspect?,
) {
    val title = SeriesActivity.displayName(series.name)
    holder.title.text = title
    holder.meta.text = buildString {
        append(series.count)
        append(if (series.count > 1) " épisodes" else " épisode")
        if (series.watchedCount > 0) append(" · ${series.watchedCount} vus")
    }
    holder.meta.visibility = View.VISIBLE
    holder.hdr.visibility = View.GONE
    val allWatched = series.count > 0 && series.watchedCount >= series.count
    holder.watched.visibility = if (allWatched) View.VISIBLE else View.GONE
    holder.resume.visibility =
        if (series.inProgressCount > 0 || series.watchedCount in 1 until series.count) View.VISIBLE else View.GONE
    holder.resume.progress =
        if (series.count > 0) (series.watchedCount * 100 / series.count).coerceIn(1, 100) else 0
    holder.poster.alpha = if (allWatched) 0.68f else 1f

    val plan = ArtworkPolicy.plan(
        kind = "series",
        tmdbPosterUrl = LanUrlPolicy.safeTmdbImage(series.poster),
        localThumbnailUrl = resolveLanThumbnail(holder.itemView, series.thumbnail),
    )
    loadArtwork(holder, "series:${series.key}", title, plan, environment, forcedAspect)
    holder.itemView.contentDescription =
        "$title, ${holder.meta.text}${if (allWatched) ", série vue" else ""}"
    holder.itemView.setOnClickListener { onSeries(series) }
}

private fun bindAlbumCard(
    holder: CardViewHolder, album: LibRow.AlbumRow, environment: CardEnvironment,
    onAlbum: (LibRow.AlbumRow) -> Unit,
) {
    holder.title.text = album.name
    holder.meta.text = album.subtitle
    holder.meta.visibility = View.VISIBLE
    holder.hdr.visibility = View.GONE
    holder.watched.visibility = View.GONE
    holder.resume.visibility = View.GONE
    holder.poster.alpha = 1f
    val plan = ArtworkPolicy.plan(kind = album.kind,
        localThumbnailUrl = resolveLanThumbnail(holder.itemView, album.thumbnail))
    loadArtwork(holder, album.stableKey, album.name, plan, environment, ArtworkPolicy.Aspect.SQUARE)
    holder.itemView.contentDescription = "${album.name}, ${album.subtitle}"
    holder.itemView.setOnClickListener { onAlbum(album) }
}

private fun bindCollectionCard(
    holder: CardViewHolder, row: LibRow.CollectionRow, environment: CardEnvironment,
    onCollection: (LibRow.CollectionRow) -> Unit,
) {
    holder.title.text = row.collection.name
    val count = row.items.count { it.kind != "series" } + row.items.filter { it.kind == "series" }
        .map(::mediaSeriesIdentity).distinct().size
    holder.meta.text = "$count ${if (count > 1) "médias" else "média"}${if (row.collection.smartQuery != null) " · Automatique" else ""}"
    holder.meta.visibility = View.VISIBLE
    holder.hdr.visibility = View.GONE
    holder.watched.visibility = View.GONE
    holder.resume.visibility = View.GONE
    holder.poster.alpha = 1f
    val plan = ArtworkPolicy.plan("movie", tmdbPosterUrl = LanUrlPolicy.safeTmdbImage(row.thumbnail),
        localThumbnailUrl = resolveLanThumbnail(holder.itemView, row.thumbnail))
    loadArtwork(holder, row.stableKey, row.collection.name, plan, environment, ArtworkPolicy.Aspect.POSTER)
    holder.itemView.contentDescription = "${row.collection.name}, ${holder.meta.text}"
    holder.itemView.setOnClickListener { onCollection(row) }
}

private fun loadArtwork(
    holder: CardViewHolder,
    bindingKey: String,
    title: String,
    plan: ArtworkPolicy.Plan,
    environment: CardEnvironment,
    forcedAspect: ArtworkPolicy.Aspect?,
) {
    holder.poster.tag = bindingKey
    holder.poster.dispose()
    val candidates = if (forcedAspect == ArtworkPolicy.Aspect.LANDSCAPE) {
        plan.candidates.sortedBy { candidate ->
            if (candidate.aspect == ArtworkPolicy.Aspect.LANDSCAPE) 0 else 1
        }
    } else {
        plan.candidates
    }
    val fallback = BrandedArtworkDrawable(
        title,
        plan.typeLabel,
        plan.resolutionLabel,
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            1f,
            holder.itemView.resources.displayMetrics,
        ),
    )
    // The card geometry must be stable for the whole bind.  Changing it when Coil
    // finishes an asynchronous fallback made a grid row keep its old measurement,
    // allowing a later thumbnail to overlap the following row.
    val frameAspect = forcedAspect ?: plan.fallbackAspect
    holder.poster.setArtworkAspect(frameAspect)
    holder.poster.setImageDrawable(fallback)
    holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP
    holder.poster.contentDescription = null

    fun loadAt(index: Int) {
        if (holder.poster.tag != bindingKey) return
        val candidate = candidates.getOrNull(index)
        if (candidate == null) {
            holder.poster.dispose()
            holder.poster.setArtworkAspect(frameAspect)
            holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.poster.setImageDrawable(fallback)
            return
        }
        holder.poster.setArtworkAspect(frameAspect)
        holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP
        val builder: coil.request.ImageRequest.Builder.() -> Unit = {
            crossfade(true)
            placeholder(fallback)
            listener(
                onError = { _, _ ->
                    if (holder.poster.tag == bindingKey) loadAt(index + 1)
                },
            )
        }
        if (candidate.role == ArtworkPolicy.Role.TMDB_POSTER) {
            holder.poster.load(candidate.value, builder = builder)
        } else {
            holder.poster.load(candidate.value, environment.lanImages, builder)
        }
    }
    loadAt(0)
}

private fun mediaMeta(media: MediaItem, advancedMode: Boolean): String = when (media.kind) {
    "photo" -> if (advancedMode) {
        listOfNotNull(
            media.width?.let { width -> media.height?.let { "$width×$it" } },
            media.container.uppercase(),
        ).joinToString(" · ")
    } else {
        media.folder.orEmpty()
    }
    "audio" -> buildList {
        media.artist?.let(::add)
        media.album?.let(::add)
        if (advancedMode) add(media.container.uppercase())
        if (isEmpty() && media.duration > 0) add("${(media.duration / 60).toInt()} min")
    }.joinToString(" · ")
    else -> {
        val resolution = ArtworkPolicy.resolutionLabel(media.width, media.height)
        if (advancedMode) {
            listOfNotNull(media.year?.toString(), media.videoCodec?.uppercase(), resolution)
                .joinToString(" · ")
        } else {
            listOfNotNull(media.year?.toString(), resolution).joinToString(" · ")
        }
    }
}

private fun resolveLanThumbnail(view: View, relative: String?): String? = relative?.let {
    runCatching {
        LanUrlPolicy.resolveSameServer(
            PluxyApplication.serverBaseUrl(view.context),
            it,
        )
    }.getOrNull()
}

/** Une seule restauration en attente ; une carte déplacée est recherchée par identité. */
internal class RecyclerFocusRequester {
    private var pending: Request? = null

    fun cancel() {
        pending?.dispose()
        pending = null
    }

    fun request(
        recycler: RecyclerView,
        position: () -> Int,
        isCurrent: () -> Boolean = { true },
        action: (View) -> Boolean,
    ) {
        cancel()
        val adapter = recycler.adapter ?: return
        val initialPosition = position()
        if (initialPosition !in 0 until adapter.itemCount) return
        lateinit var request: Request
        request = Request(recycler, adapter, initialPosition, position, isCurrent, action) {
            if (pending === request) pending = null
        }
        pending = request
        request.start()
    }

    private class Request(
        private val recycler: RecyclerView,
        private val adapter: RecyclerView.Adapter<*>,
        private var scrolledPosition: Int,
        private val position: () -> Int,
        private val isCurrent: () -> Boolean,
        private val action: (View) -> Boolean,
        private val onDisposed: () -> Unit,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val itemId = adapter.getItemId(scrolledPosition)
        private var active = true
        private val deadline = SystemClock.uptimeMillis() + 600L
        private var observer: ViewTreeObserver? = null
        private var scrollRequested = false

        fun start() {
            recycler.addOnAttachStateChangeListener(this)
            recycler.post {
                if (!valid() || focusIfReady()) {
                    dispose()
                } else {
                    observer = recycler.viewTreeObserver
                    observer?.addOnPreDrawListener(this)
                    recycler.invalidate()
                }
            }
        }

        private fun valid(): Boolean {
            if (!active || recycler.adapter !== adapter || !isCurrent()) return false
            val target = position()
            return target in 0 until adapter.itemCount &&
                (!adapter.hasStableIds() || adapter.getItemId(target) == itemId)
        }

        private fun focusIfReady(): Boolean {
            if (recycler.isComputingLayout || recycler.hasPendingAdapterUpdates()) return false
            val target = position()
            val holder = recycler.findViewHolderForAdapterPosition(target)
            if (holder == null) {
                if (!scrollRequested || target != scrolledPosition) {
                    scrolledPosition = target
                    scrollRequested = true
                    recycler.scrollToPosition(target)
                }
                return false
            }
            return (!adapter.hasStableIds() || holder.itemId == itemId) && action(holder.itemView)
        }

        override fun onPreDraw(): Boolean {
            if (!valid() || focusIfReady() || SystemClock.uptimeMillis() >= deadline) dispose()
            else recycler.postInvalidateOnAnimation()
            return true
        }

        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dispose()

        fun dispose() {
            if (!active) return
            active = false
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            recycler.removeOnAttachStateChangeListener(this)
            onDisposed()
        }
    }
}

private val ROW_DIFF = object : DiffUtil.ItemCallback<LibRow>() {
    override fun areItemsTheSame(oldItem: LibRow, newItem: LibRow): Boolean =
        oldItem.stableKey == newItem.stableKey

    override fun areContentsTheSame(oldItem: LibRow, newItem: LibRow): Boolean =
        oldItem == newItem
}

private val SHELF_DIFF = object : DiffUtil.ItemCallback<HomeShelf>() {
    override fun areItemsTheSame(oldItem: HomeShelf, newItem: HomeShelf): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: HomeShelf, newItem: HomeShelf): Boolean =
        oldItem == newItem
}
