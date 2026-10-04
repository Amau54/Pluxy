package com.pluxy.tv.ui

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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.dispose
import coil.load
import com.pluxy.tv.R
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.networkOrNull
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Visionneuse locale : gauche/droite, puis diaporama optionnel. */
class PhotoActivity : AppCompatActivity() {
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
    private lateinit var previousButton: Button
    private lateinit var slideshowButton: Button
    private lateinit var nextButton: Button
    private lateinit var retryButton: Button
    private lateinit var errorView: TextView
    private val accessibilityManager by lazy { getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager }
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
        if (enabled && ::photoInfo.isInitialized) setChromeVisible(true)
    }
    private var photos: List<MediaItem> = emptyList()
    private var index = 0
    private var slideshowRunning = false
    private var chromeVisible = true
    private var selectedFolder = ""
    private var activityStarted = false
    private var imageDisplayed = false
    private var imageRequestVersion = 0L
    private val consumedKeys = mutableSetOf<Int>()

    private val hideChrome = Runnable {
        // A focused action must stay visible while the user chooses it.
        if (!accessibilityManager.isEnabled && !photoInfo.hasFocus()) setChromeVisible(false)
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
        retryButton = findViewById(R.id.btnPhotoRetry)
        errorView = findViewById(R.id.photoError)
        photos = listOf(initial)
        selectedFolder = savedInstanceState?.getString(STATE_FOLDER) ?: intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        slideshowRunning = savedInstanceState?.getBoolean(STATE_SLIDESHOW) ?: false

        bindPhotoGestures()
        previousButton.setOnClickListener { browseTo(index - 1) }
        slideshowButton.setOnClickListener { setSlideshowRunning(!slideshowRunning) }
        nextButton.setOnClickListener { browseTo(index + 1) }
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
            val snapshot = networkOrNull { CatalogueRepository.load(api.abs("/")) { api.listItems() } }
                ?: return@launch
            val folder = selectedFolder
            val (fresh, validFolder) = withContext(Dispatchers.Default) {
                val valid = validPhotoFolder(snapshot.items, folder)
                snapshot.items.filter { it.kind == "photo" }
                    .filter { valid.isBlank() || TvLibraryContent.photoFolderLabel(it) == valid }
                    .sortedByDescending { it.addedAt } to valid
            }
            selectedFolder = validFolder
            if (fresh.isNotEmpty()) {
                val currentId = photos.getOrNull(index)?.id
                photos = fresh
                index = restoredPhotoIndex(photos, currentId)
                if (photos[index].id != currentId) {
                    android.widget.Toast.makeText(this@PhotoActivity, R.string.photo_missing_fallback, android.widget.Toast.LENGTH_SHORT).show()
                    showAt(index)
                } else {
                    updatePhotoLabel()
                    updateSlideshowControls()
                    scheduleSlideshow()
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
        loading.visibility = View.VISIBLE
        val previousImage = photoView.drawable
        photoView.load(api.abs("/stream/direct/${item.id}"), imageLoader) {
            crossfade(180)
            placeholder(previousImage)
            if (previousImage != null) error(previousImage) else error(R.drawable.ic_photo_placeholder)
            listener(
                onSuccess = { _, _ ->
                    if (version == imageRequestVersion && !isDestroyed) {
                        loading.visibility = View.GONE
                        imageDisplayed = true
                        scheduleSlideshow()
                    }
                },
                onError = { _, _ ->
                    if (version == imageRequestVersion && !isDestroyed) {
                        loading.visibility = View.GONE
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
        indexView.text = getString(R.string.photo_index, index + 1, photos.size)
        photoView.contentDescription = getString(R.string.photo_description_position, item.title, index + 1, photos.size)
    }

    private fun browseTo(requested: Int) {
        showAt(requested)
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
        // Keep the central action focusable even while the folder is loading or
        // contains a single image, so revealing controls always has a stable target.
        slideshowButton.text = getString(
            if (slideshowRunning) R.string.photo_slideshow_pause else R.string.photo_slideshow_start,
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
        handler.removeCallbacks(hideChrome)
        if (!show && photoInfo.hasFocus()) photoView.requestFocus()
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
            if (event.repeatCount == 0) consumedKeys.remove(event.keyCode)
            if (event.keyCode in consumedKeys && event.repeatCount > 0 &&
                event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
            ) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (chromeVisible && photoInfo.hasFocus()) return super.dispatchKeyEvent(event)
                    consumedKeys.add(event.keyCode)
                    if (event.repeatCount == 0 || event.repeatCount % 3 == 0) {
                        browseTo(index - 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (chromeVisible && photoInfo.hasFocus()) return super.dispatchKeyEvent(event)
                    consumedKeys.add(event.keyCode)
                    if (event.repeatCount == 0 || event.repeatCount % 3 == 0) {
                        browseTo(index + 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!chromeVisible || !photoInfo.hasFocus()) {
                        consumedKeys.add(event.keyCode)
                        revealControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.repeatCount > 0) return true
                    if (!chromeVisible || !photoInfo.hasFocus()) {
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
    }

    override fun onStop() {
        activityStarted = false
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
        outState.putBoolean(STATE_SLIDESHOW, slideshowRunning)
        outState.putBoolean(STATE_CHROME, chromeVisible)
        outState.putInt(STATE_FOCUSED_CONTROL, currentFocus?.takeIf { it !== photoView }?.id ?: View.NO_ID)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        imageRequestVersion++
        handler.removeCallbacksAndMessages(null)
        if (::photoView.isInitialized) photoView.dispose()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM = "extra_photo_item_json"
        const val EXTRA_FOLDER = "extra_photo_folder"
        private const val STATE_ITEM = "photo_current_item"
        private const val STATE_FOLDER = "photo_current_folder"
        private const val STATE_SLIDESHOW = "photo_slideshow_requested"
        private const val STATE_CHROME = "photo_chrome_visible"
        private const val STATE_FOCUSED_CONTROL = "photo_focused_control"
        private const val SWIPE_DISTANCE_DP = 56
        private const val SLIDESHOW_DELAY_MS = 5_000L
        private const val CHROME_TIMEOUT_MS = 4_000L
    }
}
