package com.pluxy.tv.api

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Limites apprises pour CET appareil, sans identifiant utilisateur ni donnée distante. */
data class LearnedTvProfile(
    val unsupportedVideoCodecs: Set<String> = emptySet(),
    val unsupportedContainers: Set<String> = emptySet(),
    val failureCounts: Map<String, Int> = emptyMap(),
    val maxStableBitrateMbps: Int? = null,
    val stableEvidence: Int = 0,
    val lastUpdatedAtMs: Long = 0,
    val revision: Int = 0,
)

object TvProfilePolicy {
    fun apply(
        detected: ClientCapabilities,
        learned: LearnedTvProfile,
        profileId: String,
        profileName: String,
    ): ClientCapabilities {
        val blockedVideo = learned.unsupportedVideoCodecs.mapTo(hashSetOf()) { normalizeCodec(it) }
        val video = detected.supportedVideoCodecs.filterNot { normalizeCodec(it) in blockedVideo }
        val containers = detected.supportedContainers.filterNot {
            it.lowercase() in learned.unsupportedContainers
        }
        val effectiveBitrate = listOfNotNull(
            detected.maxBitrateMbps,
            learned.maxStableBitrateMbps,
        ).minOrNull()
        return detected.copy(
            supportsHevc = detected.supportsHevc && "hevc" !in blockedVideo,
            supportsHevc10bit = detected.supportsHevc10bit && "hevc10" !in blockedVideo &&
                "hevc" !in blockedVideo,
            supportsH264 = detected.supportsH264 && "h264" !in blockedVideo,
            supportsAv1 = detected.supportsAv1 && "av1" !in blockedVideo,
            supportedVideoCodecs = video,
            supportedContainers = containers,
            maxBitrateMbps = effectiveBitrate,
            profileId = profileId,
            profileName = profileName,
            profileRevision = learned.revision,
        )
    }

    /** Une erreur « format non supporté » est déterministe ; une erreur générique doit se répéter. */
    fun recordFormatFailure(
        current: LearnedTvProfile,
        codec: String?,
        container: String?,
        deterministic: Boolean,
        nowMs: Long,
    ): LearnedTvProfile {
        val key = listOfNotNull(codec?.let(::normalizeCodec), container?.lowercase())
            .joinToString("|").ifBlank { "unknown" }
        val count = (current.failureCounts[key] ?: 0) + 1
        val confirmed = deterministic || count >= 2
        return current.copy(
            unsupportedVideoCodecs = if (confirmed && !codec.isNullOrBlank()) {
                current.unsupportedVideoCodecs + normalizeCodec(codec)
            } else current.unsupportedVideoCodecs,
            unsupportedContainers = if (confirmed && !container.isNullOrBlank()) {
                current.unsupportedContainers + container.lowercase()
            } else current.unsupportedContainers,
            failureCounts = current.failureCounts + (key to count),
            lastUpdatedAtMs = nowMs,
            revision = current.revision + 1,
        )
    }

    fun recordNetworkConstraint(
        current: LearnedTvProfile,
        sourceBitrateMbps: Double,
        nowMs: Long,
    ): LearnedTvProfile {
        if (sourceBitrateMbps <= 0.0) return current
        val learnedLimit = (sourceBitrateMbps * 0.70).roundToInt().coerceIn(5, 200)
        return current.copy(
            maxStableBitrateMbps = minOf(current.maxStableBitrateMbps ?: learnedLimit, learnedLimit),
            stableEvidence = 0,
            lastUpdatedAtMs = nowMs,
            revision = current.revision + 1,
        )
    }

    /** Deux longues lectures stables permettent une remontée prudente, jamais brutale. */
    fun recordStablePlayback(
        current: LearnedTvProfile,
        sourceBitrateMbps: Double,
        nowMs: Long,
    ): LearnedTvProfile {
        val cap = current.maxStableBitrateMbps ?: return current
        if (sourceBitrateMbps < cap * 0.85) return current
        val evidence = current.stableEvidence + 1
        val raised = if (evidence >= 2) {
            minOf(sourceBitrateMbps.roundToInt().coerceAtLeast(cap), (cap * 1.20).roundToInt())
        } else cap
        return current.copy(
            maxStableBitrateMbps = raised,
            stableEvidence = if (evidence >= 2) 0 else evidence,
            lastUpdatedAtMs = nowMs,
            revision = current.revision + 1,
        )
    }

    fun normalizeCodec(value: String): String = when (value.lowercase()) {
        "avc", "video/avc", "h.264" -> "h264"
        "h265", "video/hevc", "h.265" -> "hevc"
        "av01", "video/av01" -> "av1"
        else -> value.lowercase().substringAfter('/')
    }
}

class TvProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val profileId: String by lazy {
        val raw = listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT)
            .joinToString("|") { it.orEmpty().trim().lowercase() }
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    val profileName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL)
            .mapNotNull { value -> value?.trim()?.takeIf { it.isNotBlank() } }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { "Téléviseur Android" }

    @Synchronized
    fun load(): LearnedTvProfile {
        val raw = prefs.getString(key(), null) ?: return LearnedTvProfile()
        return runCatching {
            val json = JSONObject(raw)
            LearnedTvProfile(
                unsupportedVideoCodecs = json.stringSet("unsupported_video_codecs"),
                unsupportedContainers = json.stringSet("unsupported_containers"),
                failureCounts = json.optJSONObject("failure_counts")?.let { counts ->
                    buildMap {
                        counts.keys().forEach { name -> put(name, counts.optInt(name, 0).coerceAtLeast(0)) }
                    }
                }.orEmpty(),
                maxStableBitrateMbps = json.optInt("max_stable_bitrate_mbps", 0).takeIf { it > 0 },
                stableEvidence = json.optInt("stable_evidence", 0).coerceIn(0, 2),
                lastUpdatedAtMs = json.optLong("last_updated_at_ms", 0).coerceAtLeast(0),
                revision = json.optInt("revision", 0).coerceAtLeast(0),
            )
        }.getOrDefault(LearnedTvProfile())
    }

    fun applyTo(detected: ClientCapabilities): ClientCapabilities =
        TvProfilePolicy.apply(detected, load(), profileId, profileName)

    @Synchronized
    fun recordFormatFailure(codec: String?, container: String?, deterministic: Boolean) = update {
        TvProfilePolicy.recordFormatFailure(it, codec, container, deterministic, System.currentTimeMillis())
    }

    @Synchronized
    fun recordNetworkConstraint(sourceBitrateMbps: Double) = update {
        TvProfilePolicy.recordNetworkConstraint(it, sourceBitrateMbps, System.currentTimeMillis())
    }

    @Synchronized
    fun recordStablePlayback(sourceBitrateMbps: Double) = update {
        TvProfilePolicy.recordStablePlayback(it, sourceBitrateMbps, System.currentTimeMillis())
    }

    @Synchronized
    fun reset() {
        prefs.edit().remove(key()).apply()
    }

    private fun update(block: (LearnedTvProfile) -> LearnedTvProfile) {
        save(block(load()))
    }

    private fun save(value: LearnedTvProfile) {
        val json = JSONObject().apply {
            put("unsupported_video_codecs", JSONArray(value.unsupportedVideoCodecs.sorted()))
            put("unsupported_containers", JSONArray(value.unsupportedContainers.sorted()))
            put("failure_counts", JSONObject(value.failureCounts))
            put("max_stable_bitrate_mbps", value.maxStableBitrateMbps ?: JSONObject.NULL)
            put("stable_evidence", value.stableEvidence)
            put("last_updated_at_ms", value.lastUpdatedAtMs)
            put("revision", value.revision)
        }
        prefs.edit().putString(key(), json.toString()).apply()
    }

    private fun key() = "profile_$profileId"

    companion object {
        private const val PREFS = "pluxy_tv_profiles"
    }
}

private fun JSONObject.stringSet(name: String): Set<String> {
    val array = optJSONArray(name) ?: return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            array.optString(index).trim().lowercase().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
