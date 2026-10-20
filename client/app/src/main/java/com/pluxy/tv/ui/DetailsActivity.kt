package com.pluxy.tv.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pluxy.tv.ui.InAppNotice as Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.CastMember
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.LanMediaImages
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.MovieMetadata
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.PlaybackDecision
import com.pluxy.tv.player.DiagnosticLevel
import com.pluxy.tv.player.PlaybackDiagnostic
import com.pluxy.tv.player.PlaybackStrategy
import com.pluxy.tv.player.PlayerActivity
import com.pluxy.tv.player.ProgressOutbox
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Fiche détaillée Pluxy : synopsis, casting, bande-annonce et actions de lecture. */
@OptIn(UnstableApi::class)
class DetailsActivity : ThemedActivity() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var item: MediaItem
    private val api = PluxyApi.create()
    private var playbackDecision: PlaybackDecision? = null
    private var resumePositionMs: Long = 0L
    private var primaryStartsFromBeginning = false
    private var primaryAutoResume = false
    private var progressWatched = false
    private var localBackdropUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        if (resources.getInteger(R.integer.details_actions_orientation) == LinearLayout.VERTICAL) {
            listOf(R.id.play, R.id.restart, R.id.trailer, R.id.playSettings).forEach { id ->
                findViewById<Button>(id).apply {
                    layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
                }
            }
        }

        val json = intent.getStringExtra(EXTRA_ITEM) ?: run { finish(); return }
        item = moshi.adapter(MediaItem::class.java).fromJson(json) ?: run { finish(); return }

        val displayTitle = LibraryOrganizer.displayTitle(item)
        findViewById<TextView>(R.id.title).apply {
            text = displayTitle
            contentDescription = displayTitle
        }
        findViewById<TextView>(R.id.posterFallback).text = displayTitle
        findViewById<ImageView>(R.id.poster).contentDescription =
            getString(R.string.description_poster_title, item.title)
        findViewById<RecyclerView>(R.id.cast).layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Pastilles techniques disponibles d'emblée (sans réseau) : résolution, codec, HDR.
        buildTechChips()
        loadLocalThumbnail()

        val playBtn = findViewById<Button>(R.id.play)
        playBtn.setOnClickListener { play() }
        findViewById<Button>(R.id.restart).setOnClickListener {
            launchPlayer(forceDirect = false, startFromBeginning = true, autoResume = false)
        }
        resumePositionMs = (item.duration * 1_000 * item.progress).toLong().coerceAtLeast(0)
        updatePlaybackActions(item.watched)
        playBtn.requestFocus()
        findViewById<Button>(R.id.trailer).visibility = View.GONE
        findViewById<Button>(R.id.playSettings).setOnClickListener { showPlaybackSettings() }
        findViewById<TextView>(R.id.playDiagnostic).setOnClickListener { showDiagnosticDetails() }
        findViewById<TextView>(R.id.overview).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.label_synopsis)
                .setMessage(findViewById<TextView>(R.id.overview).text)
                .setPositiveButton("Fermer", null).show()
        }

        loadMetadata()
        loadPlaybackDiagnostic()
    }

    override fun onResume() {
        super.onResume()
        if (!::item.isInitialized) return
        lifecycleScope.launch {
            val progress = try {
                ProgressOutbox.pendingResume(api.baseUrl, item.id) ?: api.getProgress(item.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            resumePositionMs = progress.positionMs.coerceAtLeast(0)
            updatePlaybackActions(progress.watched)
        }
    }

    private fun loadLocalThumbnail() {
        val relative = item.thumbnailUrl ?: return
        localBackdropUrl = runCatching {
            LanUrlPolicy.resolveSameServer(PluxyApplication.serverBaseUrl(this), relative)
        }.getOrNull() ?: return
        loadLocalBackdrop()
    }

    private fun loadLocalPoster() {
        val url = localBackdropUrl ?: return
        findViewById<ImageView>(R.id.poster).load(url, LanMediaImages.loader(this)) {
            listener(onSuccess = { _, _ ->
                this@DetailsActivity.findViewById<TextView>(R.id.posterFallback).visibility = View.GONE
            })
        }
    }

    private fun loadLocalBackdrop() {
        val url = localBackdropUrl ?: return
        findViewById<ImageView>(R.id.backdrop).load(url, LanMediaImages.loader(this))
    }

    private fun updatePlaybackActions(watched: Boolean) {
        progressWatched = watched
        renderPlaybackActions()
    }

    private fun renderPlaybackActions() {
        val durationMs = (item.duration * 1_000).toLong().coerceAtLeast(0)
        val resumable = resumePositionMs >= 10_000L &&
            (durationMs <= 0L || resumePositionMs < durationMs * 0.90)
        primaryStartsFromBeginning = progressWatched && !resumable
        primaryAutoResume = resumable
        findViewById<Button>(R.id.play).text = when {
            playbackDecision?.playable == false && PlaybackStrategy.fromKey(PluxyApplication.playbackStrategy(this)) != PlaybackStrategy.DIRECT ->
                getString(R.string.label_direct_play_try)
            resumable -> getString(
                R.string.label_resume_at,
                formatPlaybackTime(resumePositionMs),
            )
            progressWatched -> getString(R.string.action_replay)
            else -> getString(R.string.action_play)
        }
        findViewById<Button>(R.id.restart).visibility =
            if (resumable) View.VISIBLE else View.GONE
    }

    /** Notes multi-sites (TMDB / IMDb / Rotten Tomatoes / Metacritic). */
    private fun buildRatings(m: com.pluxy.tv.api.MovieMetadata) {
        val row = findViewById<LinearLayout>(R.id.ratings)
        row.removeAllViews()
        if (m.ratings.isEmpty()) { row.visibility = View.GONE; return }
        row.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        for (r in m.ratings) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (10 * d).toInt() }
            }
            cell.addView(TextView(this).apply {
                text = r.value
                setTextColor(pluxyColor(R.color.accent))
                textSize = resources.getDimension(R.dimen.auxiliary_meta_size) / resources.displayMetrics.scaledDensity
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            })
            cell.addView(TextView(this).apply {
                text = " · " + r.source
                setTextColor(pluxyColor(R.color.text_tertiary))
                textSize = resources.getDimension(R.dimen.auxiliary_meta_size) / resources.displayMetrics.scaledDensity
                gravity = Gravity.CENTER
                letterSpacing = 0f
            })
            row.addView(cell)
        }
    }

    /** Pastilles techniques (résolution / codec / HDR / durée) issues du fichier. */
    private fun buildTechChips() {
        val chips = findViewById<LinearLayout>(R.id.chips)
        chips.removeAllViews()
        ArtworkPolicy.resolutionLabel(item.width, item.height)?.let { label ->
            chips.addView(chip(label, accent = label == "4K"))
        }
        if (item.isHdr) chips.addView(chip("HDR", accent = true))
    }

    private fun chip(text: String, accent: Boolean): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            setBackgroundResource(if (accent) R.drawable.bg_chip_accent else R.drawable.bg_chip)
            setTextColor(
                pluxyColor(if (accent) R.color.accent else R.color.text_secondary),
            )
            textSize = resources.getDimension(R.dimen.auxiliary_meta_size) / resources.displayMetrics.scaledDensity
            letterSpacing = 0f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            setPadding((9 * d).toInt(), (3 * d).toInt(), (9 * d).toInt(), (3 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() }
        }
    }

    private fun settingsDp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showPlaybackSettings() {
        val langs = listOf("fr" to "Français", "en" to "English", "es" to "Español",
            "de" to "Deutsch", "it" to "Italiano", "" to "Original (auto)")
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(settingsDp(16), settingsDp(8), settingsDp(16), settingsDp(8))
        }
        fun row(label: String) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            minHeight = settingsDp(48)
            stateListAnimator = null
            setTextColor(pluxyColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_setting_row)
            setPadding(settingsDp(12), settingsDp(8), settingsDp(12), settingsDp(8))
            column.addView(this, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        fun audioLabel() = "Audio · ${langs.firstOrNull { it.first == PluxyApplication.audioLang(this) }?.second ?: "Original (auto)"}"
        fun subtitleLabel() = "Sous-titres · ${if (PluxyApplication.subsMode(this) == "off") "Désactivés" else "Automatiques"}"
        val audio = row(audioLabel())
        val subtitles = row(subtitleLabel())
        val metadata = row("Corriger les informations")
        val dialog = AlertDialog.Builder(this).setTitle("Réglages du média").setView(column)
            .setNegativeButton("Fermer", null).create()
        audio.setOnClickListener { anchor ->
            CompactChoiceMenu.show(this, anchor, "Langue audio",
                langs.map { CompactChoiceMenu.Choice(it.second, it.first) }, PluxyApplication.audioLang(this),
                onSelect = { value ->
                    PluxyApplication.setPlaybackPrefs(this, value, PluxyApplication.subsMode(this))
                    audio.text = audioLabel()
                })
        }
        subtitles.setOnClickListener { anchor ->
            CompactChoiceMenu.show(this, anchor, "Sous-titres",
                listOf(CompactChoiceMenu.Choice("Désactivés", "off"), CompactChoiceMenu.Choice("Automatiques", "auto")),
                PluxyApplication.subsMode(this), onSelect = { value ->
                    PluxyApplication.setPlaybackPrefs(this, PluxyApplication.audioLang(this), value)
                    subtitles.text = subtitleLabel()
                })
        }
        metadata.setOnClickListener {
            dialog.dismiss()
            MetadataMatchDialog.show(this, item) { loadMetadata() }
        }
        dialog.setOnShowListener { audio.requestFocus() }
        dialog.show()
    }

    private fun loadMetadata() {
        val status = findViewById<TextView>(R.id.status)
        status.setText(R.string.label_metadata_loading)
        lifecycleScope.launch {
            val meta = try {
                api.metadata(item.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val message = when {
                !item.available -> getString(R.string.media_unavailable_detail)
                meta == null -> getString(R.string.label_metadata_unavailable)
                !meta.matched -> getString(R.string.label_metadata_unmatched)
                else -> ""
            }
            status.text = message
            status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            if (meta != null) {
                bind(meta)
            } else {
                loadLocalPoster()
                findViewById<View>(R.id.overviewLabel).visibility = View.GONE
                findViewById<View>(R.id.overview).visibility = View.GONE
            }
        }
    }

    private fun loadPlaybackDiagnostic() {
        val view = findViewById<TextView>(R.id.playDiagnostic)
        lifecycleScope.launch {
            val decision = try {
                api.diagnose(item.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (decision == null) {
                view.setText(R.string.label_diagnostic_unavailable)
                view.setTextColor(0xFFB4BCCC.toInt())
                renderPlaybackActions()
                return@launch
            }
            playbackDecision = decision
            renderPlaybackActions()
            val ui = PlaybackDiagnostic.from(decision)
            if (PlaybackStrategy.fromKey(PluxyApplication.playbackStrategy(this@DetailsActivity)) == PlaybackStrategy.DIRECT) {
                view.text = "Vidéo originale · aucune conversion vidéo"
                view.setTextColor(pluxyColor(R.color.text_secondary))
                return@launch
            }
            view.text = when (ui.level) {
                DiagnosticLevel.DIRECT -> "Détails de lecture · ${ui.title}"
                DiagnosticLevel.ADAPTATION -> getString(R.string.label_adaptation_status, ui.title)
                DiagnosticLevel.WARNING -> getString(R.string.label_warning_status, ui.title)
                DiagnosticLevel.UNSUPPORTED ->
                    getString(R.string.label_unsupported_status, ui.title)
            }
            view.setTextColor(
                when (ui.level) {
                    DiagnosticLevel.DIRECT -> pluxyColor(R.color.text_secondary)
                    DiagnosticLevel.ADAPTATION -> pluxyColor(R.color.accent)
                    DiagnosticLevel.WARNING -> 0xFFF6C85F.toInt()
                    DiagnosticLevel.UNSUPPORTED -> 0xFFFF7B7B.toInt()
                },
            )
        }
    }

    private fun showDiagnosticDetails() {
        val decision = playbackDecision
        if (decision == null) {
            Toast.makeText(this, "Le diagnostic sera relancé au démarrage de la lecture.", Toast.LENGTH_SHORT).show()
            return
        }
        val ui = PlaybackDiagnostic.from(decision)
        AlertDialog.Builder(this)
            .setTitle(ui.title)
            .setMessage(ui.detail + "\n\nFichier : " + listOfNotNull(item.videoCodec, item.audioCodec).joinToString(" · ") + "\nProfil : " + (decision.profileName ?: "détection locale"))
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun bind(m: MovieMetadata) {
        findViewById<TextView>(R.id.posterFallback).text = m.title
        findViewById<TextView>(R.id.title).apply {
            text = m.title
            contentDescription = m.title
        }
        findViewById<ImageView>(R.id.poster).apply {
            contentDescription = getString(R.string.description_poster_title, m.title)
            val posterUrl = LanUrlPolicy.safeTmdbImage(m.posterUrl)
            if (posterUrl != null) {
                load(posterUrl) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ ->
                            this@DetailsActivity.findViewById<TextView>(R.id.posterFallback).visibility = View.GONE
                        },
                        onError = { _, _ -> loadLocalPoster() },
                    )
                }
            } else loadLocalPoster()
        }
        LanUrlPolicy.safeTmdbImage(m.backdropUrl)?.let {
            findViewById<ImageView>(R.id.backdrop).load(it) {
                listener(onError = { _, _ -> loadLocalBackdrop() })
            }
        }

        val sub = listOfNotNull(
            m.year?.toString(),
            m.runtime?.let { "${it} min" },
            m.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString("  ·  ")
        findViewById<TextView>(R.id.sub).text = sub
        buildRatings(m)

        findViewById<TextView>(R.id.tagline).apply {
            text = m.tagline ?: ""; visibility = if (m.tagline.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val hasOverview = !m.overview.isNullOrBlank()
        findViewById<TextView>(R.id.overview).apply {
            text = m.overview.orEmpty()
            visibility = if (hasOverview) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.overviewLabel).visibility = if (hasOverview) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.director).apply {
            text = m.director?.let { getString(R.string.label_director, it) }.orEmpty()
            visibility = if (m.director.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val hasCast = m.cast.isNotEmpty()
        findViewById<TextView>(R.id.castLabel).visibility =
            if (hasCast) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.cast).apply {
            visibility = if (hasCast) View.VISIBLE else View.GONE
            adapter = if (hasCast) CastAdapter(m.cast) else null
        }

        // Bande-annonce
        val trailerBtn = findViewById<Button>(R.id.trailer)
        TrailerPlayer.bind(this, trailerBtn, m.trailerUrl)
    }

    private fun play() {
        val decision = playbackDecision
        if (decision != null && !decision.playable &&
            PlaybackStrategy.fromKey(PluxyApplication.playbackStrategy(this)) != PlaybackStrategy.DIRECT) {
            val ui = PlaybackDiagnostic.from(decision)
            AlertDialog.Builder(this)
                .setTitle("Lecture non garantie")
                .setMessage(
                    ui.detail + "\n\nLe serveur ne peut pas adapter ce fichier. " +
                        "Vous pouvez tout de même laisser le lecteur tenter le fichier original.",
                )
            .setPositiveButton("Essayer en direct") { _, _ ->
                launchPlayer(
                    forceDirect = true,
                    startFromBeginning = primaryStartsFromBeginning,
                    autoResume = primaryAutoResume,
                )
            }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        launchPlayer(
            forceDirect = false,
            startFromBeginning = primaryStartsFromBeginning,
            autoResume = primaryAutoResume,
        )
    }

    private fun launchPlayer(
        forceDirect: Boolean,
        startFromBeginning: Boolean,
        autoResume: Boolean,
    ) {
        val json = moshi.adapter(MediaItem::class.java).toJson(item)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM, json)
            .putExtra(PlayerActivity.EXTRA_FORCE_DIRECT, forceDirect)
            .putExtra(PlayerActivity.EXTRA_START_FROM_BEGINNING, startFromBeginning)
            .putExtra(PlayerActivity.EXTRA_AUTO_RESUME, autoResume))
    }

    companion object {
        const val EXTRA_ITEM = "extra_item_json"
    }
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private class CastAdapter(private val cast: List<CastMember>) :
    RecyclerView.Adapter<CastAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val profile: ImageView = v.findViewById(R.id.profile)
        val actor: TextView = v.findViewById(R.id.actor)
        val character: TextView = v.findViewById(R.id.character)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false)
        return VH(v)
    }

    override fun getItemCount() = cast.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cast[position]
        h.actor.text = c.name
        h.character.text = c.character ?: ""
        h.character.visibility = if (c.character.isNullOrBlank()) View.GONE else View.VISIBLE
        h.itemView.isFocusable = true
        h.itemView.contentDescription = listOfNotNull(c.name, c.character).joinToString(", ")
        h.profile.contentDescription = null
        val profile = LanUrlPolicy.safeTmdbImage(c.profileUrl)
        if (profile != null) h.profile.load(profile) { crossfade(true) }
        else {
            h.profile.dispose()
            h.profile.setImageDrawable(null)
        }
    }
}
