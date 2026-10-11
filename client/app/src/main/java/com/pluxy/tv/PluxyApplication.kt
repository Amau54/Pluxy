package com.pluxy.tv

import android.app.Application
import android.content.Context
import com.pluxy.tv.player.ProgressOutbox

/**
 * Application globale : conserve l'adresse du serveur Pluxy (modifiable par
 * l'utilisateur) et expose un point d'accès unique au client réseau.
 */
class PluxyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProgressOutbox.start(this)
        com.pluxy.server.updates.AppUpdates.register(this, setOf(
            com.pluxy.tv.ui.MainActivity::class.java,
            com.pluxy.tv.ui.ServerSetupActivity::class.java,
            com.pluxy.server.MainActivity::class.java,
        ))
    }

    companion object {
        lateinit var instance: PluxyApplication
            private set

        private const val PREFS = "pluxy_prefs"
        private const val KEY_SERVER = "server_base_url"
        private const val KEY_NAME = "server_name"

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** true si un serveur a déjà été choisi (par découverte ou manuellement). */
        fun isConfigured(ctx: Context): Boolean =
            prefs(ctx).getString(KEY_SERVER, null) != null

        fun serverBaseUrl(ctx: Context): String =
            prefs(ctx).getString(KEY_SERVER, "http://127.0.0.1:8420")!!

        fun serverName(ctx: Context): String =
            prefs(ctx).getString(KEY_NAME, "Pluxy") ?: "Pluxy"

        fun setServer(ctx: Context, url: String, name: String = "Pluxy") {
            // Migrate any legacy global folder while it still belongs to the old server.
            if (isConfigured(ctx)) photoFolder(ctx)
            prefs(ctx).edit()
                .putString(KEY_SERVER, url.trimEnd('/'))
                .putString(KEY_NAME, name)
                .apply()
        }

        private fun readerPrefs(ctx: Context): android.content.SharedPreferences {
            val server = serverBaseUrl(ctx)
            val profile = com.pluxy.tv.ui.FamilyProfileSelection.selected(server)
            return if (profile == "default") prefs(ctx) else ctx.getSharedPreferences("reader:$server:$profile", Context.MODE_PRIVATE)
        }

        // --- Préférences de lecture (réglées en amont depuis la fiche film) --- //
        private const val KEY_AUDIO_LANG = "pref_audio_lang"
        private const val KEY_SUBS_MODE = "pref_subs_mode"   // auto | off
        private const val KEY_PLAYBACK_STRATEGY = "playback_strategy"
        private const val KEY_AUDIO_OUTPUT_MODE = "audio_output_mode"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next_episode"

        fun audioLang(ctx: Context): String =
            readerPrefs(ctx).getString(KEY_AUDIO_LANG, "fr") ?: "fr"

        fun subsMode(ctx: Context): String =
            readerPrefs(ctx).getString(KEY_SUBS_MODE, "off") ?: "off"

        fun setPlaybackPrefs(ctx: Context, audioLang: String, subsMode: String) {
            readerPrefs(ctx).edit()
                .putString(KEY_AUDIO_LANG, audioLang)
                .putString(KEY_SUBS_MODE, subsMode)
                .apply()
        }

        /** auto | direct | compatibility. Une valeur inconnue migre sans risque vers auto. */
        fun playbackStrategy(ctx: Context): String {
            val stored = readerPrefs(ctx).getString(KEY_PLAYBACK_STRATEGY, "auto")
            return stored?.takeIf { it in setOf("auto", "direct", "compatibility") } ?: "auto"
        }

        fun setPlaybackStrategy(ctx: Context, value: String) {
            if (value !in setOf("auto", "direct", "compatibility")) return
            readerPrefs(ctx).edit().putString(KEY_PLAYBACK_STRATEGY, value).apply()
        }

        fun autoPlayNextEpisode(ctx: Context): Boolean =
            readerPrefs(ctx).getBoolean(KEY_AUTO_PLAY_NEXT, true)

        fun playerActionHints(ctx: Context): Boolean =
            readerPrefs(ctx).getBoolean("player_action_hints", false)

        fun playerFocusContrast(ctx: Context): Boolean = readerPrefs(ctx).getBoolean("player_focus_contrast", false)
        fun setPlayerFocusContrast(ctx: Context, enabled: Boolean) {
            readerPrefs(ctx).edit().putBoolean("player_focus_contrast", enabled).apply()
        }

        fun setPlayerActionHints(ctx: Context, enabled: Boolean) {
            readerPrefs(ctx).edit().putBoolean("player_action_hints", enabled).apply()
        }

        fun audioOutputMode(ctx: Context): String =
            readerPrefs(ctx).getString(KEY_AUDIO_OUTPUT_MODE, "auto")
                ?.takeIf { it in setOf("auto", "passthrough", "compatible") } ?: "auto"

        fun setAudioOutputMode(ctx: Context, value: String) {
            if (value in setOf("auto", "passthrough", "compatible"))
                readerPrefs(ctx).edit().putString(KEY_AUDIO_OUTPUT_MODE, value).apply()
        }

        fun setAutoPlayNextEpisode(ctx: Context, enabled: Boolean) = readerPrefs(ctx).edit()
            .putBoolean(KEY_AUTO_PLAY_NEXT, enabled)
            .apply()

        // --- Filtres de la bibliothèque (persistés) -------------------------- //
        private const val KEY_SORT = "lib_sort"        // title | added | year | type
        private const val KEY_PHOTO_FOLDER = "photo_folder"
        private const val KEY_TYPE = "lib_type"        // all | movie | series
        private const val KEY_WATCHED = "lib_watched"  // all | unwatched | watched

        fun librarySort(ctx: Context): String = prefs(ctx).getString(KEY_SORT, "title") ?: "title"
        /** Chaîne vide : toutes les images, sans imposer un dossier. */
        private fun photoFolderKey(ctx: Context): String = "$KEY_PHOTO_FOLDER:${serverBaseUrl(ctx)}"

        fun photoFolder(ctx: Context): String {
            val preferences = prefs(ctx)
            val key = photoFolderKey(ctx)
            if (preferences.contains(key)) return preferences.getString(key, "").orEmpty()
            val legacy = preferences.getString(KEY_PHOTO_FOLDER, "").orEmpty()
            if (preferences.contains(KEY_PHOTO_FOLDER)) {
                preferences.edit().putString(key, legacy).remove(KEY_PHOTO_FOLDER).apply()
            }
            return legacy
        }
        fun libraryType(ctx: Context): String = prefs(ctx).getString(KEY_TYPE, "all") ?: "all"
        fun libraryWatched(ctx: Context): String = prefs(ctx).getString(KEY_WATCHED, "all") ?: "all"

        fun setLibrarySort(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_SORT, v).apply()
        fun setPhotoFolder(ctx: Context, folder: String) = prefs(ctx).edit()
            .putString(photoFolderKey(ctx), folder)
            .apply()
        fun setLibraryType(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_TYPE, v).apply()
        fun setLibraryWatched(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_WATCHED, v).apply()

        // --- Interface : expérience épurée par défaut, outils historiques en option. --- //
        private const val KEY_ADVANCED_MODE = "advanced_mode"
        private const val KEY_LIBRARY_TAB = "library_tab"
        private const val KEY_HOME_SHELF_ORDER = "home_shelf_order"
        private const val KEY_HOME_HIDDEN_SHELVES = "home_hidden_shelves"
        val HOME_SHELF_IDS = listOf("continue", "up_next", "recent", "movies", "series")

        fun advancedMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ADVANCED_MODE, false)

        fun setAdvancedMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit()
            .putBoolean(KEY_ADVANCED_MODE, enabled)
            .apply()

        fun libraryTab(ctx: Context): String {
            val scopedKey = "$KEY_LIBRARY_TAB:${serverBaseUrl(ctx)}"
            val stored = prefs(ctx).getString(scopedKey, null) ?: prefs(ctx).getString(KEY_LIBRARY_TAB, null)
            if (stored in setOf("home", "movie", "series", "clip", "photo", "audio")) return stored!!
            // Migration douce : respecte l'ancien filtre Film/Série au premier lancement.
            return libraryType(ctx).takeIf { it == "movie" || it == "series" } ?: "home"
        }

        fun setLibraryTab(ctx: Context, value: String) {
            if (value !in setOf("home", "movie", "series", "clip", "photo", "audio")) return
            prefs(ctx).edit().putString("$KEY_LIBRARY_TAB:${serverBaseUrl(ctx)}", value).apply()
        }

        /** Préférences propres à chaque TV/profil Android, sans modifier la bibliothèque familiale. */
        fun homeShelfOrder(ctx: Context): List<String> {
            val stored = prefs(ctx).getString(KEY_HOME_SHELF_ORDER, "").orEmpty()
                .split(',').map(String::trim).filter { it in HOME_SHELF_IDS }.distinct()
            return (stored + HOME_SHELF_IDS).distinct()
        }

        fun hiddenHomeShelves(ctx: Context): Set<String> = prefs(ctx)
            .getStringSet(KEY_HOME_HIDDEN_SHELVES, emptySet()).orEmpty()
            .filterTo(LinkedHashSet()) { it in HOME_SHELF_IDS }

        fun saveHomeShelves(ctx: Context, order: List<String>, hidden: Set<String>) {
            val normalizedOrder = (order.filter { it in HOME_SHELF_IDS } + HOME_SHELF_IDS).distinct()
            prefs(ctx).edit()
                .putString(KEY_HOME_SHELF_ORDER, normalizedOrder.joinToString(","))
                .putStringSet(KEY_HOME_HIDDEN_SHELVES, hidden.filter { it in HOME_SHELF_IDS }.toSet())
                .apply()
        }
    }
}
