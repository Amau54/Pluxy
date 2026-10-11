package com.pluxy.server.updates

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object AppUpdates {
    internal const val BASE = "https://github.com/Amau54/Pluxy/releases/download/dev-latest/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = WeakReference<Activity>(null)
    private var checking = false
    private var latest: UpdateRelease? = null
    private var nextCheck = 0L
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || url.port != 443 || url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
                throw IOException("Source de téléchargement invalide.")
            chain.proceed(chain.request())
        }.build()

    fun register(application: Application, eligible: Set<Class<out Activity>>) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
                if (activity.javaClass !in eligible) return
                scope.launch {
                    val now = System.currentTimeMillis()
                    if (now >= nextCheck && !checking) {
                        checking = true
                        try { latest = check(application); nextCheck = now + TimeUnit.HOURS.toMillis(8) }
                        catch (_: Exception) { nextCheck = now + TimeUnit.HOURS.toMillis(1) }
                        finally { checking = false }
                    }
                    val release = latest ?: return@launch
                    if (release.code <= installedCode(application) || foreground.get() !== activity || activity.isFinishing || activity.isDestroyed) return@launch
                    val prefs = application.getSharedPreferences("pluxy_updates", Context.MODE_PRIVATE)
                    if (now < prefs.getLong("offer_after", 0L)) return@launch
                    prefs.edit().putLong("offer_after", now + TimeUnit.HOURS.toMillis(24)).apply()
                    open(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
    fun open(context: Context) {
        context.getSharedPreferences("pluxy_updates", Context.MODE_PRIVATE).edit()
            .putLong("offer_after", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)).apply()
        context.startActivity(Intent(context, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    @Suppress("DEPRECATION")
    internal fun packageInfo(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    internal fun installedCode(context: Context) = PackageInfoCompat.getLongVersionCode(packageInfo(context))
    internal fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    internal fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { sha(it.toByteArray()) }.toSet()
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    internal suspend fun check(context: Context): UpdateRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE + "BUILD_INFO.json").header("Cache-Control", "no-cache").build()
        http.newCall(request).apply { timeout().timeout(20, TimeUnit.SECONDS) }.execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub est indisponible. Réessayez plus tard.")
            val body = response.body ?: throw IOException("Réponse vide.")
            if (body.contentLength() > 256 * 1024) throw IOException("Réponse de mise à jour invalide.")
            val text = body.byteStream().use { input ->
                val bytes = input.readBytesBounded(256 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
            val release = UpdateRelease.parse(text, context.packageName, Build.VERSION.SDK_INT)
            if (release.certificates != certificates(packageInfo(context))) throw IOException("Cette installation utilise une autre signature. Mettez-la à jour depuis son canal d’origine.")
            release
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break; if (output.size() + count > limit) throw IOException("Réponse trop volumineuse."); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    internal suspend fun download(context: Context, release: UpdateRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        if (release.code <= installedCode(context)) throw IOException("Pluxy est déjà à jour.")
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2) }?.forEach { it.delete() }
        val file = File(directory, "pluxy-${release.code}.apk")
        val partial = File(directory, "pluxy-${release.code}.part")
        val call = http.newCall(Request.Builder().url(BASE + "Pluxy-debug.apk").header("Cache-Control", "no-cache").build())
        val cancel = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Le téléchargement est indisponible.")
                val body = response.body ?: throw IOException("Réponse vide.")
                val size = body.contentLength()
                if (size > 128L * 1024 * 1024) throw IOException("Taille de téléchargement invalide.")
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L; var last = -1
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; if (total > 128L * 1024 * 1024) throw IOException("Taille de téléchargement invalide.")
                        output.write(buffer, 0, count)
                        val percent = if (size > 0) (total * 100 / size).toInt().coerceIn(0, 100) else 0
                        if (percent != last) { last = percent; withContext(Dispatchers.Main) { progress(percent) } }
                    }
                    if (size > 0 && total != size) throw IOException("Téléchargement incomplet.")
                } }
            }
            verify(context, partial, release)
            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) throw IOException("Impossible de préparer l’installation.")
            file
        } finally { cancel.cancel(); partial.delete() }
    }
    @Suppress("DEPRECATION")
    internal fun verify(context: Context, file: File, release: UpdateRelease) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != release.hash) throw IOException("Le téléchargement a changé ou est incomplet. Relancez la vérification.")
        val apk = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags()) ?: throw IOException("APK invalide.")
        if (apk.packageName != context.packageName || apk.versionName != release.version || PackageInfoCompat.getLongVersionCode(apk) != release.code || release.code <= installedCode(context) || certificates(apk) != certificates(packageInfo(context)))
            throw IOException("Cet APK n’est pas une mise à jour compatible.")
    }
}
