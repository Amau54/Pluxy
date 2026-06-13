package com.pluxy.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.Discovery
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de connexion : découvre automatiquement le(s) serveur(s) Pluxy sur le LAN.
 *  - 1 serveur  -> connexion automatique
 *  - plusieurs  -> l'utilisateur choisit
 *  - aucun      -> saisie manuelle de l'IP
 */
class ServerSetupActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_setup)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.serverList)

        findViewById<Button>(R.id.rescan).setOnClickListener { runDiscovery() }
        findViewById<Button>(R.id.connectManual).setOnClickListener { connectManual() }

        runDiscovery()
    }

    private fun runDiscovery() {
        list.removeAllViews()
        progress.visibility = View.VISIBLE
        status.text = "Recherche du serveur sur le réseau…"
        lifecycleScope.launch {
            val servers = Discovery.discover(this@ServerSetupActivity)
            progress.visibility = View.GONE
            when {
                servers.isEmpty() ->
                    status.text = "Aucun serveur trouvé automatiquement. " +
                        "Vérifiez que le PC serveur est allumé, ou saisissez l'IP ci-dessous."
                servers.size == 1 -> {
                    status.text = "Serveur trouvé : ${servers[0].name}"
                    select(servers[0])              // connexion auto
                }
                else -> {
                    status.text = "${servers.size} serveurs trouvés — choisissez :"
                    servers.forEach { addServerButton(it) }
                }
            }
        }
    }

    private fun addServerButton(info: ServerInfo) {
        val d = resources.displayMetrics.density
        val b = Button(this).apply {
            text = "${info.name}  —  ${info.ip}:${info.port}"
            setTextColor(0xFFF5F7FB.toInt())
            textSize = 16f
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_secondary)
            stateListAnimator = android.animation.AnimatorInflater
                .loadStateListAnimator(this@ServerSetupActivity, R.animator.focus_lift_btn)
            isFocusable = true
            minHeight = (52 * d).toInt()
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
            setOnClickListener { select(info) }
        }
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * d).toInt() }
        list.addView(b)
    }

    private fun select(info: ServerInfo) {
        PluxyApplication.setServer(this, info.baseUrl, info.name)
        Toast.makeText(this, "Connecté à ${info.name}", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun connectManual() {
        val raw = findViewById<android.widget.EditText>(R.id.manualIp).text.toString().trim()
        if (raw.isEmpty()) return
        val url = when {
            raw.startsWith("http") -> raw
            raw.contains(":") -> "http://$raw"
            else -> "http://$raw:8420"
        }
        progress.visibility = View.VISIBLE
        status.text = "Connexion à $url…"
        lifecycleScope.launch {
            // Valide en interrogeant /api/server/info.
            val info = withContext(Dispatchers.IO) { PluxyApi(url).serverInfo() }
            progress.visibility = View.GONE
            if (info != null) {
                // On garde l'hôte saisi (sûr d'être joignable) + le nom/port du serveur.
                select(ServerInfo(name = info.name, ip = hostOf(url), port = portOf(url, info.port)))
            } else {
                status.text = "Impossible de joindre $url. Vérifiez l'IP et le port."
            }
        }
    }

    private fun hostOf(url: String): String =
        url.removePrefix("http://").removePrefix("https://").substringBefore(":").substringBefore("/")

    private fun portOf(url: String, fallback: Int): Int =
        url.removePrefix("http://").removePrefix("https://").substringBefore("/")
            .substringAfter(":", "").toIntOrNull() ?: fallback
}
