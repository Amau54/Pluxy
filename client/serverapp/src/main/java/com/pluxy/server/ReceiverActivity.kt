package com.pluxy.server

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pluxy.receiver.cast.GoogleCastController
import com.pluxy.receiver.cast.GoogleCastSupport
import com.pluxy.receiver.core.PlaybackSuitability
import com.pluxy.receiver.core.ReceiverController
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.ReceiverPlaybackPlanner
import com.pluxy.receiver.core.ReceiverProfileStore
import com.pluxy.receiver.core.RemoteMedia
import com.pluxy.receiver.core.RemotePlaybackPhase
import com.pluxy.receiver.core.RemotePlaybackStatus
import com.pluxy.receiver.dlna.DlnaController
import com.pluxy.receiver.dlna.DlnaDiscovery
import com.pluxy.server.library.MediaEntry
import com.pluxy.server.library.MediaSupport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(UnstableApi::class)
class ReceiverActivity : AppCompatActivity() {
    private lateinit var discovery: DlnaDiscovery
    private lateinit var profileStore: ReceiverProfileStore
    private lateinit var receiverAdapter: ReceiverAdapter
    private lateinit var mediaAdapter: RemoteMediaAdapter
    private lateinit var search: EditText
    private lateinit var browserSummary: TextView
    private lateinit var dlnaHeader: View
    private lateinit var dlnaSection: View
    private lateinit var castHint: TextView
    private lateinit var statusText: TextView
    private lateinit var timeText: TextView
    private lateinit var photoControls: View
    private lateinit var standardControls: View
    private lateinit var photoIndex: TextView
    private lateinit var seek: SeekBar
    private lateinit var playPause: Button
    private lateinit var stop: Button
    private lateinit var castPlay: Button
    private lateinit var castRouteButton: MediaRouteButton
    private lateinit var header: View
    private lateinit var destinations: View
    private lateinit var playbackControls: View
    private var keyboardVisible = false
    private var remoteActive = false
    private var catalog: List<MediaEntry> = emptyList()
    private var rawDlnaDevices: List<ReceiverDevice> = emptyList()
    private var dlnaDevices: List<ReceiverDevice> = emptyList()
    private var selectedId: String? = null
    private var filter = MediaFilter.VIDEO
    private val collapsedFolders = mutableSetOf<String>()
    private var userSeeking = false
    private var stableJob: Job? = null
    private var stableKey: String? = null
    private var lastLearnedFailure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.server_activity_receiver)
        profileStore = ReceiverProfileStore(this)
        discovery = DlnaDiscovery(this)
        selectedId = savedInstanceState?.getString(STATE_SELECTED_ID)
        filter = savedInstanceState?.getString(STATE_FILTER)
            ?.let { runCatching { MediaFilter.valueOf(it) }.getOrNull() }
            ?: MediaFilter.VIDEO
        bindViews()
        configureLayout()
        bindLists()
        bindActions()
        setFilter(filter, clearSelection = false)
        observeState()
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        discovery.close()
        stableJob?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_ID, selectedId)
        outState.putString(STATE_FILTER, filter.name)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        search = findViewById(R.id.receiverSearch)
        browserSummary = findViewById(R.id.remoteBrowserSummary)
        dlnaHeader = findViewById(R.id.dlnaHeader)
        dlnaSection = findViewById(R.id.dlnaSection)
        castHint = findViewById(R.id.castHint)
        statusText = findViewById(R.id.remoteStatus)
        timeText = findViewById(R.id.remoteTime)
        photoControls = findViewById(R.id.photoCastControls)
        standardControls = findViewById(R.id.standardRemoteControls)
        photoIndex = findViewById(R.id.photoCastIndex)
        seek = findViewById(R.id.remoteSeek)
        playPause = findViewById(R.id.remotePlayPause)
        stop = findViewById(R.id.remoteStop)
        castPlay = findViewById(R.id.castPlay)
        castRouteButton = findViewById(R.id.castRouteButton)
        header = findViewById(R.id.receiverHeader)
        destinations = findViewById(R.id.receiverDestinations)
        playbackControls = findViewById(R.id.remotePlaybackControls)
        runCatching {
            GoogleCastSupport.setUpRouteButton(
                this,
                castRouteButton,
            )
        }.onFailure {
            castRouteButton.visibility = View.GONE
            castPlay.isEnabled = false
            castPlay.alpha = 0.5f
            castHint.text = "Google Cast indisponible sur cet appareil"
        }
    }

    private fun configureLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            findViewById<LinearLayout>(R.id.receiverBody).orientation = LinearLayout.HORIZONTAL
            val destinationsWidth = (resources.configuration.screenWidthDp / 3).coerceIn(184, 248)
            destinations.layoutParams = LinearLayout.LayoutParams(dp(destinationsWidth), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(12) }
            findViewById<View>(R.id.receiverBrowser).layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
            )
            findViewById<View>(R.id.receiverHeaderSubtitle).visibility = View.GONE
            findViewById<TextView>(R.id.receiverTitle).textSize = 20f

            findViewById<LinearLayout>(R.id.receiverCastCard).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            findViewById<View>(R.id.receiverCastText).layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            castPlay.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { topMargin = dp(8) }

            if (resources.configuration.screenWidthDp >= 720) {
                findViewById<LinearLayout>(R.id.receiverBrowserTools).orientation = LinearLayout.HORIZONTAL
                findViewById<View>(R.id.receiverFilterRow).layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.4f)
                search.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
            }

            (playbackControls as LinearLayout).orientation = LinearLayout.HORIZONTAL
            timeText.layoutParams = LinearLayout.LayoutParams(dp(108), dp(48))
            timeText.gravity = android.view.Gravity.CENTER_VERTICAL
            seek.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
            findViewById<View>(R.id.remoteActionRow).layoutParams = LinearLayout.LayoutParams(dp(188), dp(48))
                .apply { marginStart = dp(8) }
        }
        listOf(R.id.filterVideos, R.id.filterPhotos, R.id.filterAudio).forEach { id ->
            findViewById<Button>(id).apply {
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), 0, dp(6), 0)
            }
        }
        if (resources.configuration.fontScale > 1.2f && resources.configuration.screenWidthDp < 400) {
            val card = findViewById<LinearLayout>(R.id.receiverCastCard)
            val text = findViewById<View>(R.id.receiverCastText)
            card.removeView(castRouteButton)
            card.removeView(text)
            card.removeView(castPlay)
            card.orientation = LinearLayout.VERTICAL
            val castHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            castHeader.addView(castRouteButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            castHeader.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            card.addView(castHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(castPlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        }
        if (resources.configuration.fontScale > 1.2f) {
            val filters = findViewById<LinearLayout>(R.id.receiverFilterRow)
            val tools = filters.parent as LinearLayout
            val position = tools.indexOfChild(filters)
            val railParams = filters.layoutParams
            tools.removeView(filters)
            filters.weightSum = 0f
            val rail = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(filters, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            }
            tools.addView(rail, position, railParams)
            listOf(R.id.filterVideos, R.id.filterPhotos, R.id.filterAudio).forEach { id ->
                findViewById<Button>(id).apply {
                    val margin = (layoutParams as LinearLayout.LayoutParams).marginStart
                    layoutParams = LinearLayout.LayoutParams(
                        (paint.measureText(this.text.toString()) + dp(24)).toInt().coerceAtLeast(dp(96)), dp(48),
                    ).apply { marginStart = margin }
                    setSingleLine(true)
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.receiverRoot)
        val padding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(
                padding[0] + bars.left,
                padding[1] + bars.top,
                padding[2] + bars.right,
                padding[3] + maxOf(bars.bottom, ime.bottom),
            )
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateCompactControls()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateCompactControls() {
        header.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        destinations.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        val shortLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            resources.configuration.fontScale > 1.2f
        browserSummary.visibility = if (keyboardVisible || shortLandscape) View.GONE else View.VISIBLE
        playbackControls.visibility = if (remoteActive && !keyboardVisible) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindLists() {
        receiverAdapter = ReceiverAdapter(
            onClick = ::prepareDlna,
            onLongClick = ::showProfile,
        )
        findViewById<RecyclerView>(R.id.dlnaList).apply {
            layoutManager = LinearLayoutManager(this@ReceiverActivity, RecyclerView.HORIZONTAL, false)
            adapter = receiverAdapter
        }
        mediaAdapter = RemoteMediaAdapter(
            onMediaClick = { item ->
                selectedId = item.id
                mediaAdapter.selectedId = item.id
                mediaAdapter.notifyDataSetChanged()
                statusText.text = "${item.title} · choisissez Google Cast ou une TV DLNA"
            },
            onFolderClick = { folder ->
                if (!collapsedFolders.add(folder)) collapsedFolders.remove(folder)
                renderCatalog()
            },
        )
        findViewById<RecyclerView>(R.id.remoteMediaList).apply {
            layoutManager = LinearLayoutManager(this@ReceiverActivity)
            adapter = mediaAdapter
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.receiverBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.filterVideos).setOnClickListener { setFilter(MediaFilter.VIDEO) }
        findViewById<Button>(R.id.filterPhotos).setOnClickListener { setFilter(MediaFilter.PHOTO) }
        findViewById<Button>(R.id.filterAudio).setOnClickListener { setFilter(MediaFilter.AUDIO) }
        search.doAfterTextChanged { renderCatalog() }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            WindowCompat.getInsetsController(window, search).hide(WindowInsetsCompat.Type.ime())
            search.clearFocus()
            true
        }
        castPlay.setOnClickListener { prepareCast() }
        findViewById<Button>(R.id.photoPrevious).setOnClickListener { movePhoto(-1) }
        findViewById<Button>(R.id.photoNext).setOnClickListener { movePhoto(1) }
        castPlay.setOnLongClickListener {
            GoogleCastSupport.currentDevice(this)?.let(::showProfile)
            true
        }
        playPause.setOnClickListener {
            lifecycleScope.launch { runCatching { RemotePlaybackRuntime.playPause() }.onFailure(::showError) }
        }
        stop.setOnClickListener {
            lifecycleScope.launch { runCatching { RemotePlaybackRuntime.stop() }.onFailure(::showError) }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = "${formatTime(progress * 1_000L)} / ${formatTime(seek.max * 1_000L)}"
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                lifecycleScope.launch {
                    runCatching { RemotePlaybackRuntime.seekTo(seek.progress * 1_000L) }
                        .onFailure(::showError)
                }
            }
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ServerRuntime.catalog.collect {
                        catalog = it
                        if (selectedId != null && it.none { item -> item.id == selectedId }) selectedId = null
                        renderCatalog()
                    }
                }
                launch {
                    discovery.devices.collect { found ->
                        rawDlnaDevices = found
                        dlnaDevices = found.map(profileStore::effective)
                        receiverAdapter.submit(dlnaDevices)
                        val visibility = if (dlnaDevices.isEmpty()) View.GONE else View.VISIBLE
                        // Discovery stays silent: this section only appears when a TV is available.
                        dlnaHeader.visibility = visibility
                        dlnaSection.visibility = visibility
                    }
                }
                launch {
                    RemotePlaybackRuntime.state.collect { renderRemote(it) }
                }
            }
        }
    }

    private fun setFilter(value: MediaFilter, clearSelection: Boolean = true) {
        filter = value
        val buttons = mapOf(
            MediaFilter.VIDEO to findViewById<Button>(R.id.filterVideos),
            MediaFilter.PHOTO to findViewById<Button>(R.id.filterPhotos),
            MediaFilter.AUDIO to findViewById<Button>(R.id.filterAudio),
        )
        buttons.forEach { (candidate, button) ->
            val selected = candidate == value
            button.setBackgroundResource(if (selected) R.drawable.server_bg_button_primary else R.drawable.server_bg_button_secondary)
            button.setTextColor(if (selected) Color.rgb(16, 16, 16) else Color.WHITE)
        }
        if (clearSelection) selectedId = null
        renderCatalog()
    }

    private fun renderCatalog() {
        val query = search.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
        val filtered = catalog.asSequence()
            .filter { filter.accepts(it) }
            .filter { query.isBlank() || searchable(it).contains(query) }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .toList()
        mediaAdapter.selectedId = selectedId
        mediaAdapter.submit(browserRows(filtered))
        browserSummary.text = when {
            filtered.isEmpty() -> "Aucun média dans cette catégorie"
            else -> "${filtered.size} média${if (filtered.size > 1) "s" else ""} · " +
                "dossiers repliables · touchez un dossier pour l’ouvrir"
        }
        if (filtered.isEmpty() && RemotePlaybackRuntime.state.value.media == null) {
            statusText.text = "Aucun média dans cet onglet"
        } else if (filtered.isNotEmpty() && RemotePlaybackRuntime.state.value.media == null &&
            statusText.text.toString() == "Aucun média dans cet onglet") {
            val selected = filtered.firstOrNull { it.id == selectedId }
            statusText.text = if (selected != null) {
                "${selected.title} · choisissez Google Cast ou une TV DLNA"
            } else {
                "Choisissez un média puis une télévision"
            }
        }
    }

    /** Structure de navigateur simple et robuste : les dossiers visibles restent
     * indépendants du stockage Android et les fichiers sans dossier ne disparaissent pas. */
    private fun browserRows(items: List<MediaEntry>): List<BrowserRow> = buildList {
        items.groupBy { it.folder?.trim()?.takeIf(String::isNotBlank) ?: "Médias sans dossier" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .forEach { (folder, entries) ->
                add(BrowserRow.Folder(folder, entries.size, folder !in collapsedFolders))
                if (folder !in collapsedFolders) {
                    entries.forEach { add(BrowserRow.Media(it)) }
                }
            }
    }

    private fun prepareDlna(device: ReceiverDevice) {
        val media = selectedRemoteMedia() ?: return
        prepare(device, media) { DlnaController(device) }
    }

    private fun prepareCast() {
        val media = selectedRemoteMedia() ?: return
        val device = GoogleCastSupport.currentDevice(this)?.let(profileStore::effective)
        if (device == null) {
            castHint.text = "Choisissez la télévision"
            castRouteButton.performClick()
            return
        }
        castHint.text = device.name
        prepare(device, media) { GoogleCastController(this, device) }
    }

    private fun prepare(
        device: ReceiverDevice,
        media: RemoteMedia,
        controller: () -> ReceiverController,
    ) {
        val plan = ReceiverPlaybackPlanner.plan(media, device.capabilities, serverCanAdapt = false)
        if (plan.suitability == PlaybackSuitability.UNSUPPORTED) {
            AlertDialog.Builder(this)
                .setTitle("Compatibilité incertaine")
                .setMessage(
                    (listOf(plan.summary) + plan.reasons +
                        "Le serveur Android ne réencode pas ce fichier. Vous pouvez tout de même tenter le Direct Play.")
                        .joinToString("\n\n"),
                )
                .setPositiveButton("Essayer en Direct Play") { _, _ -> startRemote(device, media, controller) }
                .setNegativeButton("Annuler", null)
                .show()
        } else {
            startRemote(device, media, controller)
        }
    }

    private fun startRemote(
        device: ReceiverDevice,
        media: RemoteMedia,
        createController: () -> ReceiverController,
    ) {
        lifecycleScope.launch {
            val controller = runCatching(createController).getOrElse {
                showError(it)
                return@launch
            }
            try {
                RemotePlaybackRuntime.load(controller, media)
                Toast.makeText(this@ReceiverActivity, "Direct Play envoyé à ${device.name}", Toast.LENGTH_SHORT).show()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    private fun selectedRemoteMedia(): RemoteMedia? {
        val item = catalog.firstOrNull { it.id == selectedId }
        if (item == null) {
            Toast.makeText(this, "Choisissez d'abord un média.", Toast.LENGTH_SHORT).show()
            return null
        }
        if (!item.available) {
            Toast.makeText(this, "Source indisponible. Rétablissez son accès puis relancez le scan.", Toast.LENGTH_LONG).show()
            return null
        }
        val base = ServerRuntime.state.value.address
        if (!ServerRuntime.state.value.running || base.isNullOrBlank()) {
            Toast.makeText(this, "Le serveur doit être actif sur le Wi-Fi.", Toast.LENGTH_LONG).show()
            return null
        }
        val mime = item.mimeType?.takeIf {
            it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/")
        } ?: MediaSupport.mimeFor(item.kind, item.container)
        return RemoteMedia(
            id = item.id,
            title = item.title,
            url = "$base/stream/direct/${item.id}",
            mimeType = mime,
            kind = item.kind,
            container = item.container,
            videoCodec = item.videoCodec,
            audioCodec = item.audioCodec,
            width = item.width,
            height = item.height,
            isHdr = item.isHdr,
            durationMs = (item.durationSeconds * 1_000).toLong().coerceAtLeast(0),
            artworkUrl = "$base/image/thumbnail/${item.id}",
        )
    }

    /** Les flèches restent dans le même dossier, comme dans un explorateur : on ne
     * saute jamais vers une image sans rapport lorsque plusieurs sources existent. */
    private fun movePhoto(offset: Int) {
        val current = catalog.firstOrNull { it.id == selectedId }?.takeIf { it.kind == "photo" }
            ?: return
        val folder = current.folder?.trim().orEmpty()
        val photos = catalog.asSequence()
            .filter { it.kind == "photo" && it.folder?.trim().orEmpty() == folder }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .toList()
        if (photos.size < 2) return
        val currentIndex = photos.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val target = photos[(currentIndex + offset + photos.size) % photos.size]
        selectedId = target.id
        mediaAdapter.selectedId = target.id
        mediaAdapter.notifyDataSetChanged()
        lifecycleScope.launch {
            val remote = selectedRemoteMedia() ?: return@launch
            runCatching { RemotePlaybackRuntime.showMedia(remote) }.onFailure(::showError)
        }
    }

    private fun renderRemote(value: RemotePlaybackStatus) {
        remoteActive = value.active
        updateCompactControls()
        val isPhoto = value.active && value.media?.kind == "photo"
        photoControls.visibility = if (isPhoto) View.VISIBLE else View.GONE
        standardControls.visibility = if (isPhoto) View.GONE else View.VISIBLE
        if (isPhoto) updatePhotoIndex(value.media?.id)
        val deviceName = value.device?.name
        statusText.text = when (value.phase) {
            RemotePlaybackPhase.IDLE -> selectedId?.let { id ->
                catalog.firstOrNull { it.id == id }?.let { "${it.title} · choisissez une télévision" }
            } ?: "Choisissez un média puis une télévision"
            RemotePlaybackPhase.CONNECTING -> "Connexion à ${deviceName ?: "la TV"}…"
            RemotePlaybackPhase.BUFFERING -> "Mise en mémoire sur ${deviceName ?: "la TV"}…"
            RemotePlaybackPhase.PLAYING -> "${value.media?.title ?: "Lecture"} · ${deviceName ?: "TV"}"
            RemotePlaybackPhase.PAUSED -> "En pause · ${deviceName ?: "TV"}"
            RemotePlaybackPhase.ENDED -> "Lecture terminée"
            RemotePlaybackPhase.ERROR -> value.error ?: "La télévision a refusé le média"
        }
        val durationSeconds = (value.durationMs / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val positionSeconds = (value.positionMs / 1_000).coerceIn(0, durationSeconds.coerceAtLeast(0).toLong()).toInt()
        seek.max = durationSeconds.coerceAtLeast(1)
        if (!userSeeking) seek.progress = positionSeconds
        seek.isEnabled = value.active && durationSeconds > 0 && value.device?.capabilities?.supportsSeek != false
        playPause.isEnabled = value.active
        stop.isEnabled = value.active
        playPause.text = if (value.phase == RemotePlaybackPhase.PLAYING) "Pause" else "Lecture"
        timeText.text = "${formatTime(value.positionMs)} / ${formatTime(value.durationMs)}"

        val device = value.device
        val media = value.media
        val unsupportedComponent = value.unsupportedComponent
        if (value.formatUnsupported && unsupportedComponent != null && device != null && media != null) {
            val key = "${device.id}:${media.id}"
            if (lastLearnedFailure != key) {
                lastLearnedFailure = key
                profileStore.recordFormatFailure(device, media, unsupportedComponent)
                Toast.makeText(this, "Format mémorisé comme incompatible pour ${device.name}", Toast.LENGTH_LONG).show()
            }
        }
        scheduleStableLearning(value)
    }

    private fun updatePhotoIndex(id: String?) {
        val current = catalog.firstOrNull { it.id == id } ?: return
        val folder = current.folder?.trim().orEmpty()
        val photos = catalog.filter { it.kind == "photo" && it.folder?.trim().orEmpty() == folder }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
        val position = photos.indexOfFirst { it.id == current.id }
        photoIndex.text = if (position >= 0) "${position + 1} / ${photos.size}" else ""
    }

    private fun scheduleStableLearning(value: RemotePlaybackStatus) {
        val device = value.device
        val media = value.media
        if (value.phase != RemotePlaybackPhase.PLAYING || device == null || media == null) {
            stableJob?.cancel()
            stableJob = null
            stableKey = null
            return
        }
        val key = "${device.id}:${media.id}"
        if (stableKey == key && stableJob?.isActive == true) return
        stableJob?.cancel()
        stableKey = key
        stableJob = lifecycleScope.launch {
            delay(STABLE_LEARNING_MS)
            val current = RemotePlaybackRuntime.state.value
            if (current.phase == RemotePlaybackPhase.PLAYING &&
                current.device?.id == device.id && current.media?.id == media.id
            ) {
                profileStore.recordSuccess(device, media)
            }
        }
    }

    private fun showProfile(device: ReceiverDevice) {
        val caps = profileStore.effective(device).capabilities
        val details = buildString {
            append(caps.source)
            append("\nConteneurs : ").append(caps.containers.sorted().joinToString().ifBlank { "non annoncés" })
            append("\nVidéo : ").append(caps.videoCodecs.sorted().joinToString().ifBlank { "non annoncée" })
            append("\nAudio : ").append(caps.audioCodecs.sorted().joinToString().ifBlank { "non annoncé" })
            caps.maxVideoHeight?.let { append("\nPlafond : ").append(it).append('p') }
        }
        AlertDialog.Builder(this)
            .setTitle("Profil · ${device.name}")
            .setMessage(details)
            .setPositiveButton("Réinitialiser le profil") { _, _ ->
                profileStore.reset(device)
                dlnaDevices = rawDlnaDevices.map(profileStore::effective)
                receiverAdapter.submit(dlnaDevices)
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showError(error: Throwable) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Envoi impossible")
            .setMessage(error.message?.take(300) ?: "La télévision n'a pas accepté la lecture.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun searchable(item: MediaEntry): String = listOfNotNull(
        item.title,
        item.seriesName,
        item.artist,
        item.album,
        item.folder,
    ).joinToString(" ").lowercase(Locale.ROOT)

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val tail = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, tail)
        else "%02d:%02d".format(minutes, tail)
    }

    private enum class MediaFilter {
        VIDEO, PHOTO, AUDIO;

        fun accepts(item: MediaEntry): Boolean = when (this) {
            VIDEO -> item.kind == "movie" || item.kind == "series"
            PHOTO -> item.kind == "photo"
            AUDIO -> item.kind == "audio"
        }
    }

    companion object {
        private const val STATE_SELECTED_ID = "selected_media_id"
        private const val STATE_FILTER = "media_filter"
        private const val STABLE_LEARNING_MS = 30_000L
    }
}

private class ReceiverAdapter(
    private val onClick: (ReceiverDevice) -> Unit,
    private val onLongClick: (ReceiverDevice) -> Unit,
) : RecyclerView.Adapter<ReceiverAdapter.Holder>() {
    private var items: List<ReceiverDevice> = emptyList()

    fun submit(value: List<ReceiverDevice>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.server_item_receiver, parent, false),
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.description.text = listOfNotNull(item.description, item.capabilities.source)
            .joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.receiverName)
        val description: TextView = view.findViewById(R.id.receiverDescription)
    }
}

private sealed interface BrowserRow {
    data class Folder(val name: String, val count: Int, val expanded: Boolean) : BrowserRow
    data class Media(val entry: MediaEntry) : BrowserRow
}

private class RemoteMediaAdapter(
    private val onMediaClick: (MediaEntry) -> Unit,
    private val onFolderClick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<BrowserRow> = emptyList()
    var selectedId: String? = null

    fun submit(value: List<BrowserRow>) {
        items = value
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is BrowserRow.Folder -> TYPE_FOLDER
        is BrowserRow.Media -> TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == TYPE_FOLDER) R.layout.server_item_remote_folder else R.layout.server_item_remote_media
        return if (viewType == TYPE_FOLDER) FolderHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false),
        ) else Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is BrowserRow.Folder -> bindFolder(holder as FolderHolder, row)
            is BrowserRow.Media -> bindMedia(holder as Holder, row.entry)
        }
    }

    private fun bindFolder(holder: FolderHolder, folder: BrowserRow.Folder) {
        holder.chevron.setImageResource(if (folder.expanded) R.drawable.server_ic_chevron_down else R.drawable.server_ic_chevron_right)
        holder.title.text = folder.name
        holder.count.text = "${folder.count} élément${if (folder.count > 1) "s" else ""}"
        ViewCompat.setStateDescription(holder.itemView, if (folder.expanded) "Dossier ouvert" else "Dossier fermé")
        holder.itemView.setOnClickListener { onFolderClick(folder.name) }
    }

    private fun bindMedia(holder: Holder, item: MediaEntry) {
        holder.kind.setImageResource(when (item.kind) {
            "photo" -> R.drawable.server_ic_media_photo
            "audio" -> R.drawable.server_ic_media_music
            else -> R.drawable.server_ic_media_video
        })
        holder.title.text = item.title
        holder.description.text = when (item.kind) {
            "audio" -> listOfNotNull(item.artist, item.album, item.container.uppercase()).joinToString(" · ")
            "photo" -> listOfNotNull(item.folder, item.container.uppercase()).joinToString(" · ")
            "series" -> listOfNotNull(
                item.seriesName,
                item.season?.let { "S%02d".format(it) },
                item.episode?.let { "E%02d".format(it) },
                item.container.uppercase(),
            ).joinToString(" · ")
            else -> listOfNotNull(item.parsedYear?.toString(), item.container.uppercase(), item.height?.let { "${it}p" })
                .joinToString(" · ")
        }
        holder.itemView.setBackgroundResource(
            if (item.id == selectedId) R.drawable.server_bg_selected else R.drawable.server_bg_button_secondary,
        )
        holder.itemView.isSelected = item.id == selectedId
        holder.selected.visibility = if (item.id == selectedId) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onMediaClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val kind: ImageView = view.findViewById(R.id.mediaKind)
        val title: TextView = view.findViewById(R.id.mediaTitle)
        val description: TextView = view.findViewById(R.id.mediaDescription)
        val selected: ImageView = view.findViewById(R.id.mediaSelected)
    }

    class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chevron: ImageView = view.findViewById(R.id.folderChevron)
        val title: TextView = view.findViewById(R.id.folderTitle)
        val count: TextView = view.findViewById(R.id.folderCount)
    }

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_MEDIA = 1
    }
}
