package com.pluxy.receiver.cast

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import com.pluxy.receiver.core.ReceiverCapabilities
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.ReceiverProtocol

@OptIn(UnstableApi::class)
object GoogleCastSupport {
    val conservativeCapabilities = ReceiverCapabilities(
        containers = setOf("mp4", "webm", "m4a", "mp3", "jpeg", "jpg", "png", "webp"),
        videoCodecs = setOf("h264", "vp8", "vp9"),
        audioCodecs = setOf("aac", "mp3", "opus", "vorbis"),
        imageMimeTypes = setOf("image/jpeg", "image/png", "image/webp"),
        maxVideoHeight = 1080,
        supportsHdr = false,
        source = "Google Cast prudent",
    )

    @MainThread
    fun initialize(context: Context) {
        Cast.getSingletonInstance(context.applicationContext).initialize()
    }

    @MainThread
    fun setUpRouteButton(context: Context, button: MediaRouteButton) {
        initialize(context)
        // Le résultat asynchrone n'est pas bloquant : le bouton se met à jour dès
        // que Google Play services termine la découverte locale.
        @Suppress("UNUSED_VARIABLE")
        val unused = MediaRouteButtonFactory.setUpMediaRouteButton(context, button)
    }

    @MainThread
    fun currentDevice(context: Context): ReceiverDevice? {
        val cast = Cast.getSingletonInstance(context.applicationContext)
        val session = cast.currentCastSession ?: return null
        val remote = session.castDevice ?: return null
        val id = remote.deviceId?.takeIf { it.isNotBlank() } ?: return null
        return ReceiverDevice(
            id = "cast:$id",
            name = remote.friendlyName?.takeIf { it.isNotBlank() } ?: "Google Cast",
            protocol = ReceiverProtocol.GOOGLE_CAST,
            description = remote.modelName,
            capabilities = conservativeCapabilities,
        )
    }
}
