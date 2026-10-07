package com.pluxy.tv.api

import android.content.Context
import coil.ImageLoader
import com.pluxy.tv.PluxyApplication
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Charge uniquement les miniatures et photos du serveur LAN actuellement sélectionné. */
object LanMediaImages {
    @Volatile
    private var cached: Pair<String, ImageLoader>? = null

    @Synchronized
    fun loader(context: Context): ImageLoader {
        val appContext = context.applicationContext
        val baseUrl = PluxyApplication.serverBaseUrl(appContext)
        cached?.takeIf { it.first == baseUrl }?.let { return it.second }
        val serverHost = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
        val sameServerOnly = Interceptor { chain ->
            if (!LanUrlPolicy.isSameServer(baseUrl, chain.request().url.toString())) {
                throw IOException("Image hors du serveur Pluxy sélectionné")
            }
            chain.proceed(chain.request())
        }
        // Une rafale de cartes peut arriver pendant qu'Android décode déjà deux images.
        // Le serveur répond alors 503 + Retry-After au lieu de saturer sa RAM : on retente
        // uniquement les miniatures LAN, avec des délais progressifs et bornés.
        val thumbnailRetry = Interceptor { chain ->
            val request = chain.request()
            var response = chain.proceed(request)
            if ((request.url.encodedPath.startsWith("/image/thumbnail/") ||
                    request.url.encodedPath.matches(Regex("/api/library/items/[^/]+/thumbnail")))) {
                for (delayMs in THUMBNAIL_RETRY_DELAYS_MS) {
                    if (response.code != 503) break
                    response.close()
                    try {
                        Thread.sleep(delayMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Chargement de miniature interrompu", interrupted)
                    }
                    response = chain.proceed(request)
                }
            }
            response
        }
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    if (!hostname.equals(serverHost, ignoreCase = true)) {
                        throw UnknownHostException("Hôte différent du serveur Pluxy sélectionné")
                    } else {
                        LanUrlPolicy.resolveTrustedHost(hostname)
                    }
            })
            .addInterceptor(sameServerOnly)
            .addInterceptor(thumbnailRetry)
            .addNetworkInterceptor(sameServerOnly)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return ImageLoader.Builder(appContext)
            .okHttpClient(http)
            .crossfade(true)
            .build()
            .also { cached = baseUrl to it }
    }

    private val THUMBNAIL_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1500L, 3000L)
}
