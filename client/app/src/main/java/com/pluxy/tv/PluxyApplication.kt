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
            prefs(ctx).edit()
                .putString(KEY_SERVER, url.trimEnd('/'))
                .putString(KEY_NAME, name)
                .apply()
        }
    }
}
