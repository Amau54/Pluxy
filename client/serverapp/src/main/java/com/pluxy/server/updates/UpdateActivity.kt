package com.pluxy.server.updates

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.pluxy.server.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var progress: ProgressBar
    private var release: UpdateRelease? = null
    private var apk: File? = null
    private var busy = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) install()
        else { status.text = "Installation non autorisée. Autorisez Pluxy pour continuer."; action.isEnabled = true }
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (release?.code?.let { it <= AppUpdates.installedCode(this) } == true) {
            apk = null
            present()
        } else {
            status.text = "L’installation n’a pas été terminée. Vous pouvez réessayer."
            action.isEnabled = true
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(28); setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true; isFillViewport = true
            setBackgroundResource(R.drawable.server_bg_server_ambient)
            val television = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            addView(FrameLayout(this@UpdateActivity).apply {
                addView(column, FrameLayout.LayoutParams(if (television) dp(560) else -1, -2, Gravity.CENTER))
            })
        })
        column.addView(TextView(this).apply {
            text = "Mises à jour"; textSize = 25f
            setTextColor(ContextCompat.getColor(this@UpdateActivity, R.color.server_pluxy_text))
        })
        status = TextView(this).apply {
            textSize = 16f; setPadding(0, dp(18), 0, dp(18))
            setTextColor(ContextCompat.getColor(this@UpdateActivity, R.color.server_pluxy_muted))
        }
        column.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@UpdateActivity, R.color.server_pluxy_accent))
        }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply { bottomMargin = dp(14) })
        action = button("Vérifier à nouveau", column) {
            if (release?.code?.let { it > AppUpdates.installedCode(this) } == true) download() else check()
        }
        button("Plus tard", column) { finish() }
        if (savedInstanceState?.containsKey("code") == true) {
            release = UpdateRelease(savedInstanceState.getString("version").orEmpty(), savedInstanceState.getLong("code"), savedInstanceState.getString("hash").orEmpty(), savedInstanceState.getStringArrayList("certs").orEmpty().toSet())
            apk = File(cacheDir, "updates/pluxy-${release!!.code}.apk").takeIf { it.isFile }
            present()
        } else check()
        action.requestFocus()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        release?.let { outState.putString("version", it.version); outState.putLong("code", it.code); outState.putString("hash", it.hash); outState.putStringArrayList("certs", ArrayList(it.certificates)) }
        super.onSaveInstanceState(outState)
    }
    private fun button(label: String, column: LinearLayout, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false; minHeight = dp(48)
        gravity = Gravity.CENTER; stateListAnimator = null
        setBackgroundResource(R.drawable.server_bg_button_secondary)
        setTextColor(ContextCompat.getColor(this@UpdateActivity, R.color.server_pluxy_text))
        setPadding(dp(18), dp(8), dp(18), dp(8)); setOnClickListener { click() }
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private fun present() {
        val info = release ?: return
        val available = info.code > AppUpdates.installedCode(this)
        status.text = if (available) "Pluxy ${info.version} est disponible. Vos réglages et votre bibliothèque seront conservés." else "Pluxy ${AppUpdates.packageInfo(this).versionName} est à jour."
        action.text = if (available) "Télécharger et installer" else "Vérifier à nouveau"
        action.isEnabled = true
        action.requestFocus()
    }
    private fun check() {
        if (busy) return
        busy = true; action.isEnabled = false; status.text = "Recherche de la dernière version sur GitHub…"
        lifecycleScope.launch {
            try {
                val latest = AppUpdates.check(this@UpdateActivity)
                if (release != latest) apk = null
                release = latest
                present()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { release = null; status.text = "Impossible de vérifier les mises à jour. ${error.message.orEmpty()}" }
            finally { busy = false; action.isEnabled = true }
        }
    }
    private fun download() {
        if (busy) return
        val info = release ?: return
        val cached = File(cacheDir, "updates/pluxy-${info.code}.apk")
        if (apk == cached && cached.isFile) { install(); return }
        apk = null
        busy = true; action.isEnabled = false; progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                apk = AppUpdates.download(this@UpdateActivity, info) { percent ->
                    progress.progress = percent; status.text = "Téléchargement de Pluxy ${info.version} · $percent %"
                }
                install()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = error.message.orEmpty(); release = null; action.text = "Vérifier à nouveau"; action.isEnabled = true }
            finally { busy = false; progress.visibility = View.GONE }
        }
    }
    private fun install() {
        val info = release ?: return
        val file = apk ?: return
        action.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { AppUpdates.verify(this@UpdateActivity, file, info) }
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    status.text = "Autorisez Pluxy à installer ses mises à jour, puis revenez ici pour continuer."
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } else {
                    val uri = FileProvider.getUriForFile(this@UpdateActivity, "$packageName.updates", file)
                    status.text = "Confirmez la mise à jour dans la fenêtre Android."
                    installer.launch(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri("Mise à jour Pluxy", uri)
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = "Impossible de lancer l’installation. ${error.message.orEmpty()}"; apk = null; release = null; action.text = "Vérifier à nouveau"; action.isEnabled = true }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
