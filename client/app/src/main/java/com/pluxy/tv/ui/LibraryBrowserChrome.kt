package com.pluxy.tv.ui

import android.view.Gravity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pluxy.tv.R

/** Commandes compactes de la bibliothèque, distinctes de la navigation principale. */
class LibraryBrowserChrome(
    private val activity: MainActivity,
    private val television: Boolean,
    private val onDestination: () -> Unit,
    private val onView: (View) -> Unit,
    private val onFilters: () -> Unit,
    private val onSort: (View) -> Unit,
    private val onCollections: () -> Unit,
    private val onRemoveFilter: (String) -> Unit,
    private val onHome: () -> Unit,
    private val onLibrary: () -> Unit,
    private val onSearch: () -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private val landscape = !television && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun dp(value: Int) = (value * density).toInt()
    private val parent = activity.findViewById<View>(R.id.swipe).parent as FrameLayout
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val commands = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chips = FlowLayout(activity)
    private val count = TextView(activity).apply {
        textSize = 13f
        setTextColor(activity.pluxyColor(R.color.text_secondary))
        setPadding(2, dp(6), 0, dp(2))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun button(label: String, action: (View) -> Unit): Button = Button(activity, null, 0, R.style.Btn_TopBarChoice).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener(action)
    }
    private val mode = button("Bibliothèque", onView)
    private val filters = button("Filtres") { onFilters() }
    private val sort = button("Titre ↑", onSort)
    private val collection = button("Créer") { onCollections() }
    private val destination = button("Films") { onDestination() }
    private val bottom = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_glass_toolbar)
    }
    private val home = button("Accueil") { onHome() }
    private val library = button("Bibliothèque") { onLibrary() }
    private val search = button("Recherche") { onSearch() }

    init {
        listOf(mode, filters, sort, collection).forEach {
            val params = if (television) LinearLayout.LayoutParams(-2, -2)
                else LinearLayout.LayoutParams(0, -2, 1f)
            commands.addView(it, params.apply { marginEnd = dp(if (television) 6 else 3) })
        }
        rows.addView(commands)
        rows.addView(chips)
        rows.addView(count)
        val safe = activity.resources.getDimensionPixelSize(R.dimen.screen_pad)
        parent.addView(rows, FrameLayout.LayoutParams(-1, -2).apply {
            marginStart = if (television) activity.resources.getDimensionPixelSize(R.dimen.library_rail_content_start)
                else safe + if (landscape) dp(150) else 0
            marginEnd = safe + if (landscape) dp(56) else 0
            topMargin = dp(if (television) 72 else if (landscape) 12 else 64)
        })
        if (!television) {
            activity.findViewById<View>(R.id.categoryNavigation).visibility = View.GONE
            activity.findViewById<View>(R.id.swipeHint).visibility = View.GONE
            activity.findViewById<View>(R.id.search).visibility = View.GONE
            val brand = activity.findViewById<LinearLayout>(R.id.brand)
            brand.addView(destination, LinearLayout.LayoutParams(-2, -2))
            listOf(home, library, search).forEach {
                it.setBackgroundResource(R.drawable.bg_tab)
                it.textSize = 13f
                bottom.addView(it, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            parent.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                marginStart = safe; marginEnd = safe; bottomMargin = dp(4)
            })
        }
        rows.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionContent() }
    }

    fun render(kind: String, query: TvLibraryContent.Query, view: String, total: Int, more: Boolean, searchText: String, collectionName: String? = null) {
        val video = if (searchText.isNotBlank()) query.searchScope in setOf("all", "movie", "series")
            else kind in setOf("movie", "series")
        rows.visibility = if (kind == "home" && searchText.isBlank()) View.GONE else View.VISIBLE
        destination.visibility = if (kind == "home" || searchText.isNotBlank()) View.GONE else View.VISIBLE
        destination.text = when (kind) { "movie" -> "Films"; "series" -> "Séries"; "photo" -> "Photos"; "audio" -> "Musique"; else -> "Médias" }
        destination.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_library_dropdown, 0)
        mode.text = when {
            collectionName != null -> "Collections"
            kind == "photo" -> if (query.photoView == "timeline") "Chronologie" else "Albums"
            kind == "audio" && view != "collections" -> "Albums"
            view == "discover" -> "Découvrir"
            view == "collections" -> "Collections"
            else -> if (television) "Bibliothèque" else "Catalogue"
        }
        mode.visibility = if (searchText.isBlank()) View.VISIBLE else View.GONE
        filters.visibility = if (video && view != "collections" && collectionName == null) View.VISIBLE else View.GONE
        collection.visibility = if (view == "collections" || collectionName != null) View.VISIBLE else View.GONE
        collection.text = if (collectionName != null) "Gérer" else "Créer"
        sort.visibility = if (view != "collections" && collectionName == null) View.VISIBLE else View.GONE
        sort.text = when (query.sort) { "added" -> "Ajouts"; "year" -> "Année"; "duration" -> "Durée"; "captured" -> "Date"; else -> "Titre" } +
            if (query.descending ?: (query.sort != "title")) " ↓" else " ↑"
        val active = activeFilters(query)
        filters.text = if (active.isEmpty()) "Filtres" else "Filtres (${active.size})"
        filters.isSelected = active.isNotEmpty()
        chips.removeAllViews()
        active.take(3).forEach { (key, label) ->
            chips.addView(button(label) { onRemoveFilter(key) }.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_close, 0)
                compoundDrawablePadding = dp(4)
                contentDescription = "Retirer le filtre $label"
            }, ViewGroup.LayoutParams(-2, -2))
        }
        if (active.size > 3) chips.addView(button("+ ${active.size - 3} filtres") { onFilters() }, ViewGroup.LayoutParams(-2, -2))
        chips.visibility = if (active.isEmpty() || view == "collections" || collectionName != null) View.GONE else View.VISIBLE
        count.text = when {
            collectionName != null -> "$collectionName · $total ${if (total == 1) "élément" else "éléments"}"
            searchText.isNotBlank() -> "« $searchText » · $total ${if (total == 1) "résultat" else "résultats"}"
            view == "collections" -> "$total ${if (total == 1) "collection" else "collections"}"
            kind == "photo" -> "$total ${if (total == 1) "photo" else "photos"}"
            kind == "audio" -> "$total ${if (total == 1) "titre" else "titres"}"
            kind == "series" -> "$total ${if (total == 1) "série" else "séries"}"
            else -> "$total ${if (total == 1) "film" else "films"}"
        } + if (more) " · chargement au défilement" else ""
        count.visibility = if (landscape) View.GONE else View.VISIBLE
        if (landscape) commands.contentDescription = count.text
        home.isSelected = kind == "home" && searchText.isBlank()
        library.isSelected = kind != "home" && searchText.isBlank()
        search.isSelected = searchText.isNotBlank()
        positionContent()
    }

    fun positionContent() {
        val swipe = activity.findViewById<View>(R.id.swipe)
        val params = swipe.layoutParams as ViewGroup.MarginLayoutParams
        val top = (rows.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        params.topMargin = if (rows.visibility == View.VISIBLE) top + rows.measuredHeight + dp(8) else dp(if (television) 92 else 64)
        params.bottomMargin = if (television) 0 else dp(72)
        swipe.layoutParams = params
    }

    fun contentStart(pixels: Int) {
        val params = rows.layoutParams as ViewGroup.MarginLayoutParams
        if (params.marginStart != pixels) { params.marginStart = pixels; rows.layoutParams = params }
    }

    fun focusCommands(): Boolean = rows.visibility == View.VISIBLE &&
        listOf(mode, filters, sort, collection).firstOrNull { it.visibility == View.VISIBLE }?.requestFocus() == true

    companion object {
        fun activeFilters(query: TvLibraryContent.Query): List<Pair<String, String>> = buildList {
            if (query.watchedFilter != "all") add("watched" to when (query.watchedFilter) { "watched" -> "Déjà vus"; "in_progress" -> "En cours"; else -> "Non vus" })
            query.genres.sorted().forEach { add("genre:$it" to it) }
            query.year?.let { add("year" to it.toString()) }
            query.durationMaxMinutes?.let { add("duration" to "≤ $it min") }
            if (query.resolution != "all") add("resolution" to query.resolution.uppercase())
            if (query.hdrOnly) add("hdr" to "HDR")
        }
    }
}

/** Les filtres passent à la ligne au lieu de disparaître hors de l'écran. */
private class FlowLayout(context: android.content.Context) : ViewGroup(context) {
    private val gap = (4 * resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + child.measuredWidth > width) { y += lineHeight + gap; x = 0; lineHeight = 0 }
            x += child.measuredWidth + gap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(width, resolveSize(y + lineHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0; var y = 0; var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > 0 && x + child.measuredWidth > width) { y += lineHeight + gap; x = 0; lineHeight = 0 }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
