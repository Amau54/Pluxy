package com.pluxy.tv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private val accent by lazy { pluxyColor(R.color.accent) }
    private val sub by lazy { pluxyColor(R.color.text_secondary) }
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
            setPadding(safeSide, dp(32), safeSide, dp(32))
        }
        scroll.clipChildren = false
        scroll.addView(rows)
        setContentView(scroll)

        title("Réglages")
        statusView = TextView(this).apply {
            setTextColor(pluxyColor(R.color.text_tertiary)); textSize = 14f
            setPadding(0, 0, 0, dp(14))
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
        rowButton("Thème", appearance.label, appearance.description)
            .setOnClickListener { pickAppearanceTheme(appearance) }

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
        ).setOnClickListener { pickPlaybackStrategy(playbackStrategy) }
        val autoNext = PluxyApplication.autoPlayNextEpisode(this)
        localSwitchRow("Épisode suivant automatique", autoNext) {
            PluxyApplication.setAutoPlayNextEpisode(this, !autoNext)
            render()
        }

        rowButton(
            "Langue audio",
            audioLanguageLabel(PluxyApplication.audioLang(this)),
            "Choisit la piste prioritaire quand plusieurs langues sont disponibles.",
        ).setOnClickListener { pickAudioLanguage() }
        rowButton(
            "Sous-titres",
            subtitleModeLabel(PluxyApplication.subsMode(this)),
            "Affiche automatiquement les sous-titres ou les masque.",
        ).setOnClickListener { pickSubtitleMode() }

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
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundResource(R.drawable.focus_card)
            stateListAnimator = android.animation.AnimatorInflater
                .loadStateListAnimator(this@SettingsActivity, R.animator.focus_lift_btn)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            minHeight = dp(64)
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
        }, lp().also { it.topMargin = dp(8) })

        addClose()
    }

    private fun addClose() {
        val close = Button(this).apply {
            tag = "setting:close"
            setText(R.string.action_close)
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundResource(R.drawable.bg_btn_secondary)
            stateListAnimator = android.animation.AnimatorInflater
                .loadStateListAnimator(this@SettingsActivity, R.animator.focus_lift_btn)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { finish() }
        }
        rows.addView(close, lp().also { it.topMargin = dp(20) })
    }

    // ---- Lignes ---------------------------------------------------------- //
    private fun title(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text
            setTextColor(pluxyColor(R.color.text_primary)); textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
    }

    private fun section(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(accent); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(26), 0, dp(6))
        })
    }

    private fun rowButton(label: String, value: String, hint: String?): LinearLayout {
        val row = LinearLayout(this).apply {
            tag = "setting:$label"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(74)
            contentDescription = listOfNotNull(label, value, hint).joinToString(". ")
            setBackgroundResource(R.drawable.focus_card)
            stateListAnimator = android.animation.AnimatorInflater
            .loadStateListAnimator(this@SettingsActivity, R.animator.focus_lift_btn)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(sub)
            textSize = 16f
            maxLines = 3
            setPadding(0, dp(2), 0, 0)
        })
        hint?.takeIf { it.isNotBlank() }?.let { description ->
            row.addView(TextView(this).apply {
                text = description
                setTextColor(pluxyColor(R.color.text_tertiary))
                textSize = if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) 16f else 14f
                setPadding(0, dp(2), 0, 0)
            })
        }
        rows.addView(row, lp().also { it.topMargin = dp(8) })
        return row
    }

    private fun localSwitchRow(label: String, value: Boolean, onToggle: () -> Unit) {
        val toggle = SwitchCompat(this).apply {
            tag = "setting:$label"
            text = label
            isChecked = value
            setTextColor(Color.WHITE)
            textSize = 16f
            isFocusable = true
            setBackgroundResource(R.drawable.focus_card)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            minHeight = dp(64)
            setOnCheckedChangeListener { _, checked ->
                if (checked != value) onToggle()
            }
        }
        rows.addView(toggle, lp().also { it.topMargin = dp(12) })
    }

    /** Choix local, disponible même dans l'interface épurée et sans serveur joignable. */
    private fun pickPlaybackStrategy(current: PlaybackStrategy) {
        val choices = PlaybackStrategy.entries
        val labels = choices.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mode de lecture")
            .setSingleChoiceItems(labels, choices.indexOf(current)) { dialog, which ->
                val selected = choices[which]
                PluxyApplication.setPlaybackStrategy(this, selected.key)
                render()
                Toast.makeText(
                    this,
                    "Lecture : ${selected.title}",
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickAppearanceTheme(current: AppearanceTheme) {
        val choices = AppearanceTheme.entries
        AlertDialog.Builder(this)
            .setTitle("Thème")
            .setSingleChoiceItems(
                choices.map { "${it.label} · ${it.description}" }.toTypedArray(),
                choices.indexOf(current),
            ) { dialog, which ->
                val selected = choices[which]
                dialog.dismiss()
                if (selected != current) {
                    AppearancePreferences.setTheme(this, selected)
                    recreate()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_focus", currentFocus?.tag as? String)
        outState.putInt("settings_scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun pickAudioLanguage() {
        val choices = listOf(
            "fr" to "Français",
            "en" to "English",
            "es" to "Español",
            "de" to "Deutsch",
            "it" to "Italiano",
            "" to "Original (auto)",
        )
        val current = PluxyApplication.audioLang(this)
        AlertDialog.Builder(this)
            .setTitle("Langue audio")
            .setSingleChoiceItems(
                choices.map { it.second }.toTypedArray(),
                choices.indexOfFirst { it.first == current }.coerceAtLeast(0),
            ) { dialog, which ->
                PluxyApplication.setPlaybackPrefs(this, choices[which].first, PluxyApplication.subsMode(this))
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickSubtitleMode() {
        val choices = listOf("auto" to "Automatiques", "off" to "Désactivés")
        val current = PluxyApplication.subsMode(this)
        AlertDialog.Builder(this)
            .setTitle("Sous-titres")
            .setSingleChoiceItems(
                choices.map { it.second }.toTypedArray(),
                choices.indexOfFirst { it.first == current }.coerceAtLeast(0),
            ) { dialog, which ->
                PluxyApplication.setPlaybackPrefs(this, PluxyApplication.audioLang(this), choices[which].first)
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
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
