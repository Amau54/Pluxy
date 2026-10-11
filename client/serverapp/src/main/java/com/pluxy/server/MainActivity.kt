package com.pluxy.server

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: ServerPreferences
    private lateinit var statusPill: TextView
    private lateinit var serverAddress: TextView
    private lateinit var serverSummary: TextView
    private lateinit var folderPath: TextView
    private lateinit var photoFolderPath: TextView
    private lateinit var audioFolderPath: TextView
    private lateinit var serverName: EditText
    private lateinit var tmdbKey: EditText
    private lateinit var autoDiscover: SwitchCompat
    private lateinit var keepAwake: SwitchCompat
    private lateinit var autoStart: SwitchCompat
    private lateinit var serverToggle: Button
    private lateinit var startProgress: View
    private lateinit var rescan: Button
    private lateinit var sendToTv: Button
    private lateinit var optionsContainer: View
    private lateinit var optionsToggle: Button
    private var snapshot = ServerSnapshot()
    private var optionsExpanded = false
    private var pendingStartAfterPermission = false

    private val folderManager = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showSelectedFolders()
            if (snapshot.running) ServerService.scan(this)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) Toast.makeText(
            this,
            "Le serveur peut fonctionner, mais Android masquera sa notification de contrôle.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private val mediaPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val usable = grants.values.any { it } || hasAutomaticMediaPermissions()
        if (!usable && !preferences.hasManualMediaSource()) {
            Toast.makeText(
                this,
                "Autorisez les médias ou choisissez un dossier dans Options.",
                Toast.LENGTH_LONG,
            ).show()
        }
        if (pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            if (usable || preferences.hasManualMediaSource()) ServerService.start(this)
            renderState(ServerRuntime.state.value)
        } else if (snapshot.running && usable) {
            ServerService.scan(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.server_activity_main)
        preferences = ServerPreferences(this)
        bindViews()
        loadPreferences()
        bindActions()
        optionsExpanded = savedInstanceState?.getBoolean("options_expanded") ?: false
        pendingStartAfterPermission = savedInstanceState?.getBoolean("pending_start_permission") ?: false
        renderOptions()
        requestNotificationPermissionIfNeeded()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerRuntime.state.collect { state ->
                    snapshot = state
                    renderState(state)
                }
            }
        }
    }

    private fun bindViews() {
        statusPill = findViewById(R.id.statusPill)
        serverAddress = findViewById(R.id.serverAddress)
        serverSummary = findViewById(R.id.serverSummary)
        folderPath = findViewById(R.id.folderPath)
        photoFolderPath = findViewById(R.id.photoFolderPath)
        audioFolderPath = findViewById(R.id.audioFolderPath)
        serverName = findViewById(R.id.serverName)
        tmdbKey = findViewById(R.id.tmdbKey)
        autoDiscover = findViewById(R.id.autoDiscover)
        keepAwake = findViewById(R.id.keepAwake)
        autoStart = findViewById(R.id.autoStart)
        serverToggle = findViewById(R.id.serverToggle)
        startProgress = findViewById(R.id.startProgress)
        rescan = findViewById(R.id.rescan)
        sendToTv = findViewById(R.id.sendToTv)
        optionsContainer = findViewById(R.id.optionsContainer)
        optionsToggle = findViewById(R.id.optionsToggle)
    }

    private fun loadPreferences() {
        serverName.setText(preferences.serverName)
        tmdbKey.setText(preferences.tmdbApiKey)
        autoDiscover.isChecked = preferences.autoDiscoverMedia
        keepAwake.isChecked = preferences.keepAwake
        autoStart.isChecked = preferences.autoStart
        showSelectedFolders()
    }

    private fun bindActions() {
        findViewById<Button>(R.id.chooseFolder).setOnClickListener { chooseSource(MediaTheme.VIDEO) }
        findViewById<Button>(R.id.choosePhotoFolder).setOnClickListener { chooseSource(MediaTheme.PHOTO) }
        findViewById<Button>(R.id.chooseAudioFolder).setOnClickListener { chooseSource(MediaTheme.AUDIO) }
        findViewById<Button>(R.id.tmdbHelpToggle).setOnClickListener {
            val help = findViewById<View>(R.id.tmdbHelp)
            val visible = help.visibility != View.VISIBLE
            help.visibility = if (visible) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.tmdbHelpToggle).text =
                if (visible) "Masquer l’aide TMDB" else "À quoi sert TMDB ?"
        }
        findViewById<Button>(R.id.refreshTmdb).setOnClickListener {
            saveEditableSettings()
            ServerRuntime.update { it.copy(metadataStatus = "Actualisation des affiches et synopsis…") }
            ServerService.refreshMetadata(this)
        }
        findViewById<Button>(R.id.copyAddress).setOnClickListener { copyAddress() }
        findViewById<Button>(R.id.batterySettings).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.privacyPolicy).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                .onFailure {
                    Toast.makeText(
                        this,
                        "Impossible d'ouvrir la politique de confidentialité.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }

        findViewById<Button>(R.id.changeApplicationMode).setOnClickListener {
            packageManager.getLaunchIntentForPackage(packageName)?.component?.let { launcher ->
                startActivity(Intent().setComponent(launcher)
                    .putExtra(ApplicationMode.EXTRA_CHOOSE_MODE, true))
            }
        }
        optionsToggle.setOnClickListener {
            optionsExpanded = !optionsExpanded
            renderOptions()
        }
        findViewById<Button>(R.id.appUpdates).setOnClickListener { com.pluxy.server.updates.AppUpdates.open(this) }
        autoDiscover.setOnCheckedChangeListener { _, checked ->
            preferences.autoDiscoverMedia = checked
            if (checked && !hasAutomaticMediaPermissions()) {
                mediaPermissions.launch(requiredMediaPermissions())
            } else if (snapshot.running) {
                if (preferences.hasMediaSource()) ServerService.scan(this) else ServerService.stop(this)
            }
        }
        keepAwake.setOnCheckedChangeListener { _, checked ->
            preferences.keepAwake = checked
            if (snapshot.running) ServerService.start(this)
        }
        autoStart.setOnCheckedChangeListener { _, checked -> preferences.autoStart = checked }

        serverToggle.setOnClickListener {
            if (snapshot.running) {
                ServerService.stop(this)
                return@setOnClickListener
            }
            saveEditableSettings()
            if (!preferences.hasMediaSource()) {
                optionsExpanded = true
                renderOptions()
                Toast.makeText(
                    this,
                    "Activez la détection automatique ou choisissez un dossier.",
                    Toast.LENGTH_LONG,
                ).show()
            } else if (preferences.autoDiscoverMedia && !hasAutomaticMediaPermissions()) {
                pendingStartAfterPermission = true
                renderState(snapshot)
                mediaPermissions.launch(requiredMediaPermissions())
            } else {
                ServerService.start(this)
            }
        }
        rescan.setOnClickListener {
            saveEditableSettings()
            if (snapshot.running) ServerService.scan(this) else ServerService.start(this)
        }
        sendToTv.setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }

    private fun saveEditableSettings() {
        preferences.serverName = serverName.text?.toString().orEmpty()
        preferences.tmdbApiKey = tmdbKey.text?.toString().orEmpty()
        preferences.autoDiscoverMedia = autoDiscover.isChecked
        preferences.keepAwake = keepAwake.isChecked
        preferences.autoStart = autoStart.isChecked
        serverName.setText(preferences.serverName)
    }

    private fun renderOptions() {
        optionsContainer.visibility = if (optionsExpanded) View.VISIBLE else View.GONE
        optionsToggle.text = if (optionsExpanded) "Masquer les options" else "Options"
    }

    override fun onPause() {
        saveEditableSettings()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("options_expanded", optionsExpanded)
        outState.putBoolean("pending_start_permission", pendingStartAfterPermission)
        super.onSaveInstanceState(outState)
    }

    private fun showSelectedFolders() {
        folderPath.text = sourceLabel(preferences.folders(MediaTheme.VIDEO), "Aucun dossier film ou série")
        photoFolderPath.text = sourceLabel(preferences.folders(MediaTheme.PHOTO), "Aucun dossier image")
        audioFolderPath.text = sourceLabel(preferences.folders(MediaTheme.AUDIO), "Aucun dossier musique")
    }

    private fun sourceLabel(values: List<MediaFolder>, emptyLabel: String): String {
        if (values.isEmpty()) return emptyLabel
        val names = values.map { folder ->
            folder.title.ifBlank {
                runCatching { DocumentFile.fromTreeUri(this, Uri.parse(folder.uri))?.name }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "stockage Android"
            } + if (!folder.visible) " (masqué)" else ""
        }
        val preview = names.take(2).joinToString(" · ")
        return if (names.size == 1) "1 dossier · $preview" else "${names.size} dossiers · $preview" +
            if (names.size > 2) " · +${names.size - 2}" else ""
    }

    private fun chooseSource(theme: MediaTheme) {
        folderManager.launch(Intent(this, FolderManagerActivity::class.java)
            .putExtra(FolderManagerActivity.EXTRA_THEME, theme.name))
    }

    private fun unusedSourceUris(): List<String> = buildList {
        addAll(preferences.videoFolders.map { it.uri })
        addAll(preferences.photoFolders.map { it.uri })
        addAll(preferences.audioFolders.map { it.uri })
    }

    private fun renderState(state: ServerSnapshot) {
        findViewById<TextView>(R.id.tmdbStatus).text = state.metadataStatus
        findViewById<Button>(R.id.refreshTmdb).isEnabled = state.running && !state.starting
        val starting = state.starting || pendingStartAfterPermission
        val accent = ContextCompat.getColor(this, R.color.server_pluxy_accent)
        val success = ContextCompat.getColor(this, R.color.server_pluxy_success)
        val muted = ContextCompat.getColor(this, R.color.server_pluxy_muted)
        val error = ContextCompat.getColor(this, R.color.server_pluxy_error)

        statusPill.text = when {
            starting -> "●  Démarrage"
            state.error != null -> "●  Erreur"
            state.running && !state.wifiConnected -> "●  Hors ligne"
            state.running && state.scanning -> "●  Analyse"
            state.running -> "●  Actif"
            else -> "●  Arrêté"
        }
        statusPill.setTextColor(when {
            starting -> accent
            state.error != null -> error
            state.running && state.wifiConnected -> success
            state.running -> accent
            else -> muted
        })

        serverAddress.text = state.address ?: "Indisponible"
        serverSummary.text = when {
            pendingStartAfterPermission -> "Autorisez l’accès aux médias pour continuer."
            state.starting -> "Démarrage du serveur… chargement de la bibliothèque"
            state.error != null -> state.error
            state.running && state.scanning -> "${state.mediaCount} médias · analyse en cours"
            state.running && !state.wifiConnected -> "En attente du Wi-Fi"
            state.running -> "${state.mediaCount} médias disponibles"
            else -> "Prêt à démarrer"
        }
        startProgress.visibility = if (starting) View.VISIBLE else View.GONE
        serverToggle.isEnabled = !starting
        serverToggle.alpha = if (starting) 0.7f else 1f
        serverToggle.text = when {
            pendingStartAfterPermission -> "Autorisation en attente…"
            state.starting -> "Démarrage en cours…"
            state.running -> "Arrêter le serveur"
            else -> "Démarrer le serveur"
        }
        serverToggle.setBackgroundResource(
            if (state.running) R.drawable.server_bg_button_secondary else R.drawable.server_bg_button_primary,
        )
        serverToggle.setTextColor(ContextCompat.getColor(
            this,
            if (state.running) R.color.server_pluxy_text else R.color.server_pluxy_on_accent,
        ))
        rescan.isEnabled = !starting && !state.scanning && preferences.hasMediaSource()
        rescan.alpha = if (rescan.isEnabled) 1f else 0.5f
        // Cast est aussi un point d'entrée pour choisir une TV et préparer la
        // diffusion ; il ne dépend pas du cycle de vie du serveur local.
        sendToTv.isEnabled = true
        sendToTv.alpha = 1f
    }

    private fun copyAddress() {
        val address = snapshot.address ?: return Toast.makeText(
            this,
            "Aucune adresse disponible : vérifiez le Wi-Fi.",
            Toast.LENGTH_SHORT,
        ).show()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Adresse Pluxy", address))
        Toast.makeText(this, "Adresse copiée", Toast.LENGTH_SHORT).show()
    }

    private fun openBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        val opened = intents.any { intent ->
            runCatching { startActivity(intent); true }.getOrDefault(false)
        }
        if (!opened) Toast.makeText(this, "Réglages batterie introuvables sur cet appareil.", Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requiredMediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasAutomaticMediaPermissions(): Boolean = requiredMediaPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Une même arborescence peut alimenter plusieurs onglets : sa permission SAF ne
     * doit être relâchée qu'après le retrait de la dernière référence. */
    private fun releaseGrantIfUnused(value: String) {
        val stillUsed = unusedSourceUris().any { it == value }
        if (stillUsed) return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(value),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    companion object {
        private const val PRIVACY_URL =
            "https://github.com/Amau54/Pluxy/blob/main/docs/PRIVACY_POLICY.md"
    }
}
