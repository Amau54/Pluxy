package com.pluxy.tv.player

import android.content.Context
import com.pluxy.tv.api.MediaItem
import org.json.JSONObject
import java.security.MessageDigest

class SkipMarkerStore(context: Context) {
    private val prefs = context.getSharedPreferences("skip_markers", Context.MODE_PRIVATE)
    private fun key(server: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest((server.trimEnd('/') + "\n" + value).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun mediaKey(server: String, item: MediaItem) = key(server, "media:" + item.id)
    private fun seasonKey(server: String, item: MediaItem): String? =
        if (item.kind == "series" && item.season != null && !item.seriesKey.isNullOrBlank())
            key(server, "season:${item.sourceId.orEmpty()}:${item.seriesKey}:${item.season}") else null
    private fun read(key: String): SkipMarkers = runCatching {
        val json = JSONObject(prefs.getString(key, "{}") ?: "{}")
        fun number(name: String): Long? = if (json.has(name)) json.optLong(name, -1).takeIf { it >= 0 } else null
        SkipMarkers(number("intro_start") ?: 0, number("intro_end"), number("credits_start"), number("credits_end"))
    }.getOrDefault(SkipMarkers())
    fun own(server: String, item: MediaItem) = read(mediaKey(server, item))
    fun effective(server: String, item: MediaItem): SkipMarkers {
        val own = own(server, item)
        val season = seasonKey(server, item)?.let(::read) ?: SkipMarkers()
        return if (own.introEndMs == null && own.introStartMs == 0L) own.copy(introStartMs = season.introStartMs, introEndMs = season.introEndMs) else own
    }
    fun hasSeason(server: String, item: MediaItem) = seasonKey(server, item)?.let { read(it).introEndMs != null } == true
    fun canShare(item: MediaItem) = seasonKey("", item) != null
    private fun write(key: String, markers: SkipMarkers) {
        val json = JSONObject().put("intro_start", markers.introStartMs)
        markers.introEndMs?.let { json.put("intro_end", it) }
        markers.creditsStartMs?.let { json.put("credits_start", it) }
        markers.creditsEndMs?.let { json.put("credits_end", it) }
        val edit = prefs.edit().putString(key, json.toString())
        if (!prefs.contains(key) && prefs.all.size >= 2000) prefs.all.keys.firstOrNull()?.let(edit::remove)
        check(edit.commit())
    }
    fun save(server: String, item: MediaItem, markers: SkipMarkers) = write(mediaKey(server, item), markers)
    fun share(server: String, item: MediaItem, markers: SkipMarkers) {
        val key = seasonKey(server, item) ?: return
        write(key, SkipMarkers(markers.introStartMs, markers.introEndMs))
    }
    fun clear(server: String, item: MediaItem) { check(prefs.edit().remove(mediaKey(server, item)).commit()) }
    fun clearSeason(server: String, item: MediaItem) { seasonKey(server, item)?.let { check(prefs.edit().remove(it).commit()) } }
}
