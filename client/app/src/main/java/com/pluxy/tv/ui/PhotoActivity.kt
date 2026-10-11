package com.pluxy.tv.ui

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Precision
import coil.dispose
import coil.load
import com.pluxy.tv.R
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.LibraryRequest
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.networkOrNull
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Visionneuse locale : gauche/droite, puis diaporama optionnel. */
class PhotoActivity : ThemedActivity() {
    private val api = PluxyApi.create()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var imageLoader: ImageLoader
    private lateinit var photoView: ImageView
    private lateinit var titleView: TextView
    private lateinit var indexView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var photoInfo: View
    private lateinit var photoHintLeft: View
    private lateinit var photoHintRight: View
    private lateinit var photoStatus: TextView
    private lateinit var photoControls: View
    private lateinit var previousButton: ImageButton
    private lateinit var slideshowButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var retryButton: Button
    private lateinit var errorView: TextView
    private lateinit var folderNotice: TextView
    private var folderPages: List<PhotoFolderPage> = emptyList()
    private var pagedFolders = false
    private val folderCache = linkedMapOf<String, List<MediaItem>>()
    private var folderLoad: kotlinx.coroutines.Job? = null
    private var photoById: Map<String, MediaItem> = emptyMap()
    private val folderPositions = mutableMapOf<String, String>()
    private var prefetchJob: Job? = null
    private val hideFolderNotice = Runnable { if (::folderNotice.isInitialized) folderNotice.visibility = View.GONE }
    private val showLoading = Runnable { if (!imageDisplayed && activityStarted) loading.visibility = View.VISIBLE }
    private val accessibilityManager by lazy { getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager }
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
        if (enabled && ::photoInfo.isInitialized) setChromeVisible(true)
    }
    private var photos: List<MediaItem> = emptyList()
    private var index = 0
    private var slideshowRunning = false
    private var chromeVisible = true
    private var selectedFolder = ""
    private var selectedAlbumKey = ""
    private var folderSort = ""
    private var folderDescending = false
    private var collectionId = ""
    private var activityStarted = false
    private var imageDisplayed = false
    private var imageRequestVersion = 0L
    private val consumedKeys = mutableSetOf<Int>()

    private val hideChrome = Runnable {
        // A focused action must stay visible while the user chooses it.
        if (!accessibilityManager.isEnabled && !photoInfo.hasFocus() && !closeButton.hasFocus()) {
            setChromeVisible(false)
        }
    }

    private val slideshowTick = object : Runnable {
        override fun run() {
            if (!shouldAdvanceSlideshow(slideshowRunning, activityStarted, imageDisplayed, photos.size)) return
            showAt(index + 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        val json = savedInstanceState?.getString(STATE_ITEM) ?: intent.getStringExtra(EXTRA_ITEM)
            ?: run { finish(); return }
        val initial = runCatching { moshi.adapter(MediaItem::class.java).fromJson(json) }.getOrNull()
            ?.takeIf { it.kind == "photo" } ?: run { finish(); return }

        imageLoader = LanMediaImages.loader(this)
        photoView = findViewById(R.id.photo)
        titleView = findViewById(R.id.photoTitle)
        indexView = findViewById(R.id.photoIndex)
        loading = findViewById(R.id.loading)
        photoInfo = findViewById(R.id.photoInfo)
        photoHintLeft = findViewById(R.id.photoHintLeft)
        photoHintRight = findViewById(R.id.photoHintRight)
        photoStatus = findViewById(R.id.photoStatus)
        photoControls = findViewById(R.id.photoControls)
        previousButton = findViewById(R.id.btnPhotoPrevious)
        slideshowButton = findViewById(R.id.btnPhotoSlideshow)
        nextButton = findViewById(R.id.btnPhotoNext)
        closeButton = findViewById(R.id.btnPhotoClose)
        retryButton = findViewById(R.id.btnPhotoRetry)
        errorView = findViewById(R.id.photoError)
        folderNotice = findViewById(R.id.photoFolderNotice)
        photos = listOf(initial)
        selectedFolder = savedInstanceState?.getString(STATE_FOLDER) ?: intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        selectedAlbumKey = savedInstanceState?.getString(STATE_ALBUM_KEY) ?: intent.getStringExtra(EXTRA_ALBUM_KEY).orEmpty()
        folderSort = savedInstanceState?.getString(EXTRA_SORT) ?: intent.getStringExtra(EXTRA_SORT).orEmpty()
        folderDescending = savedInstanceState?.getBoolean(EXTRA_DESCENDING) ?: intent.getBooleanExtra(EXTRA_DESCENDING, false)
        collectionId = savedInstanceState?.getString(STATE_COLLECTION_ID) ?: intent.getStringExtra(EXTRA_COLLECTION_ID).orEmpty()
        slideshowRunning = savedInstanceState?.getBoolean(STATE_SLIDESHOW) ?: false

        bindPhotoGestures()
        previousButton.setOnClickListener { browseTo(index - 1) }
        slideshowButton.setOnClickListener { setSlideshowRunning(!slideshowRunning) }
        nextButton.setOnClickListener { browseTo(index + 1) }
        closeButton.setOnClickListener { finish() }
        retryButton.setOnClickListener { showAt(index) }
        photoView.setOnClickListener {
            if (accessibilityManager.isEnabled) revealControls() else {
                setChromeVisible(!chromeVisible)
                if (chromeVisible) scheduleChromeHide()
            }
        }
        ViewCompat.replaceAccessibilityAction(
            photoView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.photo_show_controls),
        ) { _, _ -> revealControls(); true }
        ViewCompat.addAccessibilityAction(photoView, getString(R.string.photo_previous_action)) { _, _ ->
            if (photos.size > 1) { browseTo(index - 1); true } else false
        }
        ViewCompat.addAccessibilityAction(photoView, getString(R.string.photo_next_action)) { _, _ ->
            if (photos.size > 1) { browseTo(index + 1); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chromeVisible && !accessibilityManager.isEnabled && errorView.visibility != View.VISIBLE) {
                    setChromeVisible(false)
                } else finish()
                consumedKeys.clear()
            }
        })
        setChromeVisible(savedInstanceState?.getBoolean(STATE_CHROME, true) ?: true)
        showAt(0)
        val savedControl = savedInstanceState?.getInt(STATE_FOCUSED_CONTROL, View.NO_ID) ?: View.NO_ID
        val focus = savedControl.takeIf { it != View.NO_ID }?.let { findViewById<View>(it) }
        if (focus?.visibility != View.VISIBLE || !focus.requestFocus()) photoView.requestFocus()
        if (chromeVisible) scheduleChromeHide()

        lifecycleScope.launch {
            if (collectionId.isBlank() && selectedAlbumKey.isNotBlank()) { loadPagedFolders(initial); return@launch }
            val snapshot = networkOrNull { CatalogueRepository.load(api.abs("/"), mediaKind = "photo") {
                val reader = PluxyApi(api.baseUrl)
                try { reader.listItems(LibraryRequest(kind = "photo")) } finally { reader.close() }
            } }
                ?: return@launch
            val folder = selectedFolder
            val (fresh, validFolder) = withContext(Dispatchers.Default) {
                val valid = validPhotoFolder(snapshot.items, folder)
                val allowed = if (collectionId.isBlank()) snapshot.items else {
                    LibraryPreferences.collections(this@PhotoActivity, api.baseUrl).firstOrNull { it.id == collectionId }
                        ?.let { TvLibraryContent.collectionItems(snapshot.items, it) }.orEmpty()
                }
                val selected = allowed.filter { it.kind == "photo" && it.available }
                val ordered = if (folderSort.isNotBlank()) TvLibraryContent.sortMedia(selected,
                    TvLibraryContent.Query("photo", sort = folderSort, descending = folderDescending))
                    else selected.sortedWith(compareByDescending<MediaItem> { LibraryOrganizer.captureTime(it) ?: -1.0 }
                        .thenByDescending { it.addedAt })
                ordered to valid
            }
            selectedFolder = validFolder
            folderPages = photoFolderPages(fresh)
            photoById = fresh.associateBy { it.id }
            val scoped = fresh.filter { if (selectedAlbumKey.isNotBlank()) LibraryOrganizer.photoAlbumKey(it) == selectedAlbumKey
                else validFolder.isBlank() || TvLibraryContent.photoFolderLabel(it) == validFolder }
            if (scoped.isNotEmpty()) {
                val currentId = photos.getOrNull(index)?.id
                photos = scoped
                index = restoredPhotoIndex(photos, currentId)
                if (photos[index].id != currentId) {
                    android.widget.Toast.makeText(this@PhotoActivity, R.string.photo_missing_fallback, android.widget.Toast.LENGTH_SHORT).show()
                    showAt(index)
                } else {
                    updatePhotoLabel()
                    updateSlideshowControls()
                    scheduleSlideshow()
                    prefetchNearby()
                }
            }
        }
    }

    /** Sur téléphone, l'image elle-même est la zone de navigation : un glissement
     * horizontal change de photo, un tap simple affiche ou masque les commandes.
     * Tout l'événement est consommé ici pour qu'un tap ne puisse jamais retomber sur
     * la grille Films de l'activité précédente. */
    private fun bindPhotoGestures() {
        val minimumDistance = resources.displayMetrics.density * SWIPE_DISTANCE_DP
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                photoView.performClick()
                return true
            }

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
                browseTo(if (deltaX < 0) index + 1 else index - 1)
                return true
            }
        })
        photoView.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            true
        }
    }

    private fun showAt(requested: Int) {
        if (photos.isEmpty()) return
        prefetchJob?.cancel()
        handler.removeCallbacks(slideshowTick)
        val version = ++imageRequestVersion
        imageDisplayed = false
        index = ((requested % photos.size) + photos.size) % photos.size
        val item = photos[index]
        updatePhotoLabel()
        errorView.visibility = View.GONE
        if (retryButton.hasFocus()) photoView.requestFocus()
        retryButton.visibility = View.GONE
        updateSlideshowControls()
        loading.visibility = View.GONE
        handler.removeCallbacks(showLoading)
        handler.postDelayed(showLoading, 200L)
        val previousImage = photoView.drawable
        photoView.load(api.abs("/stream/direct/${item.id}"), imageLoader) {
            size(photoWidth(), photoHeight())
            scale(Scale.FIT)
            precision(Precision.INEXACT)
            memoryCacheKey(photoCacheKey(item))
            crossfade(120)
            placeholder(previousImage)
            if (previousImage != null) error(previousImage) else error(R.drawable.ic_photo_placeholder)
            listener(
                onSuccess = { _, _ ->
                    if (version == imageRequestVersion && !isDestroyed) {
                        loading.visibility = View.GONE
                        handler.removeCallbacks(showLoading)
                        imageDisplayed = true
                        scheduleSlideshow()
                        prefetchNearby()
                    }
                },
                onError = { _, _ ->
                    if (version == imageRequestVersion && !isDestroyed) {
                        loading.visibility = View.GONE
                        handler.removeCallbacks(showLoading)
                        errorView.setText(R.string.photo_load_error)
                        errorView.visibility = View.VISIBLE
                        retryButton.visibility = View.VISIBLE
                        setChromeVisible(true)
                    }
                },
            )
        }
    }

    private fun updatePhotoLabel() {
        val item = photos.getOrNull(index) ?: return
        titleView.text = item.title
        titleView.contentDescription = item.title
        indexView.text = getString(R.string.photo_index, index + 1, photos.size)
        photoView.contentDescription = getString(R.string.photo_description_position, item.title, index + 1, photos.size)
    }

    private fun browseTo(requested: Int) {
        showAt(requested)
    }

    private fun browseFolder(offset: Int) {
        val current = photos.getOrNull(index) ?: return
        if (folderPages.isEmpty()) return
        val key = LibraryOrganizer.photoAlbumKey(current)
        folderPositions[key] = current.id
        val requested = selectedAlbumKey.takeIf { folderLoad?.isActive == true } ?: key
        val pageIndex = folderPages.indexOfFirst { it.key == requested }.coerceAtLeast(0)
        val nextIndex = wrappedPhotoIndex(pageIndex + offset, folderPages.size)
        val page = folderPages[nextIndex]
        selectedAlbumKey = page.key
        selectedFolder = ""
        if (pagedFolders) {
            folderLoad?.cancel()
            folderLoad = lifecycleScope.launch {
                val loaded = networkOrNull { folderPhotos(page) } ?: run { selectedAlbumKey = key; return@launch }
                photos = loaded
                showAt(restoredPhotoIndex(photos, folderPositions[page.key]))
                prefetchFolderData(nextIndex)
            }
        } else {
            photos = page.photos
            showAt(restoredPhotoIndex(photos, folderPositions[page.key]))
        }
        folderNotice.text = "${page.label} · ${nextIndex + 1}/${folderPages.size}"
        folderNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hideFolderNotice)
        handler.postDelayed(hideFolderNotice, 2_400L)
    }

    private suspend fun loadPagedFolders(initial: MediaItem) {
        val heads = networkOrNull { api.listItems(LibraryRequest(kind = "photo", grouped = true)) } ?: return
        folderPages = photoFolderPages(heads)
        pagedFolders = true
        photoById = heads.associateBy { it.id }
        val page = folderPages.firstOrNull { it.key == LibraryOrganizer.photoAlbumKey(initial) } ?: return
        val loaded = networkOrNull { folderPhotos(page) } ?: return
        photos = loaded
        index = restoredPhotoIndex(photos, initial.id)
        updatePhotoLabel(); updateSlideshowControls(); scheduleSlideshow(); prefetchNearby()
        prefetchFolderData(folderPages.indexOf(page))
    }

    private suspend fun folderPhotos(page: PhotoFolderPage): List<MediaItem> {
        folderCache[page.key]?.let { return it }
        val fetched = api.listItems(LibraryRequest(kind = "photo", groupId = page.photos.first().id,
            sort = folderSort.ifBlank { "added" }, descending = if (folderSort.isBlank()) true else folderDescending))
            .filter { it.available }.ifEmpty { page.photos }
        val values = if (folderSort.isBlank()) fetched.sortedWith(compareByDescending<MediaItem> { LibraryOrganizer.captureTime(it) ?: -1.0 }.thenBy { it.title.lowercase() }) else fetched
        folderCache.remove(page.key); folderCache[page.key] = values
        while (folderCache.size > 3) folderCache.remove(folderCache.keys.first())
        photoById = folderPages.flatMap { it.photos }.plus(folderCache.values.flatten()).associateBy { it.id }
        return values
    }

    private fun prefetchFolderData(center: Int) {
        lifecycleScope.launch {
            for (offset in listOf(1, -1)) {
                val page = folderPages.getOrNull(wrappedPhotoIndex(center + offset, folderPages.size)) ?: continue
                networkOrNull { folderPhotos(page) }
            }
        }
    }

    private fun photoWidth() = photoView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    private fun photoHeight() = photoView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
    private fun photoCacheKey(item: MediaItem) = "${api.baseUrl}:photo:${item.id}:${photoWidth()}x${photoHeight()}"

    private fun prefetchNearby() {
        prefetchJob?.cancel()
        if (!activityStarted || !imageDisplayed) return
        val ids = nearbyPhotoIds(folderPages, photos, index)
        prefetchJob = lifecycleScope.launch {
            // Sequential requests leave decoding capacity for the image on screen.
            for (id in ids) {
                val item = photoById[id] ?: photos.firstOrNull { it.id == id } ?: continue
                imageLoader.execute(ImageRequest.Builder(this@PhotoActivity)
                    .data(api.abs("/stream/direct/$id"))
                    .size(photoWidth(), photoHeight()).scale(Scale.FIT).precision(Precision.INEXACT)
                    .memoryCacheKey(photoCacheKey(item)).build())
            }
        }
    }

    private fun setSlideshowRunning(running: Boolean) {
        slideshowRunning = running && photos.size > 1
        handler.removeCallbacks(slideshowTick)
        updateSlideshowControls()
        scheduleSlideshow()
        updateScreenAwake()
    }

    private fun scheduleSlideshow() {
        handler.removeCallbacks(slideshowTick)
        if (shouldAdvanceSlideshow(slideshowRunning, activityStarted, imageDisplayed, photos.size)) {
            handler.postDelayed(slideshowTick, SLIDESHOW_DELAY_MS)
        }
    }

    private fun updateSlideshowControls() {
        if (!::slideshowButton.isInitialized) return
        val canBrowse = photos.size > 1
        previousButton.isEnabled = canBrowse
        nextButton.isEnabled = canBrowse
        previousButton.alpha = if (canBrowse) 1f else 0.35f
        nextButton.alpha = if (canBrowse) 1f else 0.35f
        // Keep the central action focusable even while the folder is loading or
        // contains a single image, so revealing controls always has a stable target.
        slideshowButton.contentDescription = getString(
            if (slideshowRunning) R.string.photo_slideshow_pause else R.string.photo_slideshow_start,
        )
        slideshowButton.setImageResource(
            if (slideshowRunning) R.drawable.ic_player_pause else R.drawable.ic_player_play,
        )
        photoStatus.text = getString(
            if (canBrowse) R.string.photo_slideshow_active else R.string.photo_single_image,
        )
        photoStatus.visibility = if (chromeVisible && (slideshowRunning || !canBrowse)) View.VISIBLE else View.GONE
    }

    private fun updateScreenAwake() {
        if (activityStarted && slideshowRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun revealControls() {
        setChromeVisible(true)
        if (retryButton.visibility == View.VISIBLE) retryButton.requestFocus() else slideshowButton.requestFocus()
    }

    private fun scheduleChromeHide() {
        setChromeVisible(true)
        handler.removeCallbacks(hideChrome)
        if (!accessibilityManager.isEnabled && errorView.visibility != View.VISIBLE) {
            handler.postDelayed(hideChrome, CHROME_TIMEOUT_MS)
        }
    }

    private fun setChromeVisible(visible: Boolean) {
        val show = visible || accessibilityManager.isEnabled || errorView.visibility == View.VISIBLE
        chromeVisible = show
        val television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        closeButton.visibility = if (show && !television) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideChrome)
        if (!show && (photoInfo.hasFocus() || closeButton.hasFocus())) photoView.requestFocus()
        listOf(photoInfo, photoHintLeft, photoHintRight).forEach { chrome ->
            chrome.animate().cancel()
            if (show) {
                chrome.visibility = View.VISIBLE
                chrome.animate().alpha(1f).setDuration(160L).start()
            } else {
                chrome.animate().alpha(0f).setDuration(180L).withEndAction {
                    if (!chromeVisible) chrome.visibility = View.GONE
                }.start()
            }
        }
        updateSlideshowControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::photoView.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlFocused = photoInfo.hasFocus() || closeButton.hasFocus()
            if (event.repeatCount == 0) consumedKeys.remove(event.keyCode)
            if (event.keyCode in consumedKeys && event.repeatCount > 0 &&
                event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
            ) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    consumedKeys.add(event.keyCode)
                    if (event.repeatCount == 0 || event.repeatCount % 3 == 0) {
                        browseTo(index - 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    consumedKeys.add(event.keyCode)
                    if (event.repeatCount == 0 || event.repeatCount % 3 == 0) {
                        browseTo(index + 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    consumedKeys.add(event.keyCode)
                    if (event.repeatCount == 0) browseFolder(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.repeatCount > 0) return true
                    if (!chromeVisible || !controlFocused) {
                        consumedKeys.add(event.keyCode)
                        revealControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    consumedKeys.add(event.keyCode)
                    if (event.repeatCount == 0) {
                        setSlideshowRunning(when (event.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> true
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                            else -> !slideshowRunning
                        })
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (!::photoView.isInitialized) return
        activityStarted = true
        accessibilityManager.addAccessibilityStateChangeListener(accessibilityListener)
        if (accessibilityManager.isEnabled) setChromeVisible(true)
        updateSlideshowControls()
        updateScreenAwake()
        scheduleSlideshow()
        prefetchNearby()
    }

    override fun onStop() {
        activityStarted = false
        prefetchJob?.cancel()
        handler.removeCallbacks(showLoading)
        handler.removeCallbacks(hideFolderNotice)
        if (::folderNotice.isInitialized) folderNotice.visibility = View.GONE
        handler.removeCallbacks(slideshowTick)
        accessibilityManager.removeAccessibilityStateChangeListener(accessibilityListener)
        updateScreenAwake()
        consumedKeys.clear()
        handler.removeCallbacks(hideChrome)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        photos.getOrNull(index)?.let { outState.putString(STATE_ITEM, moshi.adapter(MediaItem::class.java).toJson(it)) }
        outState.putString(STATE_FOLDER, selectedFolder)
        outState.putString(STATE_ALBUM_KEY, selectedAlbumKey)
        outState.putString(EXTRA_SORT, folderSort)
        outState.putBoolean(EXTRA_DESCENDING, folderDescending)
        outState.putString(STATE_COLLECTION_ID, collectionId)
        outState.putBoolean(STATE_SLIDESHOW, slideshowRunning)
        outState.putBoolean(STATE_CHROME, chromeVisible)
        outState.putInt(STATE_FOCUSED_CONTROL, currentFocus?.takeIf { it !== photoView }?.id ?: View.NO_ID)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        imageRequestVersion++
        prefetchJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::photoView.isInitialized) photoView.dispose()
        api.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SORT = "extra_photo_folder_sort"
        const val EXTRA_DESCENDING = "extra_photo_folder_descending"
        const val EXTRA_ITEM = "extra_photo_item_json"
        const val EXTRA_FOLDER = "extra_photo_folder"
        const val EXTRA_ALBUM_KEY = "extra_photo_album_key"
        const val EXTRA_COLLECTION_ID = "extra_photo_collection_id"
        private const val STATE_ITEM = "photo_current_item"
        private const val STATE_FOLDER = "photo_current_folder"
        private const val STATE_ALBUM_KEY = "photo_current_album_key"
        private const val STATE_COLLECTION_ID = "photo_current_collection_id"
        private const val STATE_SLIDESHOW = "photo_slideshow_requested"
        private const val STATE_CHROME = "photo_chrome_visible"
        private const val STATE_FOCUSED_CONTROL = "photo_focused_control"
        private const val SWIPE_DISTANCE_DP = 56
        private const val SLIDESHOW_DELAY_MS = 5_000L
        private const val CHROME_TIMEOUT_MS = 4_000L
    }
}
