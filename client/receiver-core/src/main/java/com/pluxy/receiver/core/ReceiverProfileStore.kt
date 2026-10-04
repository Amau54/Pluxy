package com.pluxy.receiver.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class LearnedReceiverProfile(
    val validatedContainers: Set<String> = emptySet(),
    val validatedVideoCodecs: Set<String> = emptySet(),
    val validatedAudioCodecs: Set<String> = emptySet(),
    val validatedImageMimeTypes: Set<String> = emptySet(),
    val rejectedContainers: Set<String> = emptySet(),
    val rejectedVideoCodecs: Set<String> = emptySet(),
    val rejectedAudioCodecs: Set<String> = emptySet(),
    val rejectedImageMimeTypes: Set<String> = emptySet(),
    val stableVideoHeight: Int? = null,
    val validatedHdr: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0,
)

object ReceiverProfilePolicy {
    fun apply(
        capabilities: ReceiverCapabilities,
        learned: LearnedReceiverProfile,
    ): ReceiverCapabilities = capabilities.copy(
        containers = (capabilities.containers + learned.validatedContainers) - learned.rejectedContainers,
        videoCodecs = (capabilities.videoCodecs + learned.validatedVideoCodecs) - learned.rejectedVideoCodecs,
        audioCodecs = (capabilities.audioCodecs + learned.validatedAudioCodecs) - learned.rejectedAudioCodecs,
        imageMimeTypes = (capabilities.imageMimeTypes + learned.validatedImageMimeTypes) -
            learned.rejectedImageMimeTypes,
        // Une réussite ne crée aucun plafond inférieur. Elle peut seulement prouver
        // qu'une résolution supérieure au profil prudent fonctionne réellement.
        maxVideoHeight = listOfNotNull(capabilities.maxVideoHeight, learned.stableVideoHeight).maxOrNull(),
        supportsHdr = capabilities.supportsHdr || learned.validatedHdr,
        source = if (learned.successCount + learned.failureCount > 0) {
            "${capabilities.source}+appris"
        } else capabilities.source,
        rejectedContainers = capabilities.rejectedContainers + learned.rejectedContainers,
        rejectedVideoCodecs = capabilities.rejectedVideoCodecs + learned.rejectedVideoCodecs,
        rejectedAudioCodecs = capabilities.rejectedAudioCodecs + learned.rejectedAudioCodecs,
        rejectedImageMimeTypes = capabilities.rejectedImageMimeTypes + learned.rejectedImageMimeTypes,
    )

    fun recordFailure(
        profile: LearnedReceiverProfile,
        media: RemoteMedia,
        component: UnsupportedFormatComponent,
    ): LearnedReceiverProfile = when (component) {
        UnsupportedFormatComponent.CONTAINER -> profile.copy(
            rejectedContainers = profile.rejectedContainers + media.container.normalized(),
            failureCount = profile.failureCount + 1,
        )
        UnsupportedFormatComponent.VIDEO -> profile.copy(
            rejectedVideoCodecs = profile.rejectedVideoCodecs + listOfNotNull(media.videoCodec?.normalized()),
            failureCount = profile.failureCount + 1,
        )
        UnsupportedFormatComponent.AUDIO -> profile.copy(
            rejectedAudioCodecs = profile.rejectedAudioCodecs + listOfNotNull(media.audioCodec?.normalized()),
            failureCount = profile.failureCount + 1,
        )
        UnsupportedFormatComponent.IMAGE -> profile.copy(
            rejectedImageMimeTypes = profile.rejectedImageMimeTypes + media.mimeType.normalizedMime(),
            failureCount = profile.failureCount + 1,
        )
    }

    fun recordSuccess(
        profile: LearnedReceiverProfile,
        media: RemoteMedia,
    ): LearnedReceiverProfile {
        val isVideo = media.kind.lowercase(Locale.ROOT) in setOf("movie", "series", "video")
        return profile.copy(
            validatedContainers = profile.validatedContainers + media.container.normalized(),
            validatedVideoCodecs = profile.validatedVideoCodecs + listOfNotNull(media.videoCodec?.normalized()),
            validatedAudioCodecs = profile.validatedAudioCodecs + listOfNotNull(media.audioCodec?.normalized()),
            validatedImageMimeTypes = profile.validatedImageMimeTypes + listOfNotNull(
                media.mimeType.normalizedMime().takeIf { media.kind.equals("photo", ignoreCase = true) },
            ),
            stableVideoHeight = if (isVideo) {
                maxOf(profile.stableVideoHeight ?: 0, media.height ?: 0).takeIf { it > 0 }
            } else profile.stableVideoHeight,
            validatedHdr = profile.validatedHdr || (isVideo && media.isHdr),
            successCount = profile.successCount + 1,
            rejectedContainers = profile.rejectedContainers - media.container.normalized(),
            rejectedVideoCodecs = profile.rejectedVideoCodecs - listOfNotNull(media.videoCodec?.normalized()).toSet(),
            rejectedAudioCodecs = profile.rejectedAudioCodecs - listOfNotNull(media.audioCodec?.normalized()).toSet(),
            rejectedImageMimeTypes = if (media.kind.equals("photo", true)) {
                profile.rejectedImageMimeTypes - media.mimeType.normalizedMime()
            } else profile.rejectedImageMimeTypes,
        )
    }

    private fun String.normalized() = normalizeReceiverFormat(this)
    private fun String.normalizedMime() = lowercase(Locale.ROOT).substringBefore(';').trim()
}

/** Profil local par récepteur. L'identifiant SSDP/Cast est haché avant stockage. */
class ReceiverProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun effective(device: ReceiverDevice): ReceiverDevice = device.copy(
        capabilities = ReceiverProfilePolicy.apply(device.capabilities, load(device.id)),
    )

    fun recordSuccess(device: ReceiverDevice, media: RemoteMedia) {
        save(device.id, ReceiverProfilePolicy.recordSuccess(load(device.id), media))
    }

    fun recordFormatFailure(
        device: ReceiverDevice,
        media: RemoteMedia,
        component: UnsupportedFormatComponent,
    ) {
        save(device.id, ReceiverProfilePolicy.recordFailure(load(device.id), media, component))
    }

    fun reset(device: ReceiverDevice) {
        preferences.edit().remove(key(device.id)).apply()
    }

    private fun load(id: String): LearnedReceiverProfile = runCatching {
        val raw = preferences.getString(key(id), null) ?: return@runCatching LearnedReceiverProfile()
        val json = JSONObject(raw)
        LearnedReceiverProfile(
            validatedContainers = json.optJSONArray("validated_containers").strings(),
            validatedVideoCodecs = json.optJSONArray("validated_video").strings(),
            validatedAudioCodecs = json.optJSONArray("validated_audio").strings(),
            validatedImageMimeTypes = json.optJSONArray("validated_images").strings(),
            rejectedContainers = json.optJSONArray("containers").strings(),
            rejectedVideoCodecs = json.optJSONArray("video").strings(),
            rejectedAudioCodecs = json.optJSONArray("audio").strings(),
            rejectedImageMimeTypes = json.optJSONArray("images").strings(),
            stableVideoHeight = json.optInt("height", 0).takeIf { it > 0 },
            validatedHdr = json.optBoolean("hdr", false),
            successCount = json.optInt("success", 0),
            failureCount = json.optInt("failure", 0),
        )
    }.getOrDefault(LearnedReceiverProfile())

    private fun save(id: String, value: LearnedReceiverProfile) {
        val json = JSONObject().apply {
            put("validated_containers", JSONArray(value.validatedContainers.toList()))
            put("validated_video", JSONArray(value.validatedVideoCodecs.toList()))
            put("validated_audio", JSONArray(value.validatedAudioCodecs.toList()))
            put("validated_images", JSONArray(value.validatedImageMimeTypes.toList()))
            put("containers", JSONArray(value.rejectedContainers.toList()))
            put("video", JSONArray(value.rejectedVideoCodecs.toList()))
            put("audio", JSONArray(value.rejectedAudioCodecs.toList()))
            put("images", JSONArray(value.rejectedImageMimeTypes.toList()))
            put("height", value.stableVideoHeight ?: JSONObject.NULL)
            put("hdr", value.validatedHdr)
            put("success", value.successCount)
            put("failure", value.failureCount)
        }
        preferences.edit().putString(key(id), json.toString()).apply()
    }

    private fun key(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun JSONArray?.strings(): Set<String> = buildSet {
        val array = this@strings ?: return@buildSet
        for (index in 0 until array.length()) {
            array.optString(index).trim().lowercase(Locale.ROOT)
                .takeIf { candidate -> candidate.isNotBlank() }
                ?.let(::add)
        }
    }

    companion object { private const val PREFS = "pluxy_receiver_profiles_v1" }
}
