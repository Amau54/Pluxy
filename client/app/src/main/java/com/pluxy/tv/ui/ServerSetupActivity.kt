package com.pluxy.tv.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.Discovery
import com.pluxy.tv.api.PluxyApi
import com.pluxy.tv.api.ServerInfo
import com.pluxy.tv.api.LanUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de connexion : découvre automatiquement le(s) serveur(s) Pluxy sur le LAN.
 *  - 1 serveur  -> connexion automatique
 *  - plusieurs  -> l'utilisateur choisit
 *  - aucun      -> saisie manuelle de l'IP
 */
class ServerSetupActivity : ThemedActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_setup)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.serverList)

        findViewById<Button>(R.id.rescan).setOnClickListener { runDiscovery() }
        findViewById<Button>(R.id.connectManual).setOnClickListener { connectManual() }
        findViewById<android.widget.EditText>(R.id.manualIp).setOnEditorActionListener { input, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
                findViewById<Button>(R.id.connectManual).requestFocus()
                connectManual()
                true
            } else false
        }
        findViewById<Button>(R.id.rescan).requestFocus()

    }

    override fun onStart() {
        super.onStart()
        runDiscovery()
    }

    override fun onStop() {
        discoveryJob?.cancel()
        connectionJob?.cancel()
        super.onStop()
    }

    private fun runDiscovery() {
        discoveryJob?.cancel()
        connectionJob?.cancel()
        list.removeAllViews()
        progress.visibility = View.VISIBLE
        status.setText(R.string.server_setup_scanning)
        discoveryJob = lifecycleScope.launch {
            while (isActive) {
                val servers = Discovery.discover(
                    this@ServerSetupActivity,
                    timeoutMs = DISCOVERY_WINDOW_MS,
                )
                progress.visibility = View.GONE
                when {
                    servers.isEmpty() -> {
                        status.setText(R.string.server_none_found)
                        // Reste silencieux après le premier message : la recherche continue
                        // en arrière-plan et la TV se connecte dès que le serveur apparaît.
                        delay(DISCOVERY_RETRY_MS - DISCOVERY_WINDOW_MS.toLong())
                    }
                    servers.size == 1 -> {
                        status.text = getString(R.string.server_one_found, servers[0].name)
                        select(servers[0])              // connexion auto
                        return@launch
                    }
                    else -> {
                        status.text = resources.getQuantityString(
                            R.plurals.server_found_count,
                            servers.size,
                            servers.size,
                        )
                        servers.forEach { addServerButton(it) }
                        list.getChildAt(0)?.requestFocus()
                        return@launch
                    }
                }
            }
        }
    }

    private fun addServerButton(info: ServerInfo) {
        val d = resources.displayMetrics.density
        val b = Button(this).apply {
            text = getString(R.string.server_choice, info.name, info.ip, info.port)
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
        Toast.makeText(
            this,
            getString(R.string.server_connected, info.name),
            Toast.LENGTH_SHORT,
        ).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun connectManual() {
        val raw = findViewById<android.widget.EditText>(R.id.manualIp).text.toString().trim()
        if (raw.isEmpty()) return
        // A discovery response must not select another server during manual validation.
        discoveryJob?.cancel()
        connectionJob?.cancel()
        progress.visibility = View.VISIBLE
        status.setText(R.string.server_checking_address)
        connectionJob = lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching { LanUrlPolicy.normalizeServer(raw) }.getOrNull()
            }
            if (url == null) {
                progress.visibility = View.GONE
                status.setText(R.string.server_address_refused)
                return@launch
            }
            status.text = getString(R.string.server_connecting, url)
            val info = withContext(Dispatchers.IO) {
                val client = PluxyApi(url)
                try { client.serverInfo() } finally { client.close() }
            }
            progress.visibility = View.GONE
            if (info != null) {
                val parsed = java.net.URI(url)
                select(ServerInfo(name = info.name.take(60), ip = parsed.host, port = parsed.port))
            } else {
                status.text = getString(R.string.server_connection_failed, url)
            }
        }
    }

    private companion object {
        private const val DISCOVERY_RETRY_MS = 3_000L
        private const val DISCOVERY_WINDOW_MS = 1_000
    }
}
