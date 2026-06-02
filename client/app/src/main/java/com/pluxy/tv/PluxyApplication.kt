package com.pluxy.tv

import android.app.Application
import android.content.Context

/**
 * Application globale : conserve l'adresse du serveur Pluxy (modifiable par
 * l'utilisateur) et expose un point d'accès unique au client réseau.
 */
class PluxyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PluxyApplication
            private set

        private const val PREFS = "pluxy_prefs"
        private const val KEY_SERVER = "server_base_url"

        /** Valeur par défaut — à adapter à l'IP du PC serveur. */
        const val DEFAULT_SERVER = "http://192.168.1.20:8420"

        fun serverBaseUrl(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER

        fun setServerBaseUrl(ctx: Context, url: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER, url.trimEnd('/')).apply()
        }
    }
}
