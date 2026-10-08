package com.pluxy.tv.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.FocusFinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.ImageLoader
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.LibraryRequest
import com.pluxy.tv.api.LibraryFacets
import com.pluxy.tv.api.ApiException
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.networkOrNull
import com.pluxy.tv.api.Discovery
import com.pluxy.tv.api.ServerInfo
import com.pluxy.tv.api.ServerAvailability
import com.pluxy.tv.player.PlayerActivity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Accueil Android TV orienté contenus : rails horizontaux pour les actions fréquentes,
 * destinations visibles et restauration du focus par identifiant stable.
 */
@OptIn(UnstableApi::class)
class MainActivity : ThemedActivity() {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var swipeHint: TextView
    private lateinit var loading: ProgressBar
    private lateinit var libraryHero: View
    private lateinit var libraryHeroImage: ImageView
    private lateinit var libraryHeroEyebrow: TextView
    private lateinit var libraryHeroTitle: TextView
    private lateinit var libraryHeroMeta: TextView
    private lateinit var lanImages: ImageLoader
    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var homeAdapter: HomeShelfAdapter

    private var loadJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectRevision = 0L
    private var serverChoiceDialog: AlertDialog? = null
    private var dismissedServerChoices: Set<String>? = null
    private var contentJob: Job? = null
    private var contentRevision = 0L
    private var fetchRevision = 0L
    private var focusNavigationRevision = 0L
    private val horizontalFocus = RecyclerFocusRequester()
    private val consumedNavigationKeys = mutableSetOf<Int>()
    private var loadedOnce = false
    private var allItems: List<MediaItem> = emptyList()
    private var catalogueServerUrl: String? = null
    /** Une catégorie déjà trouvée ne disparaît jamais durant cette session à cause
     * d'une réponse provisoirement incomplète du serveur. */
    private val discoveredKinds = mutableSetOf<String>()
    private val collapsedPhotoFolders = mutableSetOf<String>()
    private var gridSpan = 3
    private var selectedKind = "home"
    private var advancedMode = false
    private var searchQuery = ""
    private var searchScope = "all"
    private var restoreSavedFocusOnNextContent = false
    private val focusByDestination = mutableMapOf<String, LibraryFocusTarget>()
    private lateinit var browserChrome: LibraryBrowserChrome
    private var facets = LibraryFacets()
    private var serverCatalogueCount = -1
    private var catalogueQueryKey = ""
    private var selectedCollection: LibraryCollection? = null
    private var nextPageOffset: Int? = null
    private var pageRevision: String? = null
    private var pageTotal = 0
    private var pageJob: Job? = null
    private var pagedMovies = false
    private data class BrowserSnapshot(val items: List<MediaItem>, val total: Int, val next: Int? = null,
        val revision: String? = null, val facets: LibraryFacets = LibraryFacets(), val paged: Boolean = false)

    private val setupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        loadedOnce = false
        loadItems()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        swipe = findViewById(R.id.swipe)
        swipeHint = findViewById(R.id.swipeHint)
        loading = findViewById(R.id.mainLoading)
        libraryHero = findViewById(R.id.libraryHero)
        libraryHeroImage = findViewById(R.id.libraryHeroImage)
        libraryHeroEyebrow = findViewById(R.id.libraryHeroEyebrow)
        libraryHeroTitle = findViewById(R.id.libraryHeroTitle)
        libraryHeroMeta = findViewById(R.id.libraryHeroMeta)
        selectedKind = PluxyApplication.libraryTab(this)
        selectedCollection = savedInstanceState?.getString("browser_collection")?.let { id ->
            LibraryPreferences.collections(this).firstOrNull { it.id == id }
        }
        searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
        searchScope = savedInstanceState?.getString(STATE_SEARCH_SCOPE).orEmpty().ifBlank { "all" }
        restoreSavedFocus(savedInstanceState)
        lanImages = LanMediaImages.loader(this)

        val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val screenPaddingDp = resources.getDimension(R.dimen.screen_pad) / resources.displayMetrics.density
        val reservedDp = if (isTelevision() || resources.getBoolean(R.bool.show_library_hero)) {
            resources.getDimension(R.dimen.library_rail_content_start) / resources.displayMetrics.density + screenPaddingDp
        } else screenPaddingDp * 2f
        val contentDp = (screenDp - reservedDp).coerceAtLeast(160f)
        val cardDp = resources.getDimension(R.dimen.library_grid_card_width) / resources.displayMetrics.density
        gridSpan = (contentDp / (cardDp + 8f)).toInt().coerceIn(2, 6)
        grid.setItemViewCacheSize(18)
        grid.itemAnimator = null
        createAdapters()
        configureCompactNavigation(screenDp)

        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.search).setOnClickListener {
            openSearch()
        }
        findViewById<View>(R.id.sortFilter).visibility = View.GONE
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchQuery.isNotBlank()) {
                    clearSearch()
                } else if (selectedCollection != null) {
                    selectedCollection = null
                    applyAndShow(restoreFocus = false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        findViewById<Button>(R.id.photoFolders).setOnClickListener { openPhotoFolders() }
        findViewById<Button>(R.id.emptyAction).setOnClickListener { openSetup() }
        bindTabs()
        configureTvNavigationRail()
        browserChrome = LibraryBrowserChrome(this, usesRail(),
            onDestination = ::openLibraryDestinations,
            onView = ::openLibraryViews,
            onFilters = ::openLibraryFilters,
            onSort = ::openSortFilter,
            onCollections = ::createCollection,
            onRemoveFilter = ::removeLibraryFilter,
            onHome = { selectDestination("home") },
            onLibrary = ::openLastLibrary,
            onSearch = ::openSearch,
        )
        if (usesRail()) grid.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            val compact = focused?.let(::isInGrid) == true
            val navigation = findViewById<View>(R.id.categoryNavigation)
            val density = resources.displayMetrics.density
            val width = ((if (compact) 104 else 124) * density).toInt()
            if (navigation.layoutParams.width != width) {
                navigation.layoutParams = navigation.layoutParams.apply { this.width = width }
                val start = ((if (compact) 172 else 192) * density).toInt()
                browserChrome.contentStart(start)
                swipe.layoutParams = (swipe.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = start }
            }
        }
        selectedTabView().requestFocus()
        updateSearchButton()
        swipe.setOnRefreshListener { refresh() }
        swipe.setColorSchemeColors(pluxyColor(R.color.accent))
        bindMobileCategorySwipe()
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy >= 0 && nextPageOffset != null && (grid.layoutManager as? GridLayoutManager)
                        ?.findLastVisibleItemPosition()?.let { it >= libraryAdapter.itemCount - gridSpan * 4 } == true) loadNextPage()
            }
        })

        if (!PluxyApplication.isConfigured(this)) openSetup()
        updateInterfaceMode()
    }

    override fun onResume() {
        super.onResume()
        updateInterfaceMode()
        if (!PluxyApplication.isConfigured(this)) {
            showNotConfigured()
            return
        }
        lanImages = LanMediaImages.loader(this)
        if (catalogueServerUrl != null && catalogueServerUrl != PluxyApplication.serverBaseUrl(this)) {
            allItems = emptyList()
            discoveredKinds.clear()
            focusByDestination.clear()
            collapsedPhotoFolders.clear()
            loadedOnce = false
            contentJob?.cancel()
            contentRevision++
            grid.adapter = null
            grid.visibility = View.INVISIBLE
            homeAdapter.submitList(emptyList())
            libraryAdapter.submitList(emptyList())
            updateAvailableDestinations()
            selectedTabView().requestFocus()
        }
        if (!loadedOnce) loadItems() else {
            // Local choices must apply even when the server catalogue is unchanged.
            applyAndShow()
            refreshQuietly()
        }
        startAutoRefresh()
    }

    override fun onPause() {
        horizontalFocus.cancel()
        consumedNavigationKeys.clear()
        autoRefreshJob?.cancel()
        reconnectRevision++
        reconnectJob?.cancel()
        reconnectJob = null
        serverChoiceDialog?.dismiss()
        serverChoiceDialog = null
        loadJob?.cancel()
        pageJob?.cancel()
        swipe.isRefreshing = false
        contentJob?.cancel()
        contentRevision++
        autoRefreshJob = null
        super.onPause()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        pageJob?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("browser_collection", selectedCollection?.id)
        outState.putString(STATE_SEARCH_QUERY, searchQuery)
        outState.putString(STATE_SEARCH_SCOPE, searchScope)
        focusByDestination[focusStateKey()]?.let {
            outState.putString(STATE_FOCUS_ROW, it.rowKey)
            outState.putString(STATE_FOCUS_SHELF, it.shelfId)
            outState.putString(STATE_FOCUS_DESTINATION, focusStateKey())
        }
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedNavigationKeys.remove(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                focusNavigationRevision++
                horizontalFocus.cancel()
            }
            val focused = currentFocus
            if (focused != null && isInGrid(focused)) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (moveHorizontally(focused, event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                        consumedNavigationKeys.add(event.keyCode)
                        return true
                    }
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && grid.adapter === libraryAdapter) {
                    val itemView = grid.findContainingItemView(focused)
                    val position = itemView?.let(grid::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
                    if (libraryAdapter.currentList.getOrNull(position) is LibRow.Header) {
                        libraryAdapter.currentList.getOrNull(position + 1)?.let { row ->
                            // A full-width folder must lead to its first photo, not
                            // whichever column happens to align with the header centre.
                            libraryAdapter.restoreFocus(grid, row.stableKey)
                            return true
                        }
                    }
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && !grid.canScrollVertically(-1) &&
                    FocusFinder.getInstance().findNextFocus(grid, focused, View.FOCUS_UP) == null
                ) {
                    if (::browserChrome.isInitialized && browserChrome.focusCommands()) return true
                    selectedTabView().requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveHorizontally(focused: View, right: Boolean): Boolean {
        var ancestor: ViewParent? = focused.parent
        while (ancestor != null && ancestor !is RecyclerView) ancestor = ancestor.parent
        val recycler = ancestor as? RecyclerView ?: return false
        val item = recycler.findContainingItemView(focused) ?: return false
        val position = recycler.getChildAdapterPosition(item)
        val layout = recycler.layoutManager ?: return false
        val count = recycler.adapter?.itemCount ?: return false
        if (position !in 0 until count) return true
        val step = if (right != (recycler.layoutDirection == View.LAYOUT_DIRECTION_RTL)) 1 else -1
        val target = position + step
        val gridLayout = layout as? GridLayoutManager
        val sameRow = target in 0 until count && (gridLayout == null ||
            gridLayout.spanSizeLookup.getSpanGroupIndex(position, gridLayout.spanCount) ==
                gridLayout.spanSizeLookup.getSpanGroupIndex(target, gridLayout.spanCount))
        val horizontalShelf = layout is LinearLayoutManager && layout.orientation == RecyclerView.HORIZONTAL
        if (gridLayout == null && !horizontalShelf) return false
        if (sameRow) {
            val revision = focusNavigationRevision
            horizontalFocus.request(recycler, position = { target }, isCurrent = { revision == focusNavigationRevision }) {
                it.requestFocus()
            }
        } else if (!right && (gridLayout != null || position == 0)) {
            selectedTabView().requestFocus()
        }
        // At a row's right edge, keep focus in that row. Android's spatial search
        // can otherwise choose a card above it while the next item is attaching.
        return true
    }

    private fun createAdapters() {
        val environment = cardEnvironment()
        val focusListener: (LibraryFocusTarget, LibRow) -> Unit = { target, row ->
            focusByDestination[focusStateKey()] = target
            updateLibraryHero(row)
        }
        libraryAdapter = LibraryAdapter(
            environment,
            onMedia =(::openMedia),
            onQuickPlay =(::playNow),
            onSeries =(::openSeries),
            onHeaderToggle =(::togglePhotoFolder),
            onFocused = focusListener,
            onAlbum = ::openAlbum,
            onCollection = ::openCollection,
            onLongPress = ::openRowActions,
        )
        homeAdapter = HomeShelfAdapter(
            environment,
            onMedia =(::openMedia),
            onQuickPlay =(::playNow),
            onSeries =(::openSeries),
            onFocused = focusListener,
            onAlbum = ::openAlbum,
            onCollection = ::openCollection,
            onLongPress = ::openRowActions,
        )
    }

    private fun restoreSavedFocus(state: Bundle?) {
        state ?: return
        val destination = state.getString(STATE_FOCUS_DESTINATION) ?: return
        val row = state.getString(STATE_FOCUS_ROW) ?: return
        focusByDestination[destination] =
            LibraryFocusTarget(state.getString(STATE_FOCUS_SHELF), row)
        restoreSavedFocusOnNextContent = true
    }

    private fun isInGrid(view: View): Boolean {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent === grid) return true
            parent = parent.parent
        }
        return false
    }

    private fun openSetup() =
        setupLauncher.launch(Intent(this, ServerSetupActivity::class.java))

    private fun bindTabs() {
        allDestinations().forEach { (viewId, kind) ->
            findViewById<Button>(viewId).apply {
                setOnClickListener { selectDestination(kind) }
            }
        }
        updateTabSelection()
    }

    /** Phone-only swipe navigation; D-pad navigation remains unchanged on television. */
    private fun bindMobileCategorySwipe() {
        val isTelevision = isTelevision() || resources.getBoolean(R.bool.show_library_hero)
        if (isTelevision) {
            (swipe.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = (resources.displayMetrics.density * TV_CONTENT_TOP_MARGIN_DP).toInt()
                swipe.layoutParams = params
            }
            return
        }

        swipeHint.visibility = View.GONE
        val minimumDistance = resources.displayMetrics.density * CATEGORY_SWIPE_DISTANCE_DP
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(
                start: MotionEvent?,
                end: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                start ?: return false
                val deltaX = end.x - start.x
                val deltaY = end.y - start.y
                if (abs(deltaX) < minimumDistance || abs(deltaX) <= abs(deltaY)) return false
                moveToAdjacentDestination(if (deltaX < 0) 1 else -1)
                return true
            }
        })
        grid.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    /** Les écrans TV ont besoin d'une navigation permanente et verticale ; elle évite
     * que la barre de catégories concurrence le catalogue en haut de l'écran. */
    private fun configureTvNavigationRail() {
        if (!(isTelevision() || resources.getBoolean(R.bool.show_library_hero))) return
        val density = resources.displayMetrics.density
        findViewById<View>(R.id.topBar).background = null
        findViewById<View>(R.id.swipeHint).visibility = View.GONE

        // Le défilement horizontal est réservé au téléphone. Le rail TV garde des
        // enfants directs pour préserver les déplacements haut/bas à la télécommande.
        val navigation = findViewById<LinearLayout>(R.id.categoryNavigation)
        val scroller = findViewById<HorizontalScrollView>(R.id.categoryScroller)
        val tabs = findViewById<LinearLayout>(R.id.tabs)
        scroller.removeView(tabs)
        navigation.removeView(scroller)
        navigation.addView(tabs)
        findViewById<LinearLayout>(R.id.categoryNavigation).apply {
            background = getDrawable(R.drawable.bg_glass_nav_rail)
            layoutParams = FrameLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.library_rail_width),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP,
            ).apply {
                topMargin = (104 * density).toInt()
                leftMargin = (48 * density).toInt()
            }
            elevation = 0f
            setPadding(0, 0, 0, 0)
        }
        findViewById<LinearLayout>(R.id.tabs).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Toutes les destinations doivent recevoir le format vertical, même si leur
        // contenu arrive après le premier chargement réseau et qu'elles étaient cachées.
        allDestinations().forEach { (id, _) ->
            findViewById<Button>(id).apply {
                minHeight = (48 * density).toInt()
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 0, 0) }
            }
        }
        val start = resources.getDimensionPixelSize(R.dimen.library_rail_content_start)
        listOf(findViewById<View>(R.id.swipe), libraryHero, findViewById<View>(R.id.libraryContext)).forEach { view ->
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = start
                view.layoutParams = params
            }
        }
        (findViewById<View>(R.id.libraryContext).layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = (94 * density).toInt()
        }
    }

    private fun configureMobileNavigationInsets() {
        if (isTelevision() || resources.getBoolean(R.bool.show_library_hero)) return
        val navigation = findViewById<View>(R.id.categoryNavigation)
        navigation.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val density = resources.displayMetrics.density
            val bottom = (navigation.layoutParams as ViewGroup.MarginLayoutParams).topMargin + navigation.height
            listOf(findViewById<View>(R.id.libraryContext), swipeHint).forEach { view ->
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                val margin = bottom + (8 * density).toInt()
                if (params.topMargin != margin) {
                    params.topMargin = margin
                    view.layoutParams = params
                }
            }
            updateLibraryHeroVisibility()
        }
    }

    private fun mobileContentTopMarginPx(): Int {
        val density = resources.displayMetrics.density
        val navigation = findViewById<View>(R.id.categoryNavigation)
        val top = (navigation.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val height = navigation.height.takeIf { it > 0 } ?: (56 * density).toInt()
        val context = findViewById<TextView>(R.id.libraryContext)
        val contextHeight = if (context.visibility == View.VISIBLE) context.lineHeight + (8 * density).toInt() else 0
        return top + height + contextHeight + (8 * density).toInt()
    }

    private fun moveToAdjacentDestination(offset: Int) {
        val destinations = destinations()
        val currentIndex = destinations.indexOfFirst { it.second == selectedKind }
        val target = destinations.getOrNull(currentIndex + offset) ?: return
        selectDestination(target.second)
        swipeHint.animate()
            .alpha(0f)
            .setDuration(SWIPE_HINT_DISMISS_MS)
            .withEndAction { swipeHint.visibility = View.GONE }
            .start()
    }

    private fun selectDestination(kind: String) {
        if (searchQuery.isNotBlank()) clearSearch(updateContent = false)
        if (selectedKind == kind) {
            loadItems()
            return
        }
        selectedKind = kind
        selectedCollection = null
        LibraryPreferences.rememberLibrary(this, kind)
        PluxyApplication.setLibraryTab(this, kind)
        updateTabSelection()
        updateLibraryHeroVisibility()
        val restore = focusByDestination.containsKey(focusStateKey())
        loadItems()
        if (!restore) grid.scrollToPosition(0)
    }

    private fun destinations(): List<Pair<Int, String>> = listOf(
        R.id.tabHome to "home",
        R.id.tabMovies to "movie",
        R.id.tabSeries to "series",
        R.id.tabVideos to "clip",
        R.id.tabPhotos to "photo",
        R.id.tabAudio to "audio",
    ).filter { (_, kind) -> kind == "home" || kind in discoveredKinds }

    private fun allDestinations(): List<Pair<Int, String>> = listOf(
        R.id.tabHome to "home",
        R.id.tabMovies to "movie",
        R.id.tabSeries to "series",
        R.id.tabVideos to "clip",
        R.id.tabPhotos to "photo",
        R.id.tabAudio to "audio",
    )

    private fun updateTabSelection() {
        allDestinations().forEach { (viewId, kind) ->
            findViewById<View>(viewId).visibility =
                if (kind == "home" || kind in discoveredKinds) View.VISIBLE else View.GONE
            findViewById<View>(viewId).isSelected = selectedKind == kind
        }
        if (!usesRail()) {
            findViewById<View>(R.id.categoryNavigation).visibility = View.GONE
        }
    }

    private fun selectedTabView(): View = if (!usesRail()) findViewById(R.id.brand) else findViewById(
        destinations().firstOrNull { it.second == selectedKind }?.first ?: R.id.tabHome,
    )

    private fun updateInterfaceMode() {
        // La bibliothèque garde une navigation unique, sans mode technique caché.
        advancedMode = false
        findViewById<View>(R.id.serverLabel).visibility =
            if (isTelevision()) View.INVISIBLE else View.GONE
        swipe.isEnabled = true
        val density = resources.displayMetrics.density
        val width = resources.displayMetrics.widthPixels / density
        val safe = resources.getDimension(R.dimen.screen_pad) / density
        val available = width - if (usesRail()) 192f + safe else safe * 2
        val card = resources.getDimension(R.dimen.library_grid_card_width) / density *
            if (LibraryPreferences.compact(this)) 1f else 1.25f
        gridSpan = (available / (card + 12f)).toInt().coerceIn(2, 8)
        updateLibraryHeroVisibility()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        if (advancedMode || !PluxyApplication.isConfigured(this)) return
        autoRefreshJob = lifecycleScope.launch {
            var apiUrl = PluxyApplication.serverBaseUrl(this@MainActivity)
            var api = withContext(Dispatchers.IO) { PluxyApi(apiUrl, timeoutMs = ServerAvailability.TIMEOUT_MS) }
            var sawScan = false
            try {
                while (isActive) {
                    delay(AUTO_REFRESH_MS)
                    if (reconnectJob?.isActive == true || serverChoiceDialog?.isShowing == true) continue
                    val currentUrl = PluxyApplication.serverBaseUrl(this@MainActivity)
                    if (currentUrl != apiUrl) {
                        api.close()
                        apiUrl = currentUrl
                        api = withContext(Dispatchers.IO) { PluxyApi(apiUrl, timeoutMs = ServerAvailability.TIMEOUT_MS) }
                    }
                    val status = networkOrNull { api.scanStatus() }
                    if (!isCurrentServer(api.baseUrl)) continue
                    if (status == null) {
                        requestReconnect(api.baseUrl)
                        continue
                    }
                    if (status.scanning) {
                        sawScan = true
                    } else if (loadJob?.isActive != true && (!loadedOnce || sawScan || (serverCatalogueCount >= 0 && status.count != serverCatalogueCount))) {
                        sawScan = false
                        fetchAndShow(api.baseUrl, showBlockingState = false)
                    }
                }
            } finally {
                api.close()
            }
        }
    }

    private fun refresh() {
        if (!PluxyApplication.isConfigured(this)) {
            openSetup()
            return
        }
        swipe.isRefreshing = true
        loadJob?.cancel()
        val serverUrl = PluxyApplication.serverBaseUrl(this)
        loadJob = lifecycleScope.launch {
            val api = withContext(Dispatchers.IO) { PluxyApi(serverUrl) }
            try {
                networkOrNull { api.rescan() }
                CatalogueRepository.invalidate(api.abs("/"))
                for (attempt in 0 until 10) {
                    fetchAndShow(serverUrl, showBlockingState = false)
                    val scanning = networkOrNull { api.scanStatus().scanning } ?: false
                    if (!scanning || attempt == 9) break
                    delay(1_000)
                }
            } finally {
                api.close()
                swipe.isRefreshing = false
            }
        }
    }

    private fun loadItems() {
        if (!PluxyApplication.isConfigured(this)) { showNotConfigured(); return }
        loadJob?.cancel()
        pageJob?.cancel()
        val serverUrl = PluxyApplication.serverBaseUrl(this)
        loadJob = lifecycleScope.launch { fetchAndShow(serverUrl, showBlockingState = !loadedOnce) }
    }

    private fun currentQuery(): TvLibraryContent.Query = LibraryPreferences.query(
        this, if (searchQuery.isNotBlank()) searchScope else selectedKind,
    ).copy(
        destination = selectedKind, search = searchQuery, searchScope = searchScope,
        photoFolder = if (selectedKind == "photo") PluxyApplication.photoFolder(this) else "",
        collapsedPhotoFolders = collapsedPhotoFolders.toSet(),
    )

    private fun usesRail(): Boolean = isTelevision() || resources.getBoolean(R.bool.show_library_hero)

    private fun viewMode(): String = if (searchQuery.isNotBlank() || selectedCollection != null) "library"
        else LibraryPreferences.view(this, selectedKind)

    private fun queryKey(): String = moshi.adapter(TvLibraryContent.Query::class.java).toJson(currentQuery()) +
        ":${viewMode()}:${selectedCollection?.id.orEmpty()}"

    private fun serverQuery(query: TvLibraryContent.Query = currentQuery()): LibraryRequest = LibraryRequest(
        kind = if (query.search.isNotBlank()) query.searchScope else query.destination,
        search = query.search, watched = query.watchedFilter, genres = query.genres, year = query.year,
        durationMaxMinutes = query.durationMaxMinutes, resolution = query.resolution, hdrOnly = query.hdrOnly,
        sort = query.sort, descending = query.descending,
        titlePrefix = query.titlePrefix,
    )

    private suspend fun loadCatalogue(serverUrl: String, query: TvLibraryContent.Query, view: String, collection: LibraryCollection?): BrowserSnapshot {
        val api = PluxyApi(serverUrl)
        try {
            val facetKind = if (query.search.isNotBlank()) query.searchScope else query.destination
            val available = networkOrNull { api.libraryFacets(facetKind.takeIf { it != "home" } ?: "all") } ?: LibraryFacets()
            if (!available.querySupported) return BrowserSnapshot(api.listItems(), 0, facets = available)
            if (query.destination == "home" && query.search.isBlank()) {
                val overview = api.libraryOverview()
                return BrowserSnapshot(overview.items, overview.items.size, facets = available)
            }
            if (view == "collections" || collection != null) {
                val kind = if (collection?.smartQuery != null) collection.smartQuery.destination else "all"
                val items = api.listItems(LibraryRequest(kind = kind))
                return BrowserSnapshot(items, items.size, facets = available)
            }
            if (query.destination in setOf("movie", "clip") && (query.search.isBlank() || query.searchScope == query.destination)) {
                val request = serverQuery(query)
                if (view == "discover" && query.search.isBlank()) {
                    val recent = api.libraryPage(request.copy(sort = "added", descending = true))
                    val resume = api.libraryPage(request.copy(watched = "in_progress", sort = "added", descending = true))
                    return BrowserSnapshot((resume.items + recent.items).distinctBy { it.id }, recent.total, facets = available)
                }
                val keep = if (catalogueQueryKey == queryKey() && pagedMovies) allItems.size.coerceAtLeast(100) else 100
                val items = mutableListOf<MediaItem>()
                var next: Int? = 0
                var revision: String? = null
                var total = 0
                while (next != null && items.size < keep) {
                    val page = api.libraryPage(request, next, revision)
                    validatePage(page, items.size, revision, if (revision != null) total else null)
                    items.addAll(page.items)
                    total = page.total; next = page.nextOffset; revision = page.revision
                }
                return BrowserSnapshot(items, total, next, revision, available, paged = true)
            }
            // Série et album restent atomiques : tous leurs épisodes ou pistes sont regroupés.
            val request = if (query.destination in setOf("series", "audio", "photo") && query.search.isBlank()) {
                LibraryRequest(kind = query.destination)
            } else serverQuery(query).copy(watched = "all", genres = emptySet(), year = null,
                durationMaxMinutes = null, resolution = "all", hdrOnly = false)
            val items = api.listItems(request)
            return BrowserSnapshot(items, items.size, facets = available)
        } finally { api.close() }
    }

    private fun validatePage(page: com.pluxy.tv.api.LibraryPage, offset: Int, revision: String?, total: Int?) {
        if (page.total !in 0..100_000 || page.items.size > 100 || page.revision.length !in 1..256 ||
            (revision != null && (page.revision != revision || page.total != total)) ||
            page.items.map { it.id }.distinct().size != page.items.size ||
            (page.nextOffset != null && (page.items.isEmpty() || page.nextOffset != offset + page.items.size || page.nextOffset >= page.total)) ||
            (page.nextOffset == null && offset + page.items.size != page.total)) {
            throw ApiException(409, "La bibliothèque a changé pendant le chargement")
        }
    }

    private suspend fun fetchAndShow(serverUrl: String, showBlockingState: Boolean): Boolean {
        val requestRevision = ++fetchRevision
        val navigationAtRequest = focusNavigationRevision
        val restoreAtRequest = restoreSavedFocusOnNextContent || grid.hasFocus()
        val firstAtRequest = !loadedOnce
        val key = queryKey()
        val query = currentQuery()
        val view = viewMode()
        val collection = selectedCollection
        if (showBlockingState) showLoading(true)
        return try {
            var fresh: BrowserSnapshot? = null
            repeat(2) { attempt ->
                if (fresh == null) try { fresh = loadCatalogue(serverUrl, query, view, collection) }
                catch (error: ApiException) { if (error.code != 409 || attempt == 1) throw error }
            }
            val snapshot = requireNotNull(fresh)
            if (!isCurrentServer(serverUrl) || requestRevision != fetchRevision || key != queryKey()) return false
            allItems = snapshot.items
            pageTotal = snapshot.total
            nextPageOffset = snapshot.next
            pageRevision = snapshot.revision
            pagedMovies = snapshot.paged
            facets = snapshot.facets
            serverCatalogueCount = facets.counts.values.sum().takeIf { it > 0 } ?: allItems.size
            catalogueServerUrl = serverUrl
            catalogueQueryKey = key
            discoveredKinds += facets.counts.filterValues { it > 0 }.keys + allItems.map { it.kind }
            updateAvailableDestinations()
            loadedOnce = true
            applyAndShow(restoreFocus = restoreAtRequest && navigationAtRequest == focusNavigationRevision,
                focusFirst = firstAtRequest && usesRail() && navigationAtRequest == focusNavigationRevision)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (allItems.isEmpty() && isCurrentServer(serverUrl) && requestRevision == fetchRevision) showServerError()
            else if (key != catalogueQueryKey) {
                showEmpty("Impossible de charger cette bibliothèque.", "Réessayer", false) { loadItems() }
            }
            if (isCurrentServer(serverUrl) && requestRevision == fetchRevision && ServerReconnectPolicy.isConnectionFailure(error)) {
                requestReconnect(serverUrl)
            }
            false
        } finally { if (requestRevision == fetchRevision) showLoading(false) }
    }

    private fun loadNextPage() {
        val offset = nextPageOffset ?: return
        if (pageJob?.isActive == true || loadJob?.isActive == true) return
        val key = catalogueQueryKey
        val server = catalogueServerUrl ?: return
        val request = serverQuery()
        val revision = pageRevision
        pageJob = lifecycleScope.launch {
            val api = PluxyApi(server)
            try {
                val page = api.libraryPage(request, offset, revision)
                if (key != queryKey() || !isCurrentServer(server) || pageRevision != revision) return@launch
                validatePage(page, offset, revision, pageTotal)
                val known = allItems.map { it.id }.toHashSet()
                if (page.items.any { it.id in known }) throw ApiException(409, "Page incohérente")
                allItems = allItems + page.items
                nextPageOffset = page.nextOffset
                applyAndShow()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (key == queryKey()) {
                    if (error is ApiException && error.code == 409) loadItems()
                    else android.widget.Toast.makeText(this@MainActivity, "Chargement interrompu · faites défiler pour réessayer", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (isCurrentServer(server) && ServerReconnectPolicy.isConnectionFailure(error)) requestReconnect(server)
            } finally { api.close() }
        }
    }

    private fun refreshQuietly() { loadItems() }

    private fun applyAndShow(
        restoreFocus: Boolean =
            restoreSavedFocusOnNextContent || currentFocus?.let(::isInGrid) == true,
        focusFirst: Boolean = false,
    ) {
        val environment = cardEnvironment()
        libraryAdapter.updateEnvironment(environment)
        homeAdapter.updateEnvironment(environment)
        updateLibraryContext()
        updateLibraryHeroVisibility()
        contentJob?.cancel()
        val revision = ++contentRevision
        val navigationRevision = focusNavigationRevision
        val items = allItems
        val destination = selectedKind
        val focusKey = focusStateKey()
        val homeOrder = PluxyApplication.homeShelfOrder(this)
        val hiddenShelves = PluxyApplication.hiddenHomeShelves(this)
        val query = currentQuery()
        val view = viewMode()
        val collection = selectedCollection
        contentJob = lifecycleScope.launch {
            if ((destination == "home" || (destination in setOf("movie", "series", "clip") && view == "discover")) && query.search.isBlank() && collection == null) {
                val shelves = withContext(Dispatchers.Default) {
                    if (destination == "home") TvLibraryContent.homeShelves(items, homeOrder, hiddenShelves)
                    else TvLibraryContent.destinationShelves(items, query)
                }
                if (revision != contentRevision) return@launch
                if (shelves.isEmpty()) {
                    val focusAction = shouldFocusLibraryEmptyAction(restoreFocus, focusFirst,
                        navigationRevision != focusNavigationRevision, grid.hasFocus(), currentFocus != null)
                    if (destination == "home" && hiddenShelves.isNotEmpty() &&
                        items.any { it.kind in setOf("movie", "series", "clip") }
                    ) {
                        showEmpty("Les rubriques de l’accueil sont masquées.", "Restaurer les rubriques", focusAction) {
                            PluxyApplication.saveHomeShelves(this@MainActivity, homeOrder, emptySet())
                            applyAndShow(focusFirst = true)
                        }
                    } else if (query.watchedFilter != "all" && destination in setOf("movie", "series", "clip")) {
                        showEmpty(emptyMessage(), "Afficher tous les contenus", focusAction) {
                            saveBrowserQuery(currentQuery().copy(watchedFilter = "all"))
                        }
                    } else if (destination == "home" && discoveredKinds.any { it in setOf("photo", "audio") }) {
                        showEmpty("Vos photos et votre musique sont dans Bibliothèque.", "Ouvrir la bibliothèque", focusAction) { openLastLibrary() }
                    } else showEmpty(emptyMessage(), focusAction = focusAction)
                    return@launch
                }
                renderBrowser()
                showGrid()
                if (grid.adapter !== homeAdapter) {
                    grid.layoutManager = LinearLayoutManager(this@MainActivity)
                    grid.adapter = homeAdapter
                }
                homeAdapter.submitList(shelves) {
                    if (revision != contentRevision) return@submitList
                    val target = focusByDestination[focusKey]
                    when (libraryFocusIntent(restoreFocus, focusFirst, target != null,
                        navigationRevision != focusNavigationRevision, grid.hasFocus())) {
                        LibraryFocusIntent.RESTORE -> target?.let { homeAdapter.restoreFocus(grid, it) }
                        LibraryFocusIntent.FIRST -> homeAdapter.restoreFocus(grid, LibraryFocusTarget(null, ""))
                        LibraryFocusIntent.KEEP -> Unit
                    }
                    restoreSavedFocusOnNextContent = false
                }
                updateLibraryHero(shelves.firstOrNull()?.rows?.firstOrNull())
                return@launch
            }

            val (rows, filterHidesResults) = withContext(Dispatchers.Default) {
                val result = when {
                    collection != null -> TvLibraryContent.collectionContentRows(items, collection)
                    view == "collections" && query.search.isBlank() -> TvLibraryContent.collectionRows(items, LibraryPreferences.collections(this@MainActivity))
                    else -> TvLibraryContent.rows(items, query)
                }
                result to (result.isEmpty() && query.search.isNotBlank() && query.watchedFilter != "all" &&
                    TvLibraryContent.rows(items, query.copy(watchedFilter = "all")).isNotEmpty())
            }
            if (revision != contentRevision) return@launch
            renderBrowser(rows)
            if (rows.isEmpty()) {
                if (view == "collections" && collection == null) {
                    showEmpty("Vos sélections de médias apparaîtront ici.", "Créer une collection", false) { createCollection() }
                    return@launch
                }
                val focusAction = shouldFocusLibraryEmptyAction(restoreFocus, focusFirst,
                    navigationRevision != focusNavigationRevision, grid.hasFocus(), currentFocus != null)
                if (filterHidesResults || LibraryBrowserChrome.activeFilters(query).isNotEmpty()) showEmpty("Aucun média ne correspond aux filtres.", "Réinitialiser les filtres", focusAction) {
                    saveBrowserQuery(query.copy(watchedFilter = "all", genres = emptySet(), year = null, durationMaxMinutes = null, resolution = "all", hdrOnly = false, titlePrefix = ""))
                } else showEmpty(emptyMessage(), focusAction = focusAction)
                return@launch
            }
            showGrid()
            val layout = (grid.layoutManager as? GridLayoutManager)
                ?.takeIf { it.spanCount == gridSpan }
                ?: GridLayoutManager(this@MainActivity, gridSpan)
            if (grid.layoutManager !== layout) grid.layoutManager = layout
            if (grid.adapter !== libraryAdapter) {
                grid.adapter = libraryAdapter
            }
            libraryAdapter.configureSpans(layout)
            libraryAdapter.submitList(rows) {
                if (revision != contentRevision) return@submitList
                val target = focusByDestination[focusKey]
                when (libraryFocusIntent(restoreFocus, focusFirst, target != null,
                    navigationRevision != focusNavigationRevision, grid.hasFocus())) {
                    LibraryFocusIntent.RESTORE -> target?.let { libraryAdapter.restoreFocus(grid, it.rowKey) }
                    LibraryFocusIntent.FIRST -> libraryAdapter.restoreFocus(grid, "")
                    LibraryFocusIntent.KEEP -> Unit
                }
                restoreSavedFocusOnNextContent = false
            }
            updateLibraryHero(rows.firstOrNull { it !is LibRow.Header })
        }
    }

    private fun updateAvailableDestinations() {
        val available = destinations().map { it.second }.toSet()
        if (selectedKind !in available) {
            selectedKind = "home"
            PluxyApplication.setLibraryTab(this, selectedKind)
        }
        updateTabSelection()
    }

    private fun togglePhotoFolder(header: LibRow.Header) {
        if (!header.collapsible) return
        focusByDestination[focusStateKey()] = LibraryFocusTarget(null, header.stableKey)
        val folder = header.id.removePrefix("photo:")
        if (!collapsedPhotoFolders.add(folder)) collapsedPhotoFolders.remove(folder)
        applyAndShow(restoreFocus = true)
    }

    /** Un aperçu ne s'affiche que sur les grands écrans : sur téléphone, il volerait
     * inutilement l'espace de la grille. */
    private fun updateLibraryHeroVisibility() {
        libraryHero.visibility = View.GONE
        if (::browserChrome.isInitialized) browserChrome.positionContent()
    }

    private fun updateLibraryHero(row: LibRow?) {
        if (libraryHero.visibility != View.VISIBLE || row == null) return
        val title: String
        val meta: String
        val image: String?
        when (row) {
            is LibRow.MediaRow -> {
                title = row.item.title
                meta = compactHeroMeta(row.item)
                image = row.item.thumbnailUrl
            }
            is LibRow.QuickPlayRow -> {
                title = row.item.title
                meta = row.subtitle.ifBlank { compactHeroMeta(row.item) }
                image = row.item.thumbnailUrl
            }
            is LibRow.SeriesRow -> {
                title = SeriesActivity.displayName(row.name)
                meta = "${row.count} ${if (row.count > 1) "épisodes" else "épisode"}"
                image = row.poster ?: row.thumbnail
            }
            is LibRow.Header, is LibRow.AlbumRow, is LibRow.CollectionRow -> return
        }
        libraryHeroEyebrow.text = if (selectedKind == "series") "SÉRIE SÉLECTIONNÉE" else "FILM SÉLECTIONNÉ"
        libraryHeroTitle.text = title
        libraryHeroMeta.text = meta
        image?.let { raw ->
            val url = runCatching {
                LanUrlPolicy.resolveSameServer(PluxyApplication.serverBaseUrl(this), raw)
            }.getOrNull() ?: return@let
            libraryHeroImage.load(url, lanImages)
        } ?: libraryHeroImage.setImageDrawable(null)
    }

    private fun compactHeroMeta(item: MediaItem): String = buildList {
        item.year?.let { add(it.toString()) }
        item.height?.let { add("${it}p") }
        item.duration.takeIf { it > 0 }?.let { seconds ->
            val minutes = (seconds / 60).toInt().coerceAtLeast(1)
            add("${minutes} min")
        }
        if (item.isHdr) add("HDR")
    }.joinToString(" · ").ifBlank { item.container.uppercase() }

    private fun configureCompactNavigation(screenDp: Float) {
        if (isTelevision() || resources.getBoolean(R.bool.show_library_hero)) return
        findViewById<View>(R.id.brandName).visibility = View.GONE
        if (!isTelevision()) {
            val density = resources.displayMetrics.density
            findViewById<ImageView>(R.id.brandLogo).apply {
                layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                    width = (24 * density).toInt()
                    height = (24 * density).toInt()
                    marginEnd = (8 * density).toInt()
                }
            }
            findViewById<Button>(R.id.photoFolders).apply {
                layoutParams = layoutParams.apply { width = ((if (screenDp < 340f) 96 else 112) * density).toInt() }
            }
            findViewById<Button>(R.id.sortFilter).apply {
                text = ""
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_library_sort, 0, 0, 0)
                compoundDrawablePadding = 0
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                (layoutParams as? ViewGroup.MarginLayoutParams)?.width = (48 * density).toInt()
            }
        }
        findViewById<Button>(R.id.tabAudio).text = getString(R.string.destination_music)
    }

    private fun cardEnvironment() = CardEnvironment(
        advancedMode, lanImages, photoFolderInHeader = selectedKind == "photo" && searchQuery.isBlank(),
        compact = LibraryPreferences.compact(this),
    )

    private fun focusStateKey(): String =
        "$selectedKind:${viewMode()}:${selectedCollection?.id.orEmpty()}" + if (searchQuery.isBlank()) "" else ":search"

    private fun showLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            grid.visibility = View.INVISIBLE
            empty.visibility = View.GONE
            findViewById<Button>(R.id.emptyAction).visibility = View.GONE
        }
    }

    private fun showGrid() {
        empty.visibility = View.GONE
        findViewById<Button>(R.id.emptyAction).visibility = View.GONE
        grid.visibility = View.VISIBLE
    }

    private fun showEmpty(
        message: String,
        action: String? = null,
        focusAction: Boolean = grid.hasFocus() || currentFocus == null,
        onAction: (() -> Unit)? = null,
    ) {
        val gridHadFocus = grid.hasFocus()
        grid.visibility = View.INVISIBLE
        empty.text = message
        empty.visibility = View.VISIBLE
        findViewById<Button>(R.id.emptyAction).apply {
            visibility = if (action != null) View.VISIBLE else View.GONE
            text = action
            setOnClickListener { onAction?.invoke() }
            if (action != null && focusAction) requestFocus()
        }
        if (gridHadFocus && action == null) selectedTabView().requestFocus()
        restoreSavedFocusOnNextContent = false
    }

    private fun showServerError() {
        grid.visibility = View.INVISIBLE
        empty.text = getString(
            R.string.server_unreachable,
            PluxyApplication.serverBaseUrl(this),
        )
        empty.visibility = View.VISIBLE
        findViewById<Button>(R.id.emptyAction).apply {
            setText(R.string.action_change_server)
            setOnClickListener { openSetup() }
            visibility = View.VISIBLE
            requestFocus()
        }
    }

    private fun showNotConfigured() = showEmpty(
        "Connectez cette TV à votre serveur Pluxy pour afficher la bibliothèque.",
        "Choisir un serveur",
        focusAction = true,
        onAction = ::openSetup,
    )

    private fun updateLibraryContext() {
        findViewById<View>(R.id.libraryContext).visibility = View.GONE
        findViewById<View>(R.id.sortFilter).visibility = View.GONE
        findViewById<View>(R.id.photoFolders).visibility = View.GONE
        renderBrowser()
    }

    private fun renderBrowser(rows: List<LibRow>? = null) {
        if (!::browserChrome.isInitialized) return
        val query = currentQuery()
        val total = when {
            selectedCollection != null -> TvLibraryContent.collectionContentRows(allItems, requireNotNull(selectedCollection)).count { it !is LibRow.Header }
            viewMode() == "collections" && searchQuery.isBlank() -> LibraryPreferences.collections(this).size
            pagedMovies || (selectedKind == "movie" && viewMode() == "discover") -> pageTotal
            rows != null && (selectedKind == "series" || searchQuery.isNotBlank()) -> rows.count { it !is LibRow.Header }
            selectedKind == "series" -> TvLibraryContent.rows(allItems, query).size
            else -> TvLibraryContent.filterItems(allItems, query).size
        }
        browserChrome.render(selectedKind, query, viewMode(), total, nextPageOffset != null, searchQuery, selectedCollection?.name)
    }

    private fun emptyMessage(): String = when {
        searchQuery.isNotBlank() -> "Aucun résultat pour « $searchQuery »."
        allItems.isEmpty() -> "Aucun média. Choisissez vos dossiers dans Pluxy Serveur."
        selectedKind == "home" ->
            "Aucun film ou épisode pour l'accueil. Photos et musique restent disponibles."
        selectedKind == "series" -> "Aucune série dans cette bibliothèque."
        selectedKind == "photo" -> "Aucune photo dans cette bibliothèque."
        selectedKind == "audio" -> "Aucune musique dans cette bibliothèque."
        else -> "Aucun film ne correspond à ce filtre."
    }

    private fun openSearch() {
        val input = EditText(this).apply {
            hint = "Titre, série, artiste ou album"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_SEARCH_CHARACTERS))
            setText(searchQuery)
            setSelection(text.length)
        }
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val scopes = listOf(
            "all" to "Tous les médias",
            "movie" to "Films",
            "series" to "Séries",
            "clip" to "Vidéos",
            "photo" to "Photos",
            "audio" to "Musique",
        )
        val scopeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        scopes.forEach { (value, label) ->
            scopeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = value
                isChecked = value == searchScope
                minHeight = (44 * density).toInt()
            })
        }
        panel.addView(scopeGroup)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rechercher")
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("Rechercher") { _, _ ->
                searchQuery = input.text.toString().trim()
                searchScope = scopeGroup.findViewById<RadioButton>(scopeGroup.checkedRadioButtonId)
                    ?.tag as? String ?: "all"
                updateSearchButton()
                selectedCollection = null
                loadItems()
            }
            .setNegativeButton("Annuler", null)
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        if (searchQuery.isNotBlank()) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Effacer") { _, _ ->
            clearSearch()
        }
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
    }

    private fun clearSearch(updateContent: Boolean = true) {
        searchQuery = ""
        searchScope = "all"
        updateSearchButton()
        if (updateContent) {
            loadItems()
        }
    }

    private fun updateSearchButton() {
        findViewById<ImageButton>(R.id.search).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = if (searchQuery.isBlank()) {
                "Rechercher dans tous les médias"
            } else {
                "Modifier la recherche $searchQuery (${searchScopeLabel()})"
            }
            isSelected = searchQuery.isNotBlank()
        }
    }

    private fun requestReconnect(originalUrl: String) {
        if (!isCurrentServer(originalUrl) || reconnectJob?.isActive == true || serverChoiceDialog?.isShowing == true) return
        val revision = ++reconnectRevision
        reconnectJob = lifecycleScope.launch {
            try {
                // Une erreur de catalogue ne signifie pas forcément que le serveur a disparu.
                if (ServerAvailability.probe(originalUrl) != null) {
                    if (isCurrentServer(originalUrl) && (!loadedOnce || catalogueQueryKey != queryKey())) loadItems()
                    return@launch
                }
                if (!isCurrentServer(originalUrl)) return@launch
                if (allItems.isEmpty()) showEmpty(getString(R.string.server_reconnecting), getString(R.string.action_change_server), false, ::openSetup)
                val servers = ServerAvailability.available(Discovery.discover(this@MainActivity, timeoutMs = DISCOVERY_WINDOW_MS))
                if (!isCurrentServer(originalUrl)) return@launch
                val single = ServerReconnectPolicy.select(servers)
                when {
                    single != null -> connectDiscoveredServer(single, originalUrl)
                    servers.isNotEmpty() -> showServerChoices(servers, originalUrl)
                    else -> dismissedServerChoices = null
                }
            } finally { if (revision == reconnectRevision) reconnectJob = null }
        }
    }

    private fun showServerChoices(servers: List<ServerInfo>, originalUrl: String, force: Boolean = false) {
        val signature = servers.map { it.baseUrl }.toSet()
        if (allItems.isEmpty()) showEmpty(getString(R.string.server_multiple_available), getString(R.string.action_change_server), false) {
            showServerChoices(servers, originalUrl, force = true)
        }
        if (!isCurrentServer(originalUrl) || serverChoiceDialog?.isShowing == true || (!force && dismissedServerChoices == signature)) return
        serverChoiceDialog = AlertDialog.Builder(this)
            .setTitle(R.string.action_change_server)
            .setItems(servers.map { getString(R.string.server_choice, it.name, it.ip, it.port) }.toTypedArray()) { _, position ->
                val server = servers[position]
                val revision = ++reconnectRevision
                reconnectJob?.cancel()
                reconnectJob = lifecycleScope.launch {
                    try {
                        val available = ServerAvailability.probe(server.baseUrl)
                        if (available != null) connectDiscoveredServer(available, originalUrl)
                        else {
                            dismissedServerChoices = null
                            android.widget.Toast.makeText(this@MainActivity, getString(R.string.server_connection_failed, server.baseUrl), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally { if (revision == reconnectRevision) reconnectJob = null }
                }
            }
            .setNegativeButton("Plus tard", null)
            .create().apply {
                setOnDismissListener {
                    dismissedServerChoices = signature
                    serverChoiceDialog = null
                }
                show()
                listView.requestFocus()
            }
    }

    private fun connectDiscoveredServer(server: ServerInfo, originalUrl: String) {
        if (!isCurrentServer(originalUrl)) return
        dismissedServerChoices = null
        if (server.baseUrl != originalUrl) {
            PluxyApplication.setServer(this, server.baseUrl, server.name)
            // Les identifiants, collections et filtres de deux bibliothèques peuvent se recouper.
            fetchRevision++
            contentRevision++
            contentJob?.cancel()
            pageJob?.cancel()
            loadJob?.cancel()
            allItems = emptyList()
            discoveredKinds.clear()
            focusByDestination.clear()
            collapsedPhotoFolders.clear()
            selectedCollection = null
            selectedKind = PluxyApplication.libraryTab(this)
            searchQuery = ""
            searchScope = "all"
            catalogueServerUrl = null
            catalogueQueryKey = ""
            nextPageOffset = null
            pageRevision = null
            pageTotal = 0
            serverCatalogueCount = -1
            facets = LibraryFacets()
            loadedOnce = false
            lanImages = LanMediaImages.loader(this)
            grid.visibility = View.INVISIBLE
            homeAdapter.submitList(emptyList())
            libraryAdapter.submitList(emptyList())
            updateSearchButton()
            updateAvailableDestinations()
            selectedTabView().requestFocus()
        }
        android.widget.Toast.makeText(this, getString(R.string.server_connected, server.name), android.widget.Toast.LENGTH_SHORT).show()
        loadItems()
    }

    private fun isCurrentServer(serverUrl: String): Boolean =
        serverUrl.trimEnd('/') == PluxyApplication.serverBaseUrl(this).trimEnd('/')

    /** Sélecteur compact : toutes les photos sont visibles tant qu'aucun dossier
     * n'est choisi. Le panneau se referme immédiatement après la sélection. */
    private fun updatePhotoFolderControl() {
        val control = findViewById<Button>(R.id.photoFolders)
        val photosTab = findViewById<Button>(R.id.tabPhotos)
        val home = findViewById<Button>(R.id.tabHome)
        val search = findViewById<ImageButton>(R.id.search)
        val sort = findViewById<Button>(R.id.sortFilter)
        val settings = findViewById<ImageButton>(R.id.settings)
        val show = selectedKind == "photo" && searchQuery.isBlank() &&
            allItems.any { it.kind == "photo" }
        control.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.brand).visibility = View.VISIBLE
        if (show) {
            val folder = PluxyApplication.photoFolder(this)
            control.text = folder.ifBlank { getString(R.string.photo_album_menu) }
            control.contentDescription = getString(
                R.string.photo_folder_control, folder.ifBlank { getString(R.string.photo_folder_all) },
            )
            control.nextFocusDownId = R.id.tabHome
            control.nextFocusLeftId = R.id.tabHome
            control.nextFocusRightId = R.id.search
        }

        // Rail vertical : on parcourt toujours ses rubriques avant de remonter vers l'en-tête.
        photosTab.nextFocusUpId = View.NO_ID
        home.nextFocusUpId = if (show) R.id.photoFolders else R.id.search
        search.nextFocusDownId = R.id.tabHome
        search.nextFocusLeftId = if (show) R.id.photoFolders else R.id.tabHome
        search.nextFocusRightId = if (sort.visibility == View.VISIBLE) R.id.sortFilter else R.id.settings
        sort.nextFocusLeftId = R.id.search
        sort.nextFocusRightId = R.id.settings
        sort.nextFocusDownId = R.id.tabHome
        settings.nextFocusDownId = R.id.tabHome
        settings.nextFocusLeftId = if (sort.visibility == View.VISIBLE) R.id.sortFilter else R.id.search
    }

    private fun openPhotoFolders() {
        val photos = allItems.filter { it.kind == "photo" }
        val folders = photos.groupingBy(TvLibraryContent::photoFolderLabel).eachCount()
            .toSortedMap(compareBy(LibraryOrganizer::searchKey))
        val choices = listOf(
            CompactChoiceMenu.Choice(getString(R.string.photo_folder_all), "", "${photos.size} photos", inlineDetail = true),
        ) + folders.map { (folder, count) ->
            CompactChoiceMenu.Choice(folder, folder, "$count ${if (count == 1) "photo" else "photos"}", inlineDetail = true)
        }
        CompactChoiceMenu.show(
            activity = this,
            anchor = findViewById(R.id.photoFolders),
            title = getString(R.string.photo_album_menu),
            choices = choices,
            selectedValue = PluxyApplication.photoFolder(this),
            actions = listOf(
                CompactChoiceMenu.Action(getString(R.string.photo_collapse_all)) {
                    collapsedPhotoFolders.clear()
                    collapsedPhotoFolders += PhotoFolderTree.rows(photos).filterIsInstance<LibRow.Header>().map { it.id }
                    applyAndShow(restoreFocus = false)
                },
                CompactChoiceMenu.Action(getString(R.string.photo_expand_all)) {
                    collapsedPhotoFolders.clear()
                    applyAndShow(restoreFocus = false)
                },
            ),
            onSelect = { folder ->
                PluxyApplication.setPhotoFolder(this, folder)
                        applyAndShow(restoreFocus = false)
            },
            onDismiss = ::restorePhotoFolderFocus,
        )
    }

    private fun restorePhotoFolderFocus() {
        findViewById<Button>(R.id.photoFolders).post {
            findViewById<Button>(R.id.photoFolders).requestFocus()
        }
    }

    private fun searchScopeLabel(): String = when (searchScope) {
        "movie" -> "films"
        "series" -> "séries"
        "clip" -> "vidéos"
        "photo" -> "photos"
        "audio" -> "musique"
        else -> "tous les médias"
    }

    private fun isTelevision(): Boolean = resources.configuration.uiMode and
        Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    private fun openSortFilter(anchor: View) {
        val query = currentQuery()
        val options = buildList {
            add("Titre" to "title"); add("Ajouts récents" to "added")
            if (selectedKind == "movie" || searchQuery.isNotBlank()) { add("Année" to "year"); add("Durée" to "duration") }
            if (selectedKind == "photo" && searchQuery.isBlank()) add("Prise de vue" to "captured")
        }
        CompactChoiceMenu.show(this, anchor, "Trier par",
            options.map { CompactChoiceMenu.Choice(it.first, it.second) }, query.sort,
            actions = buildList {
                add(CompactChoiceMenu.Action("Inverser l’ordre") {
                    saveBrowserQuery(query.copy(descending = !(query.descending ?: (query.sort != "title"))))
                })
                if (selectedKind == "movie" && searchQuery.isBlank()) add(CompactChoiceMenu.Action("Première lettre…") {
                    val letters = listOf("Tous les titres") + ('A'..'Z').map(Char::toString)
                    AlertDialog.Builder(this@MainActivity).setTitle("Première lettre")
                        .setSingleChoiceItems(letters.toTypedArray(), letters.indexOf(query.titlePrefix.uppercase()).coerceAtLeast(0)) { dialog, index ->
                            dialog.dismiss()
                            saveBrowserQuery(query.copy(titlePrefix = if (index == 0) "" else letters[index], sort = "title", descending = false))
                        }.setNegativeButton("Annuler", null).show()
                })
            }, onSelect = { saveBrowserQuery(query.copy(sort = it, descending = null)) })
    }

    private fun effectiveSort(): String = currentQuery().sort

    private fun openLibraryDestinations() {
        val kinds = listOf("Films" to "movie", "Séries" to "series", "Vidéos" to "clip", "Photos" to "photo", "Musique" to "audio")
            .filter { discoveredKinds.isEmpty() || it.second in discoveredKinds }
        CompactChoiceMenu.show(this, findViewById(R.id.brand), "Bibliothèque",
            kinds.map { CompactChoiceMenu.Choice(it.first, it.second) }, selectedKind,
            onSelect = { selectDestination(it) })
    }

    private fun openLastLibrary() {
        val previous = LibraryPreferences.lastLibrary(this)
        selectDestination(if (previous in discoveredKinds || discoveredKinds.isEmpty()) previous
            else listOf("movie", "series", "clip", "photo", "audio").firstOrNull { it in discoveredKinds } ?: "movie")
    }

    private fun openLibraryViews(anchor: View) {
        val query = currentQuery()
        val choices = when (selectedKind) {
            "photo" -> listOf(CompactChoiceMenu.Choice("Dossiers", "folders"), CompactChoiceMenu.Choice("Albums", "albums"), CompactChoiceMenu.Choice("Chronologie", "timeline"))
            "audio" -> listOf(CompactChoiceMenu.Choice("Albums", "library"), CompactChoiceMenu.Choice("Collections", "collections"))
            else -> listOf(CompactChoiceMenu.Choice("Découvrir", "discover"), CompactChoiceMenu.Choice("Bibliothèque", "library"), CompactChoiceMenu.Choice("Collections", "collections"))
        }
        CompactChoiceMenu.show(this, anchor, "Affichage", choices,
            if (selectedKind == "photo") query.photoView else viewMode(),
            onSelect = { value ->
                focusByDestination.remove(focusStateKey())
                selectedCollection = null
                if (selectedKind == "photo") LibraryPreferences.saveQuery(this, query.copy(photoView = value))
                else LibraryPreferences.saveView(this, selectedKind, value)
                focusByDestination.remove(focusStateKey())
                restoreSavedFocusOnNextContent = false
                grid.scrollToPosition(0)
                loadItems()
            })
    }

    private fun openLibraryFilters() = LibraryFilterDialog.show(this,
        currentQuery().let { if (searchQuery.isNotBlank()) it.copy(destination = searchScope) else it },
        facets, ::saveBrowserQuery)

    private fun saveBrowserQuery(query: TvLibraryContent.Query) {
        LibraryPreferences.saveQuery(this, if (searchQuery.isNotBlank()) query.copy(destination = searchScope) else query)
        selectedCollection = null
        focusByDestination.remove(focusStateKey())
        grid.scrollToPosition(0)
        loadItems()
    }

    private fun removeLibraryFilter(key: String) {
        val query = currentQuery()
        saveBrowserQuery(when {
            key == "watched" -> query.copy(watchedFilter = "all")
            key.startsWith("genre:") -> query.copy(genres = query.genres - key.removePrefix("genre:"))
            key == "year" -> query.copy(year = null)
            key == "duration" -> query.copy(durationMaxMinutes = null)
            key == "resolution" -> query.copy(resolution = "all")
            key == "hdr" -> query.copy(hdrOnly = false)
            key == "letter" -> query.copy(titlePrefix = "")
            else -> query
        })
    }

    private fun openAlbum(album: LibRow.AlbumRow) {
        startActivity(Intent(this, AlbumActivity::class.java)
            .putExtra(AlbumActivity.EXTRA_KEY, album.key).putExtra(AlbumActivity.EXTRA_KIND, album.kind)
            .putExtra(AlbumActivity.EXTRA_TITLE, album.name)
            .putExtra(AlbumActivity.EXTRA_COLLECTION_ID, selectedCollection?.id.orEmpty())
            .putExtra(AlbumActivity.EXTRA_DENSITY, if (LibraryPreferences.compact(this)) "compact" else "comfort"))
    }

    private fun openCollection(row: LibRow.CollectionRow) {
        selectedCollection = row.collection
        grid.scrollToPosition(0)
        applyAndShow(restoreFocus = false, focusFirst = usesRail())
    }

    private fun createCollection() {
        selectedCollection?.let {
            openRowActions(LibRow.CollectionRow(it, TvLibraryContent.collectionItems(allItems, it), null))
            return
        }
        val names = arrayOf("Choisir les médias", "Enregistrer les filtres actuels")
        AlertDialog.Builder(this).setTitle("Nouvelle collection").setItems(names) { _, choice ->
            val input = EditText(this).apply {
                hint = "Nom de la collection"
                setSingleLine(true)
                filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            }
            val dialog = AlertDialog.Builder(this).setTitle("Nommer la collection").setView(input)
                .setPositiveButton("Continuer", null).setNegativeButton("Annuler", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isBlank()) { input.error = "Choisissez un nom"; return@setOnClickListener }
                    dialog.dismiss()
                    val collection = LibraryCollection(java.util.UUID.randomUUID().toString(), name,
                        smartQuery = if (choice == 1) currentQuery().copy(search = "", searchScope = "all", collectionId = "") else null)
                    if (choice == 1) saveCollection(collection) else chooseCollectionMedia(collection)
                }
            }
            dialog.show()
        }.setNegativeButton("Annuler", null).show()
    }

    private fun saveCollection(collection: LibraryCollection) {
        LibraryPreferences.saveCollections(this, LibraryPreferences.collections(this).filterNot { it.id == collection.id } + collection)
        selectedCollection = null
        LibraryPreferences.saveView(this, selectedKind, "collections")
        loadItems()
    }

    private fun chooseCollectionMedia(collection: LibraryCollection) {
        val server = PluxyApplication.serverBaseUrl(this)
        lifecycleScope.launch {
            val api = PluxyApi(server)
            try {
                showLoading(true)
                val media = api.listItems(LibraryRequest(kind = selectedKind))
                if (!isCurrentServer(server) || isFinishing) return@launch
                val rows = if (selectedKind == "series") TvLibraryContent.rows(media, TvLibraryContent.Query("series", sort = "title"))
                    else media.sortedBy { LibraryOrganizer.searchKey(it.title) }.map { LibRow.MediaRow(it) }
                val selections = collection.mediaIds.toMutableSet()
                fun ids(row: LibRow): Set<String> = when (row) {
                    is LibRow.MediaRow -> setOf(row.item.id)
                    is LibRow.SeriesRow -> media.filter { mediaSeriesIdentity(it) == row.key }.map { it.id }.toSet()
                    else -> emptySet()
                }
                val labels = rows.map { row -> when (row) {
                    is LibRow.MediaRow -> listOfNotNull(LibraryOrganizer.displayTitle(row.item), row.item.year?.toString()).joinToString(" · ")
                    is LibRow.SeriesRow -> row.name
                    else -> ""
                } }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle(collection.name)
                    .setMultiChoiceItems(labels, rows.map { ids(it).any(selections::contains) }.toBooleanArray()) { _, index, checked ->
                        if (checked) selections.addAll(ids(rows[index])) else selections.removeAll(ids(rows[index]))
                    }.setPositiveButton("Enregistrer") { _, _ -> saveCollection(collection.copy(mediaIds = selections.toSet())) }
                    .setNegativeButton("Annuler", null).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@MainActivity, "Impossible de charger les médias", android.widget.Toast.LENGTH_SHORT).show() }
            finally { api.close(); showLoading(false) }
        }
    }

    private fun openRowActions(row: LibRow) {
        if (row is LibRow.CollectionRow) {
            AlertDialog.Builder(this).setTitle(row.collection.name)
                .setItems(arrayOf("Renommer", if (row.collection.smartQuery == null) "Modifier les médias" else "Modifier les filtres", "Supprimer la collection")) { _, action ->
                    when (action) {
                        0 -> {
                            val input = EditText(this).apply { setText(row.collection.name); setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
                            AlertDialog.Builder(this).setTitle("Renommer").setView(input).setPositiveButton("Enregistrer") { _, _ ->
                                input.text.toString().trim().takeIf { it.isNotBlank() }?.let { saveCollection(row.collection.copy(name = it)) }
                            }.setNegativeButton("Annuler", null).show()
                        }
                        1 -> if (row.collection.smartQuery == null) chooseCollectionMedia(row.collection)
                            else LibraryFilterDialog.show(this, row.collection.smartQuery, facets) { saveCollection(row.collection.copy(smartQuery = it)) }
                        2 -> AlertDialog.Builder(this).setTitle("Supprimer cette collection ?")
                            .setMessage("Les médias restent dans la bibliothèque.")
                            .setPositiveButton("Supprimer") { _, _ ->
                                LibraryPreferences.saveCollections(this, LibraryPreferences.collections(this).filterNot { it.id == row.collection.id }); applyAndShow()
                            }.setNegativeButton("Annuler", null).show()
                    }
                }.show()
            return
        }
        val ids = when (row) {
            is LibRow.MediaRow -> setOf(row.item.id)
            is LibRow.QuickPlayRow -> setOf(row.item.id)
            is LibRow.SeriesRow -> allItems.filter { mediaSeriesIdentity(it) == row.key }.map { it.id }.toSet()
            is LibRow.AlbumRow -> row.items.map { it.id }.toSet()
            else -> return
        }
        val collections = LibraryPreferences.collections(this).filter { it.smartQuery == null }
        val names = collections.map { it.name } + "Nouvelle collection"
        AlertDialog.Builder(this).setTitle("Ajouter à une collection").setItems(names.toTypedArray()) { _, index ->
            if (index < collections.size) {
                val collection = collections[index]
                saveCollection(collection.copy(mediaIds = collection.mediaIds + ids))
            } else {
                val input = EditText(this).apply { hint = "Nom"; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
                AlertDialog.Builder(this).setTitle("Nouvelle collection").setView(input).setPositiveButton("Créer") { _, _ ->
                    input.text.toString().trim().takeIf { it.isNotBlank() }?.let { saveCollection(LibraryCollection(java.util.UUID.randomUUID().toString(), it, ids)) }
                }.setNegativeButton("Annuler", null).show()
            }
        }.setNegativeButton("Annuler", null).show()
    }

    private fun openSeries(series: LibRow.SeriesRow) {
        startActivity(
            Intent(this, SeriesActivity::class.java)
                .putExtra(SeriesActivity.EXTRA_SERIES, series.name)
                .putExtra(SeriesActivity.EXTRA_KEY, series.key),
        )
    }

    private fun openMedia(item: MediaItem) {
        if (item.kind == "clip") { playNow(item); return }
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        val intent = when (item.kind) {
            "photo" -> Intent(this, PhotoActivity::class.java)
                .putExtra(PhotoActivity.EXTRA_ITEM, json)
                .putExtra(PhotoActivity.EXTRA_ALBUM_KEY, if (selectedKind == "photo" && selectedCollection == null &&
                    searchQuery.isBlank() && currentQuery().photoView == "folders") LibraryOrganizer.photoAlbumKey(item) else "")
                .putExtra(PhotoActivity.EXTRA_SORT, if (selectedKind == "photo" && selectedCollection == null &&
                    searchQuery.isBlank() && currentQuery().photoView == "folders") currentQuery().sort else "")
                .putExtra(PhotoActivity.EXTRA_DESCENDING, currentQuery().descending ?: (currentQuery().sort != "title"))
                .putExtra(PhotoActivity.EXTRA_COLLECTION_ID, selectedCollection?.id.orEmpty())
            "audio" -> Intent(this, AudioPlayerActivity::class.java)
                .putExtra(AudioPlayerActivity.EXTRA_ITEM, json)
                .putExtra(AudioPlayerActivity.EXTRA_COLLECTION_ID, selectedCollection?.id.orEmpty())
            else -> Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_ITEM, json)
        }
        startActivity(intent)
    }

    private fun playNow(item: MediaItem) {
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ITEM, json)
                .putExtra(PlayerActivity.EXTRA_AUTO_RESUME, item.inProgress),
        )
    }

    companion object {
        private const val CATEGORY_SWIPE_DISTANCE_DP = 72
        private const val SWIPE_HINT_DISMISS_MS = 180L
        private const val TV_CONTENT_TOP_MARGIN_DP = 96
        private const val TV_LIBRARY_HERO_CONTENT_TOP_MARGIN_DP = 324
        private const val COMPACT_NAVIGATION_MAX_WIDTH_DP = 600f
        // Une bibliothèque peut évoluer sans action côté TV : le serveur publie le
        // nouveau compteur de scan et l'écran se met à jour en quelques secondes.
        private const val AUTO_REFRESH_MS = 3_000L
        private const val DISCOVERY_WINDOW_MS = 1_000
        private const val MAX_SEARCH_CHARACTERS = 120
        private const val STATE_SEARCH_QUERY = "state_search_query"
        private const val STATE_SEARCH_SCOPE = "state_search_scope"
        private const val STATE_FOCUS_ROW = "state_focus_row"
        private const val STATE_FOCUS_SHELF = "state_focus_shelf"
        private const val STATE_FOCUS_DESTINATION = "state_focus_destination"
    }
}
