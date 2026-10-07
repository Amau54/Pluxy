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
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.player.PlaybackStrategy
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
        restoredFocusTag = savedInstanceState?.getString("settings_focus")
        restoredScrollY = savedInstanceState?.getInt("settings_scroll")

        scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_tv_ambient)
            fitsSystemWindows = true
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
        setContentView(scroll)

        title("Réglages")
        statusView = TextView(this).apply {
            setTextColor(pluxyColor(R.color.text_tertiary)); textSize = 14f
            setPadding(0, 0, 0, dp(4))
            setText(R.string.settings_loading)
        }
        rows.addView(statusView)

        render()
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
        rows.post {
            scroll.scrollTo(0, previousScrollY.coerceAtLeast(0))
            if (focusedTag != null) {
                rows.findViewWithTag<View>(focusedTag)?.requestFocus()
            } else if (currentFocus == null || currentFocus === scroll) {
                rows.findViewWithTag<View>("setting:Changer de serveur")?.requestFocus()
            }
        }
    }

    private fun renderContent() {
        // On retire tout sauf le titre (index 0) et le status (index 1).
        while (rows.childCount > 2) rows.removeViewAt(2)
        val configured = PluxyApplication.isConfigured(this)
        statusView.text = if (configured) "Serveur configuré" else "Aucun serveur configuré"

        section("Apparence")
        val appearance = AppearancePreferences.theme(this)
        rowButton("Thème", appearance.label, appearance.description, showHint = true)
            .setOnClickListener { anchor -> pickAppearanceTheme(anchor, appearance) }
        rowButton("Densité", if (LibraryPreferences.compact(this)) "Compact" else "Confort",
            "Ajuste la taille des affiches ; les commandes restent faciles à utiliser.", showHint = true)
            .setOnClickListener { anchor ->
                CompactChoiceMenu.show(this, anchor, "Densité",
                    listOf(CompactChoiceMenu.Choice("Compact", "compact"), CompactChoiceMenu.Choice("Confort", "comfort")),
                    if (LibraryPreferences.compact(this)) "compact" else "comfort",
                    onSelect = { LibraryPreferences.setCompact(this, it == "compact"); render() })
            }

        section("Connexion")
        rowButton(
            "Changer de serveur",
            if (configured) PluxyApplication.serverName(this) else "Non configuré",
            "Recherche les serveurs Pluxy présents sur le réseau domestique.",
        ).setOnClickListener {
            serverLauncher.launch(Intent(this, ServerSetupActivity::class.java))
        }

        section("Lecture")
        val playbackStrategy = PlaybackStrategy.fromKey(
            PluxyApplication.playbackStrategy(this),
        )
        rowButton(
            "Mode de lecture",
            playbackStrategy.title,
            playbackStrategy.description,
            showHint = true,
        ).setOnClickListener { anchor -> pickPlaybackStrategy(anchor, playbackStrategy) }
        val autoNext = PluxyApplication.autoPlayNextEpisode(this)
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

        section("Bibliothèque")
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

        addClose()
    }

    private fun addClose() {
        val close = Button(this).apply {
            tag = "setting:close"
            setText(R.string.action_close)
            isAllCaps = false
            setTextColor(pluxyColor(R.color.text_primary))
            textSize = if (television) 17f else 15f
            setBackgroundResource(R.drawable.bg_setting_row)
            stateListAnimator = null
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
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

    private fun section(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(pluxyColor(R.color.text_tertiary)); textSize = if (television) 13f else 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.05f
            setPadding(dp(10), dp(20), dp(10), dp(4))
        })
    }

    private fun rowButton(label: String, value: String, hint: String?, showHint: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            tag = "setting:$label"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
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
        outState.putString("settings_focus", currentFocus?.tag as? String)
        outState.putInt("settings_scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
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
