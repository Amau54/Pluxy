package com.pluxy.tv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pluxy.tv.ui.InAppNotice as Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.player.PlaybackStrategy
import com.pluxy.tv.player.AudioOutputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Réglages TV volontairement courts : connexion, préférences de lecture et bibliothèque.
 * Les paramètres de transcodage et de serveur restent du ressort du serveur Pluxy.
 */
class SettingsActivity : ThemedActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var statusView: TextView
    private var category = SettingsCategory.PLAYBACK
    private val categoryButtons = linkedMapOf<SettingsCategory, Button>()
    private var restoredFocusTag: String? = null
    private var restoredScrollY: Int? = null
    private val sub by lazy { pluxyColor(R.color.text_secondary) }
    private val television: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    private val serverLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = SettingsCategory.fromKey(savedInstanceState?.getString("settings_category") ?: intent.getStringExtra("settings_category"))
        restoredFocusTag = savedInstanceState?.getString("settings_focus")
        restoredScrollY = savedInstanceState?.getInt("settings_scroll")

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            // Zone sûre TV : aucun contrôle ne doit toucher les bords, y compris au focus.
            val safeSide = resources.getDimensionPixelSize(R.dimen.screen_pad)
            setPadding(safeSide, dp(24), safeSide, dp(24))
        }
        scroll.clipChildren = false
        scroll.addView(rows)
        setContentView(createSettingsLayout())

        title(category.label)
        statusView = TextView(this).apply {
            setTextColor(pluxyColor(R.color.text_tertiary)); textSize = 14f
            setPadding(0, 0, 0, dp(4))
            setText(R.string.settings_loading)
        }
        rows.addView(statusView)

        render()
    }

    private fun createSettingsLayout(): View {
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(16))
        }
        navigation.addView(TextView(this).apply {
            text = "Réglages"
            textSize = 22f
            setTextColor(pluxyColor(R.color.text_primary))
            setPadding(dp(10), 0, dp(10), dp(14))
        })
        val categories: android.view.ViewGroup = if (television) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        } else android.widget.GridLayout(this).apply { columnCount = 3 }
        SettingsCategory.entries.forEachIndexed { index, entry ->
            val button = Button(this).apply {
                id = View.generateViewId()
                tag = "category:${entry.key}"
                text = entry.label
                isAllCaps = false
                textSize = if (television) 16f else 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                stateListAnimator = null
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setBackgroundResource(R.drawable.bg_settings_category)
                setOnClickListener {
                    if (category != entry) {
                        category = entry
                        restoredFocusTag = tag as String
                        restoredScrollY = 0
                        render()
                    }
                }
            }
            categoryButtons[entry] = button
            if (categories is android.widget.GridLayout) categories.addView(button,
                android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(48)
                    columnSpec = android.widget.GridLayout.spec(index % 3, 1f)
                    rowSpec = android.widget.GridLayout.spec(index / 3)
                })
            else categories.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
        navigation.addView(categories)
        val safeSide = resources.getDimensionPixelSize(R.dimen.screen_pad)
        return LinearLayout(this).apply {
            orientation = if (television) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundResource(ambientBackgroundResource())
            setPadding(safeSide, 0, if (television) 0 else safeSide, 0)
            addView(navigation, LinearLayout.LayoutParams(if (television) dp(192) else -1, -2))
            if (television) {
                rows.setPadding(dp(24), dp(24), safeSide, dp(24))
                addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
            } else {
                rows.setPadding(0, dp(8), 0, dp(24))
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
    }

    /**
     * Reconstruit les lignes tout en conservant la position et le contrôle qui avait le focus.
     * Les réglages restent ainsi prévisibles après un PATCH ou un changement local.
     */
    private fun render() {
        val focusedTag = restoredFocusTag ?: currentFocus?.tag as? String
        val previousScrollY = restoredScrollY ?: scroll.scrollY
        restoredFocusTag = null
        restoredScrollY = null
        renderContent()
        categoryButtons.forEach { (entry, button) ->
            button.isSelected = category == entry
            button.setTextColor(pluxyColor(if (category == entry) R.color.accent else R.color.text_secondary))
        }
        rows.post {
            scroll.scrollTo(0, previousScrollY.coerceAtLeast(0))
            val target = focusedTag?.let { findViewById<View>(android.R.id.content).findViewWithTag<View>(it) }
                ?: (2 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.isFocusable }
            if (television) {
                val first = (2 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.isFocusable }
                first?.let { if (it.id == View.NO_ID) it.id = View.generateViewId() }
                categoryButtons.values.forEach { it.nextFocusRightId = first?.id ?: View.NO_ID }
            }
            if (television) target?.requestFocusFromTouch() else target?.requestFocus()
        }
    }

    private fun renderContent() {
        while (rows.childCount > 2) rows.removeViewAt(2)
        val configured = PluxyApplication.isConfigured(this)
        (rows.getChildAt(0) as TextView).text = category.label
        statusView.visibility = if (category == SettingsCategory.CONNECTION) View.VISIBLE else View.GONE
        statusView.text = if (configured) PluxyApplication.serverName(this) else "Aucun serveur configuré"
        when (category) {
            SettingsCategory.CONNECTION -> renderConnection(configured)
            SettingsCategory.PLAYBACK -> renderPlayback()
            SettingsCategory.APPEARANCE -> renderAppearance()
            SettingsCategory.LIBRARY -> renderLibrary(configured)
            SettingsCategory.PROFILES -> renderProfiles(configured)
            SettingsCategory.ACCESSIBILITY -> renderAccessibility()
            SettingsCategory.APPLICATION -> renderApplication()
        }
        addClose()
    }

    private fun renderConnection(configured: Boolean) {
        rowButton(
            "Changer de serveur",
            if (configured) PluxyApplication.serverName(this) else "Non configuré",
            "Recherche les serveurs Pluxy présents sur le réseau domestique.",
        ).setOnClickListener {
            serverLauncher.launch(Intent(this, ServerSetupActivity::class.java))
        }
    }

    private fun renderPlayback() {
        val playbackStrategy = PlaybackStrategy.fromKey(
            PluxyApplication.playbackStrategy(this),
        )
        rowButton(
            "Mode de lecture",
            playbackStrategy.title,
            playbackStrategy.description,
            showHint = false,
        ).setOnClickListener { anchor -> pickPlaybackStrategy(anchor, playbackStrategy) }
        val output = AudioOutputMode.fromKey(PluxyApplication.audioOutputMode(this))
        rowButton("Sortie audio", output.title, output.description, showHint = false)
            .setOnClickListener { anchor -> pickAudioOutput(anchor) }
        rowButton("Lecteur des bandes-annonces", TrailerPlayer.preferenceLabel(this),
            "Choisir SmartTube, YouTube ou une autre application installée.")
            .setOnClickListener { anchor -> TrailerPlayer.choosePreference(this, anchor) { render() } }
        val autoNext = PluxyApplication.autoPlayNextEpisode(this)
        val autoSkip = PluxyApplication.autoSkipSegments(this)
        localSwitchRow("Passer automatiquement les intros et génériques", autoSkip) {
            PluxyApplication.setAutoSkipSegments(this, !autoSkip)
            render()
        }
        localSwitchRow("Épisode suivant automatique", autoNext) {
            PluxyApplication.setAutoPlayNextEpisode(this, !autoNext)
            render()
        }

        rowButton(
            "Langue audio",
            audioLanguageLabel(PluxyApplication.audioLang(this)),
            "Choisit la piste prioritaire quand plusieurs langues sont disponibles.",
        ).setOnClickListener { anchor -> pickAudioLanguage(anchor) }
        rowButton(
            "Sous-titres",
            subtitleModeLabel(PluxyApplication.subsMode(this)),
            "Affiche automatiquement les sous-titres ou les masque.",
        ).setOnClickListener { anchor -> pickSubtitleMode(anchor) }
    }

    private fun renderAppearance() {
        val appearance = AppearancePreferences.theme(this)
        rowButton("Thème", appearance.label, appearance.description, showHint = false)
            .setOnClickListener { anchor -> pickAppearanceTheme(anchor, appearance) }
        rowButton("Densité", LibraryPreferences.density(this).label,
            "Ajuste la taille des affiches ; les commandes restent faciles à utiliser.", showHint = true)
            .setOnClickListener { anchor ->
                CompactChoiceMenu.show(this, anchor, "Densité",
                    CardDensity.entries.map { CompactChoiceMenu.Choice(it.label, it.key) },
                    LibraryPreferences.density(this).key,
                    onSelect = { LibraryPreferences.setDensity(this, CardDensity.fromStored(it)); render() })
            }
    }

    private fun renderLibrary(configured: Boolean) {
        rowButton(
            "Personnaliser l’accueil",
            "Rubriques et ordre",
            "Choisissez les rangées affichées et leur ordre sur cet appareil.",
        ).setOnClickListener { startActivity(Intent(this, HomePreferencesActivity::class.java)) }
        if (configured) rows.addView(Button(this).apply {
            tag = "setting:rescan"
            text = "Actualiser la bibliothèque"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(pluxyColor(R.color.text_primary))
            textSize = if (television) 17f else 15f
            setBackgroundResource(R.drawable.bg_setting_row)
            stateListAnimator = null
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minHeight = dp(48)
            if (television) nextFocusLeftId = categoryButtons.getValue(category).id
            setOnClickListener { button ->
                button.isEnabled = false
                lifecycleScope.launch {
                    try {
                        PluxyApi.create().rescan()
                        Toast.makeText(
                            this@SettingsActivity,
                            "Analyse de la bibliothèque lancée.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Impossible d’actualiser la bibliothèque.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } finally {
                        button.isEnabled = true
                    }
                }
            }
        }, lp().also { it.topMargin = dp(2) })
    }

    private fun renderProfiles(configured: Boolean) {
        val multipleProfiles = FamilyProfileSelection.enabled(this)
        localSwitchRow("Activer plusieurs profils", multipleProfiles) {
            FamilyProfileSelection.setEnabled(this, !multipleProfiles)
            lifecycleScope.launch {
                if (configured) CatalogueRepository.invalidate(PluxyApplication.serverBaseUrl(this@SettingsActivity))
                render()
            }
        }
        if (!multipleProfiles) rowButton("Profil utilisé", "Général", "L’historique existant est conservé.")
            .apply { isFocusable = false; isClickable = false }
        if (configured && FamilyProfileSelection.enabled(this)) {
            val server = PluxyApplication.serverBaseUrl(this)
            rowButton("Profil familial", FamilyProfileSelection.name(server), "Historique et préférences de lecture séparés.")
                .setOnClickListener { anchor -> pickFamilyProfile(anchor, server) }
        }
    }

    private fun renderAccessibility() {
        val contrast = PluxyApplication.playerFocusContrast(this)
        localSwitchRow("Focus du lecteur plus contrasté", contrast) {
            PluxyApplication.setPlayerFocusContrast(this, !contrast)
            render()
        }
        val actionHints = PluxyApplication.playerActionHints(this)
        localSwitchRow("Libellés sous les commandes du lecteur", actionHints) {
            PluxyApplication.setPlayerActionHints(this, !actionHints)
            render()
        }
    }

    private fun renderApplication() {
        rowButton("Mises à jour", "Vérifier ›", "Télécharger et installer la dernière version depuis GitHub.")
            .setOnClickListener { com.pluxy.server.updates.AppUpdates.open(this) }
        rowButton("Mode de l’application", "Lecteur ›", "Choisir Lecteur ou Serveur pour cet appareil.", showHint = true)
            .setOnClickListener { startActivity(Intent(this, ModeActivity::class.java)
                .putExtra(com.pluxy.server.ApplicationMode.EXTRA_CHOOSE_MODE, true)) }
    }

    override fun onResume() {
        super.onResume()
        if (::rows.isInitialized) render()
    }

    private fun addClose() {
        val close = Button(this).apply {
            tag = "setting:close"
            text = getString(R.string.action_close)
            isAllCaps = false
            setTextColor(pluxyColor(R.color.text_primary))
            textSize = if (television) 17f else 15f
            setBackgroundResource(R.drawable.bg_setting_row)
            stateListAnimator = null
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            if (television) nextFocusLeftId = categoryButtons.getValue(category).id
            setOnClickListener { finish() }
        }
        rows.addView(close, lp().also { it.topMargin = dp(16) })
    }

    // ---- Lignes ---------------------------------------------------------- //
    private fun title(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text
            setTextColor(pluxyColor(R.color.text_primary)); textSize = if (television) 26f else 24f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun rowButton(label: String, value: String, hint: String?, showHint: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            tag = "setting:$label"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            if (television) nextFocusLeftId = categoryButtons.getValue(category).id
            minimumHeight = dp(48)
            contentDescription = listOfNotNull(label, value, hint).joinToString(". ")
            setBackgroundResource(R.drawable.bg_setting_row)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(pluxyColor(R.color.text_primary))
            textSize = if (television) 17f else 15f
            includeFontPadding = false
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(sub)
            textSize = if (television) 16f else 14f
            includeFontPadding = false
        }
        val usableWidth = dp(resources.configuration.screenWidthDp) -
            resources.getDimensionPixelSize(R.dimen.screen_pad) * 2 - row.paddingLeft - row.paddingRight
        val inline = labelView.paint.measureText(label) + valueView.paint.measureText(value) + dp(24) <= usableWidth
        val header = LinearLayout(this).apply {
            orientation = if (inline) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(labelView, if (inline) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            else lp())
        header.addView(valueView, if (inline) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16)
        } else lp().apply { topMargin = dp(3) })
        row.addView(header, lp())
        // Ces deux choix bénéficient d'une précision ; les autres intitulés se suffisent à eux-mêmes.
        hint?.takeIf { it.isNotBlank() && showHint }?.let { description ->
            row.addView(TextView(this).apply {
                text = description
                setTextColor(pluxyColor(R.color.text_tertiary))
                textSize = if (television) 16f else 14f
                includeFontPadding = false
                setPadding(0, dp(4), 0, 0)
            })
        }
        rows.addView(row, lp().also { it.topMargin = dp(2) })
        return row
    }

    private fun localSwitchRow(label: String, value: Boolean, onToggle: () -> Unit) {
        val toggle = SwitchCompat(this).apply {
            tag = "setting:$label"
            text = label
            isChecked = value
            setTextColor(pluxyColor(R.color.text_primary))
            textSize = if (television) 17f else 15f
            isFocusable = true
            if (television) nextFocusLeftId = categoryButtons.getValue(category).id
            setBackgroundResource(R.drawable.bg_setting_row)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (checked != value) onToggle()
            }
        }
        rows.addView(toggle, lp().also { it.topMargin = dp(2) })
    }

    /** Choix local, disponible même dans l'interface épurée et sans serveur joignable. */
    private fun pickPlaybackStrategy(anchor: View, current: PlaybackStrategy) {
        val choices = PlaybackStrategy.entries
        CompactChoiceMenu.show(
            activity = this,
            anchor = anchor,
            title = "Mode de lecture",
            choices = choices.map { CompactChoiceMenu.Choice(it.title, it.key) },
            selectedValue = current.key,
            onSelect = { value ->
                val selected = PlaybackStrategy.fromKey(value)
                PluxyApplication.setPlaybackStrategy(this, selected.key)
                restoredFocusTag = anchor.tag as? String
                render()
                Toast.makeText(
                    this,
                    "Lecture : ${selected.title}",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    private fun pickAppearanceTheme(anchor: View, current: AppearanceTheme) {
        val choices = AppearanceTheme.entries
        CompactChoiceMenu.show(
            activity = this,
            anchor = anchor,
            title = "Thème",
            choices = choices.map { CompactChoiceMenu.Choice(it.label, it.key, it.description) },
            selectedValue = current.key,
            onSelect = { value ->
                val selected = AppearanceTheme.fromKey(value)
                if (selected != current) {
                    AppearancePreferences.setTheme(this, selected)
                    anchor.requestFocus()
                    recreate()
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_category", category.key)
        outState.putString("settings_focus", currentFocus?.tag as? String)
        outState.putInt("settings_scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun pickAudioOutput(anchor: View) {
        CompactChoiceMenu.show(this, anchor, "Sortie audio",
            AudioOutputMode.entries.map { CompactChoiceMenu.Choice(it.title, it.key, it.description) },
            PluxyApplication.audioOutputMode(this),
            onSelect = { value ->
                PluxyApplication.setAudioOutputMode(this, value)
                restoredFocusTag = anchor.tag as? String
                render()
            })
    }

    private fun pickAudioLanguage(anchor: View) {
        val choices = listOf(
            "fr" to "Français",
            "en" to "English",
            "es" to "Español",
            "de" to "Deutsch",
            "it" to "Italiano",
            "" to "Original (auto)",
        )
        val current = PluxyApplication.audioLang(this)
        CompactChoiceMenu.show(
            activity = this,
            anchor = anchor,
            title = "Langue audio",
            choices = choices.map { CompactChoiceMenu.Choice(it.second, it.first) },
            selectedValue = choices.firstOrNull { it.first == current }?.first ?: "",
            onSelect = { value ->
                PluxyApplication.setPlaybackPrefs(this, value, PluxyApplication.subsMode(this))
                restoredFocusTag = anchor.tag as? String
                render()
            },
        )
    }

    private fun pickSubtitleMode(anchor: View) {
        val choices = listOf("auto" to "Automatiques", "off" to "Désactivés")
        val current = PluxyApplication.subsMode(this)
        CompactChoiceMenu.show(
            activity = this,
            anchor = anchor,
            title = "Sous-titres",
            choices = choices.map { CompactChoiceMenu.Choice(it.second, it.first) },
            selectedValue = choices.firstOrNull { it.first == current }?.first ?: "auto",
            onSelect = { value ->
                PluxyApplication.setPlaybackPrefs(this, PluxyApplication.audioLang(this), value)
                restoredFocusTag = anchor.tag as? String
                render()
            },
        )
    }

    private fun pickFamilyProfile(anchor: View, server: String) {
        lifecycleScope.launch {
            val api = PluxyApi(server)
            try {
                val profiles = api.familyProfiles()
                CompactChoiceMenu.show(this@SettingsActivity, anchor, "Profil familial",
                    profiles.map { CompactChoiceMenu.Choice(if (it.id == "default") "Général" else it.name, it.id) } + CompactChoiceMenu.Choice("Ajouter un profil", "add"),
                    FamilyProfileSelection.selected(server), onSelect = { id ->
                        if (id == "add") createFamilyProfile(server)
                        else lifecycleScope.launch {
                            CatalogueRepository.invalidate(server)
                            FamilyProfileSelection.select(this@SettingsActivity, server, profiles.first { it.id == id })
                            render()
                        }
                    })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@SettingsActivity, "Profils indisponibles. Vérifiez que le serveur est à jour.", Toast.LENGTH_LONG).show() }
            finally { api.close() }
        }
    }

    private fun createFamilyProfile(server: String) {
        val input = android.widget.EditText(this).apply {
            hint = "Nom du profil"; isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Nouveau profil").setView(input)
            .setNegativeButton("Annuler", null).setPositiveButton("Ajouter", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().trim().isEmpty()) { input.error = "Saisissez un nom"; return@setOnClickListener }
                lifecycleScope.launch {
                    val api = PluxyApi(server)
                    try { val profile = api.createFamilyProfile(input.text.toString().trim()); CatalogueRepository.invalidate(server)
                        FamilyProfileSelection.select(this@SettingsActivity, server, profile); dialog.dismiss(); render()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { input.error = "Impossible d'ajouter ce profil" }
                    finally { api.close() }
                }
            }
        }
        dialog.show()
    }

    private fun audioLanguageLabel(value: String): String = when (value) {
        "fr" -> "Français"
        "en" -> "English"
        "es" -> "Español"
        "de" -> "Deutsch"
        "it" -> "Italiano"
        else -> "Original (auto)"
    }

    private fun subtitleModeLabel(value: String): String =
        if (value == "off") "Désactivés" else "Automatiques"

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

}
