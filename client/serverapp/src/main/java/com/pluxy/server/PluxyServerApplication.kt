package com.pluxy.server

import android.app.Application

class PluxyServerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Cast est initialisé à la demande depuis l'écran Récepteurs. Le démarrage du
        // serveur reste ainsi indépendant de Google Play services.
    }

    companion object {
        lateinit var instance: PluxyServerApplication
            private set
    }
}
