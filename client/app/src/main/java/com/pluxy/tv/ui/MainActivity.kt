package com.pluxy.tv.ui

import android.animation.AnimatorInflater
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
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
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.networkOrNull
import com.pluxy.tv.api.Discovery
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
class MainActivity : AppCompatActivity() {
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
    private var contentJob: Job? = null
    private var contentRevision = 0L
    private var fetchRevision = 0L
    private var focusNavigationRevision = 0L
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
        searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
        searchScope = savedInstanceState?.getString(STATE_SEARCH_SCOPE).orEmpty().ifBlank { "all" }
        restoreSavedFocus(savedInstanceState)
        lanImages = LanMediaImages.loader(this)

        val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val reservedDp = if (isTelevision() || resources.getBoolean(R.bool.show_library_hero)) 278f else 96f
        val contentDp = (screenDp - reservedDp).coerceAtLeast(160f)
        val cardDp = resources.getDimension(R.dimen.library_card_width) / resources.displayMetrics.density
        gridSpan = (contentDp / (cardDp + 12f)).toInt().coerceIn(2, 6)
        grid.setItemViewCacheSize(18)
        createAdapters()
        configureCompactNavigation(screenDp)

        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.search).setOnClickListener {
            openSearch()
        }
        findViewById<View>(R.id.sortFilter).setOnClickListener { openSortFilter() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchQuery.isNotBlank()) {
                    clearSearch()
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
        selectedTabView().requestFocus()
        updateSearchButton()
        swipe.setOnRefreshListener { refresh() }
        swipe.setColorSchemeColors(getColor(R.color.accent))
        bindMobileCategorySwipe()

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
        autoRefreshJob?.cancel()
        loadJob?.cancel()
        swipe.isRefreshing = false
        contentJob?.cancel()
        contentRevision++
        autoRefreshJob = null
        super.onPause()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) focusNavigationRevision++
            val focused = currentFocus
            if (focused != null && isInGrid(focused)) {
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
                    selectedTabView().requestFocus()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val layout = grid.layoutManager as? GridLayoutManager
                    val item = grid.findContainingItemView(focused)
                    val position = item?.let(grid::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
                    if (layout != null && position >= 0 &&
                        layout.spanSizeLookup.getSpanIndex(position, layout.spanCount) == 0
                    ) {
                        selectedTabView().requestFocus()
                        return true
                    }
                    val next = FocusFinder.getInstance()
                        .findNextFocus(grid, focused, View.FOCUS_LEFT)
                    if (next == null || !isInGrid(next)) {
                        selectedTabView().requestFocus()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
        )
        homeAdapter = HomeShelfAdapter(
            environment,
            onMedia =(::openMedia),
            onQuickPlay =(::playNow),
            onSeries =(::openSeries),
            onFocused = focusListener,
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

        swipeHint.visibility = View.VISIBLE
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

        findViewById<LinearLayout>(R.id.categoryNavigation).apply {
            background = getDrawable(R.drawable.bg_glass_nav_rail)
            layoutParams = FrameLayout.LayoutParams(
                (164 * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP,
            ).apply {
                topMargin = (132 * density).toInt()
                leftMargin = (48 * density).toInt()
            }
            elevation = (6 * density)
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
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
            findViewById<Button>(id).layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (50 * density).toInt(),
            ).apply { setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt()) }
        }
        val start = (230 * density).toInt()
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
            val restore = focusByDestination.containsKey(focusStateKey())
            applyAndShow(restoreFocus = restore)
            if (!restore) grid.scrollToPosition(0)
            return
        }
        selectedKind = kind
        PluxyApplication.setLibraryTab(this, kind)
        updateTabSelection()
        updateLibraryHeroVisibility()
        val restore = focusByDestination.containsKey(focusStateKey())
        applyAndShow(restoreFocus = restore)
        if (!restore) grid.scrollToPosition(0)
    }

    private fun destinations(): List<Pair<Int, String>> = listOf(
        R.id.tabHome to "home",
        R.id.tabMovies to "movie",
        R.id.tabSeries to "series",
        R.id.tabPhotos to "photo",
        R.id.tabAudio to "audio",
    ).filter { (_, kind) -> kind == "home" || kind in discoveredKinds }

    private fun allDestinations(): List<Pair<Int, String>> = listOf(
        R.id.tabHome to "home",
        R.id.tabMovies to "movie",
        R.id.tabSeries to "series",
        R.id.tabPhotos to "photo",
        R.id.tabAudio to "audio",
    )

    private fun updateTabSelection() {
        allDestinations().forEach { (viewId, kind) ->
            findViewById<View>(viewId).visibility =
                if (kind == "home" || kind in discoveredKinds) View.VISIBLE else View.GONE
            findViewById<View>(viewId).isSelected = selectedKind == kind
        }
    }

    private fun selectedTabView(): View = findViewById(
        destinations().firstOrNull { it.second == selectedKind }?.first ?: R.id.tabHome,
    )

    private fun updateInterfaceMode() {
        // La bibliothèque garde une navigation unique, sans mode technique caché.
        advancedMode = false
        findViewById<View>(R.id.serverLabel).visibility = View.INVISIBLE
        swipe.isEnabled = true
        updateLibraryHeroVisibility()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        if (advancedMode || !PluxyApplication.isConfigured(this)) return
        autoRefreshJob = lifecycleScope.launch {
            var apiUrl = PluxyApplication.serverBaseUrl(this@MainActivity)
            var api = withContext(Dispatchers.IO) { PluxyApi(apiUrl) }
            var sawScan = false
            try {
                while (isActive) {
                    delay(AUTO_REFRESH_MS)
                    if (loadJob?.isActive == true) continue
                    val currentUrl = PluxyApplication.serverBaseUrl(this@MainActivity)
                    if (currentUrl != apiUrl) {
                        api.close()
                        apiUrl = currentUrl
                        api = withContext(Dispatchers.IO) { PluxyApi(apiUrl) }
                    }
                    val status = networkOrNull { api.scanStatus() }
                    if (!isCurrentServer(api.baseUrl)) continue
                    if (status == null) {
                        reconnectFromLanDiscovery()
                        continue
                    }
                    if (status.scanning) {
                        sawScan = true
                    } else if (sawScan || status.count != allItems.size) {
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
        if (!PluxyApplication.isConfigured(this)) {
            showNotConfigured()
            return
        }
        findViewById<TextView>(R.id.serverLabel).text =
            PluxyApplication.serverBaseUrl(this)
                .removePrefix("http://")
                .removePrefix("https://")
        loadJob?.cancel()
        val serverUrl = PluxyApplication.serverBaseUrl(this)
        loadJob = lifecycleScope.launch {
            val navigationAtRequest = focusNavigationRevision
            val restoreAtRequest = restoreSavedFocusOnNextContent || grid.hasFocus()
            val cached = CatalogueRepository.cached(serverUrl)
            if (cached != null && isCurrentServer(serverUrl) && !loadedOnce) {
                allItems = cached.items
                catalogueServerUrl = serverUrl
                discoveredKinds += cached.items.map { it.kind }
                updateAvailableDestinations()
                loadedOnce = true
                applyAndShow(
                    restoreFocus = restoreAtRequest && navigationAtRequest == focusNavigationRevision,
                    focusFirst = navigationAtRequest == focusNavigationRevision,
                )
            }
            fetchAndShow(serverUrl, showBlockingState = !loadedOnce && allItems.isEmpty())
        }
    }

    /** The cache owns its reader; leaving this activity cannot close another viewer's load. */
    private suspend fun loadCatalogue(serverUrl: String): List<MediaItem> =
        CatalogueRepository.load(serverUrl, forceRefresh = true) {
            val reader = PluxyApi(serverUrl)
            try { reader.listItems() } finally { reader.close() }
        }.items

    private suspend fun fetchAndShow(serverUrl: String, showBlockingState: Boolean): Boolean {
        val requestRevision = ++fetchRevision
        val navigationAtRequest = focusNavigationRevision
        val restoreAtRequest = restoreSavedFocusOnNextContent || currentFocus?.let(::isInGrid) == true
        val firstAtRequest = !loadedOnce
        if (showBlockingState) showLoading(true)
        return try {
            val fresh = loadCatalogue(serverUrl)
            if (!isCurrentServer(serverUrl) || requestRevision != fetchRevision) return false
            if (loadedOnce && catalogueServerUrl == serverUrl && fresh === allItems) return true
            allItems = fresh
            catalogueServerUrl = PluxyApplication.serverBaseUrl(this)
            discoveredKinds += allItems.map { it.kind }
            updateAvailableDestinations()
            loadedOnce = true
            applyAndShow(
                restoreFocus = restoreAtRequest &&
                    (navigationAtRequest == focusNavigationRevision || grid.hasFocus()),
                focusFirst = firstAtRequest && navigationAtRequest == focusNavigationRevision,
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (allItems.isEmpty() && isCurrentServer(serverUrl) && requestRevision == fetchRevision) showServerError()
            false
        } finally {
            if (requestRevision == fetchRevision) showLoading(false)
        }
    }

    private fun refreshQuietly() {
        val serverUrl = PluxyApplication.serverBaseUrl(this)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val fresh = networkOrNull {
                loadCatalogue(serverUrl)
            } ?: return@launch
            if (!isCurrentServer(serverUrl)) return@launch
            if (fresh === allItems) return@launch
            allItems = fresh
            catalogueServerUrl = PluxyApplication.serverBaseUrl(this@MainActivity)
            discoveredKinds += fresh.map { it.kind }
            updateAvailableDestinations()
            applyAndShow()
        }
    }

    private fun applyAndShow(
        restoreFocus: Boolean =
            restoreSavedFocusOnNextContent || currentFocus?.let(::isInGrid) == true,
        focusFirst: Boolean = false,
    ) {
        if (catalogueServerUrl == PluxyApplication.serverBaseUrl(this)) {
            val selectedFolder = PluxyApplication.photoFolder(this)
            val validFolder = validPhotoFolder(allItems, selectedFolder)
            if (validFolder != selectedFolder) {
                PluxyApplication.setPhotoFolder(this, validFolder)
                android.widget.Toast.makeText(
                    this, "Ce dossier n’est plus disponible · tous les dossiers sont affichés", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val environment = cardEnvironment()
        libraryAdapter.updateEnvironment(environment)
        homeAdapter.updateEnvironment(environment)
        updateLibraryHeroVisibility()
        updateLibraryContext()
        updatePhotoFolderControl()
        contentJob?.cancel()
        val revision = ++contentRevision
        val navigationRevision = focusNavigationRevision
        val items = allItems
        val destination = selectedKind
        val focusKey = focusStateKey()
        val homeOrder = PluxyApplication.homeShelfOrder(this)
        val hiddenShelves = PluxyApplication.hiddenHomeShelves(this)
        val query = TvLibraryContent.Query(
            destination = destination,
            search = searchQuery,
            searchScope = searchScope,
            watchedFilter = PluxyApplication.libraryWatched(this),
            sort = effectiveSort(),
            photoFolder = PluxyApplication.photoFolder(this),
            collapsedPhotoFolders = collapsedPhotoFolders.toSet(),
            advancedMode = advancedMode,
        )
        contentJob = lifecycleScope.launch {
            if (destination in setOf("home", "movie", "series") && query.search.isBlank()) {
                val shelves = withContext(Dispatchers.Default) {
                    if (destination == "home") TvLibraryContent.homeShelves(items, homeOrder, hiddenShelves)
                    else TvLibraryContent.destinationShelves(items, destination, query.watchedFilter, query.sort)
                }
                if (revision != contentRevision) return@launch
                if (shelves.isEmpty()) {
                    val focusAction = shouldFocusLibraryEmptyAction(restoreFocus, focusFirst,
                        navigationRevision != focusNavigationRevision, grid.hasFocus(), currentFocus != null)
                    if (destination == "home" && hiddenShelves.isNotEmpty() &&
                        items.any { it.kind == "movie" || it.kind == "series" }
                    ) {
                        showEmpty("Les rubriques de l’accueil sont masquées.", "Restaurer les rubriques", focusAction) {
                            PluxyApplication.saveHomeShelves(this@MainActivity, homeOrder, emptySet())
                            applyAndShow(focusFirst = true)
                        }
                    } else if (query.watchedFilter != "all" && destination in setOf("movie", "series")) {
                        showEmpty(emptyMessage(), "Afficher tous les contenus", focusAction) {
                            PluxyApplication.setLibraryWatched(this@MainActivity, "all")
                            applyAndShow(focusFirst = true)
                        }
                    } else showEmpty(emptyMessage(), focusAction = focusAction)
                    return@launch
                }
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
                val result = TvLibraryContent.rows(items, query)
                result to (result.isEmpty() && query.search.isNotBlank() && query.watchedFilter != "all" &&
                    TvLibraryContent.rows(items, query.copy(watchedFilter = "all")).isNotEmpty())
            }
            if (revision != contentRevision) return@launch
            if (rows.isEmpty()) {
                val focusAction = shouldFocusLibraryEmptyAction(restoreFocus, focusFirst,
                    navigationRevision != focusNavigationRevision, grid.hasFocus(), currentFocus != null)
                if (filterHidesResults) showEmpty(emptyMessage(), "Afficher tous les contenus", focusAction) {
                    PluxyApplication.setLibraryWatched(this@MainActivity, "all")
                    applyAndShow(focusFirst = true)
                } else showEmpty(emptyMessage(), focusAction = focusAction)
                return@launch
            }
            showGrid()
            val layout = (grid.layoutManager as? GridLayoutManager)
                ?.takeIf { it.spanCount == gridSpan }
                ?: GridLayoutManager(this@MainActivity, gridSpan)
            if (grid.adapter !== libraryAdapter) {
                grid.layoutManager = layout
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
        // A large preview must leave enough height for a complete poster rail.
        val heightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val show = resources.getBoolean(R.bool.show_library_hero) && heightDp >= 780f &&
            searchQuery.isBlank() && selectedKind in setOf("movie", "series")
        libraryHero.visibility = if (show) View.VISIBLE else View.GONE
        (swipe.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val target = if (show) TV_LIBRARY_HERO_CONTENT_TOP_MARGIN_DP else {
                val isTv = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                    Configuration.UI_MODE_TYPE_TELEVISION
                if (isTv && searchQuery.isNotBlank()) 130 else if (isTv) TV_CONTENT_TOP_MARGIN_DP else DEFAULT_CONTENT_TOP_MARGIN_DP
            }
            params.topMargin = (resources.displayMetrics.density * target).toInt()
            swipe.layoutParams = params
        }
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
            is LibRow.Header -> return
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
        if (screenDp >= COMPACT_NAVIGATION_MAX_WIDTH_DP) return
        findViewById<View>(R.id.brandName).visibility = View.GONE
        if (!isTelevision()) findViewById<Button>(R.id.sortFilter).apply {
            text = "↕"
            (layoutParams as? ViewGroup.MarginLayoutParams)?.width =
                (44 * resources.displayMetrics.density).toInt()
        }
        findViewById<Button>(R.id.tabAudio).text = getString(R.string.destination_music)
    }

    private fun cardEnvironment() = CardEnvironment(advancedMode, lanImages)

    private fun focusStateKey(): String =
        if (searchQuery.isBlank()) selectedKind else "$selectedKind:search"

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
        val contextLabel = findViewById<TextView>(R.id.libraryContext)
        contextLabel.visibility = if (searchQuery.isNotBlank()) View.VISIBLE else View.GONE
        val watched = PluxyApplication.libraryWatched(this)
        val filterLabel = if (watchedFilterRelevant() && watched != "all") {
            if (watched == "watched") " · Déjà vus" else " · Non vus"
        } else ""
        contextLabel.text = "Résultats : « $searchQuery » · ${searchScopeLabel()}$filterLabel"
        findViewById<Button>(R.id.sortFilter).apply {
            visibility = if (searchQuery.isNotBlank() || selectedKind in setOf("movie", "series", "audio")) {
                View.VISIBLE
            } else View.GONE
            isSelected = watched != "all" && watchedFilterRelevant()
            val compact = !isTelevision() && resources.displayMetrics.widthPixels /
                resources.displayMetrics.density < COMPACT_NAVIGATION_MAX_WIDTH_DP
            text = if (compact) "↕" else if (isSelected) {
                if (PluxyApplication.libraryWatched(this@MainActivity) == "watched") "Déjà vus" else "Non vus"
            } else "Trier"
            setTextColor(getColor(if (isSelected) R.color.accent_bright else R.color.text_primary))
        }
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
                applyAndShow(restoreFocus = false, focusFirst = true)
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
            val restore = focusByDestination.containsKey(focusStateKey())
            applyAndShow(restoreFocus = restore)
            if (!restore) grid.scrollToPosition(0)
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

    /** Le serveur peut changer d'adresse après une veille ou un redémarrage. Quand
     * celui choisi devient injoignable, on redécouvre discrètement le LAN au lieu de
     * laisser l'utilisateur ouvrir le réglage ou relancer manuellement l'application. */
    private suspend fun reconnectFromLanDiscovery() {
        val navigationAtRequest = focusNavigationRevision
        val restoreAtRequest = currentFocus?.let(::isInGrid) == true
        val originalUrl = PluxyApplication.serverBaseUrl(this)
        val originalName = PluxyApplication.serverName(this)
        val servers = Discovery.discover(this, timeoutMs = DISCOVERY_WINDOW_MS)
        if (originalUrl != PluxyApplication.serverBaseUrl(this)) return
        val server = ServerReconnectPolicy.select(servers, originalUrl, originalName)
            ?: return
        if (server.baseUrl != originalUrl) {
            val fresh = networkOrNull {
                loadCatalogue(server.baseUrl)
            } ?: return
            if (originalUrl != PluxyApplication.serverBaseUrl(this)) return
            val folder = PluxyApplication.photoFolder(this)
            PluxyApplication.setServer(this, server.baseUrl, server.name)
            PluxyApplication.setPhotoFolder(this, folder)
            grid.visibility = View.INVISIBLE
            lanImages = LanMediaImages.loader(this)
            allItems = fresh
            catalogueServerUrl = server.baseUrl
            discoveredKinds.clear()
            discoveredKinds += fresh.map { it.kind }
            updateAvailableDestinations()
            loadedOnce = true
            applyAndShow(restoreFocus = restoreAtRequest && navigationAtRequest == focusNavigationRevision)
            return
        }
        fetchAndShow(originalUrl, showBlockingState = allItems.isEmpty())
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
        if (show) {
            val label = PluxyApplication.photoFolder(this)
                .ifBlank { getString(R.string.photo_folder_all) }
            control.text = "$label ▾"
            control.contentDescription = getString(R.string.photo_folder_control, label)
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
        val folders = allItems.asSequence()
            .filter { it.kind == "photo" }
            .map(TvLibraryContent::photoFolderLabel)
            .distinct()
            .sortedBy(LibraryOrganizer::searchKey)
            .toList()
        val options = listOf(getString(R.string.photo_folder_all) to "") +
            folders.map { it to it }
        val current = PluxyApplication.photoFolder(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_choose_photo_folder))
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(),
                options.indexOfFirst { it.second == current }.coerceAtLeast(0),
            ) { dialog, index ->
                PluxyApplication.setPhotoFolder(this, options[index].second)
                updatePhotoFolderControl()
                applyAndShow(restoreFocus = false)
                dialog.dismiss()
                restorePhotoFolderFocus()
            }
            .setPositiveButton(R.string.photo_collapse_all) { _, _ ->
                collapsedPhotoFolders.clear()
                collapsedPhotoFolders += folders
                applyAndShow(restoreFocus = false)
                restorePhotoFolderFocus()
            }
            .setNeutralButton(R.string.photo_expand_all) { _, _ ->
                collapsedPhotoFolders.clear()
                applyAndShow(restoreFocus = false)
                restorePhotoFolderFocus()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun restorePhotoFolderFocus() {
        findViewById<Button>(R.id.photoFolders).post {
            findViewById<Button>(R.id.photoFolders).requestFocus()
        }
    }

    private fun searchScopeLabel(): String = when (searchScope) {
        "movie" -> "films"
        "series" -> "séries"
        "photo" -> "photos"
        "audio" -> "musique"
        else -> "tous les médias"
    }

    private fun isTelevision(): Boolean = resources.configuration.uiMode and
        Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    private fun openSortFilter() {
        val sortOptions = sortOptionsForDestination()
        if (sortOptions.isEmpty()) return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Trier et filtrer")
            .setView(panel)
            .setPositiveButton("Fermer", null)
            .create()

        fun row(
            title: String,
            options: List<Pair<String, String>>,
            initial: String,
            apply: (String) -> Unit,
        ) {
            fun labelFor(value: String) =
                options.firstOrNull { it.second == value }?.first ?: value
            var current = initial
            val button = Button(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.focus_card)
                stateListAnimator = AnimatorInflater.loadStateListAnimator(
                    this@MainActivity,
                    R.animator.focus_lift_btn,
                )
                setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                text = getString(R.string.setting_current_value, title, labelFor(current))
            }
            button.setOnClickListener {
                val checked = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(options.map { it.first }.toTypedArray(), checked) {
                            choiceDialog, index ->
                        current = options[index].second
                        apply(current)
                        button.text =
                            getString(R.string.setting_current_value, title, labelFor(current))
                        applyAndShow()
                        choiceDialog.dismiss()
                    }
                    .show()
            }
            panel.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad / 2 },
            )
        }

        row(
            "Trier par",
            sortOptions,
            effectiveSort(),
            { PluxyApplication.setLibrarySort(this, it) },
        )
        if (watchedFilterRelevant()) {
            row(
                "Déjà vus",
                listOf("Tous" to "all", "Non vus" to "unwatched", "Déjà vus" to "watched"),
                PluxyApplication.libraryWatched(this),
                { PluxyApplication.setLibraryWatched(this, it) },
            )
        }
        dialog.show()
    }

    private fun watchedFilterRelevant(): Boolean =
        if (searchQuery.isNotBlank()) searchScope in setOf("all", "movie", "series")
        else selectedKind in setOf("movie", "series")

    private fun sortOptionsForDestination(): List<Pair<String, String>> = when (
        if (searchQuery.isNotBlank()) searchScope else selectedKind
    ) {
        "all", "movie" -> listOf(
            "Titre (A à Z)" to "title",
            "Récemment ajoutés" to "added",
            "Année" to "year",
        )
        "series", "audio", "photo" -> listOf(
            "Titre (A à Z)" to "title",
            "Récemment ajoutés" to "added",
        )
        else -> emptyList()
    }

    private fun effectiveSort(): String {
        if (selectedKind == "photo" && searchQuery.isBlank()) return "added"
        val stored = PluxyApplication.librarySort(this)
        return stored.takeIf { value ->
            sortOptionsForDestination().any { it.second == value }
        } ?: "added"
    }

    private fun openSeries(series: LibRow.SeriesRow) {
        startActivity(
            Intent(this, SeriesActivity::class.java)
                .putExtra(SeriesActivity.EXTRA_SERIES, series.name)
                .putExtra(SeriesActivity.EXTRA_KEY, series.key),
        )
    }

    private fun openMedia(item: MediaItem) {
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        val intent = when (item.kind) {
            "photo" -> Intent(this, PhotoActivity::class.java)
                .putExtra(PhotoActivity.EXTRA_ITEM, json)
                .putExtra(PhotoActivity.EXTRA_FOLDER, PluxyApplication.photoFolder(this))
            "audio" -> Intent(this, AudioPlayerActivity::class.java)
                .putExtra(AudioPlayerActivity.EXTRA_ITEM, json)
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
        private const val TV_CONTENT_TOP_MARGIN_DP = 104
        private const val DEFAULT_CONTENT_TOP_MARGIN_DP = 154
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
