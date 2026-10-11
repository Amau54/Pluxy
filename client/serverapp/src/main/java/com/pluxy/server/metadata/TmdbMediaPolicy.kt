package com.pluxy.server.metadata

import org.json.JSONArray

internal object TmdbMediaPolicy {
    fun stills(season: org.json.JSONObject): Map<Int, String> {
        val episodes = season.optJSONArray("episodes") ?: return emptyMap()
        return buildMap {
            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue
                val number = episode.optInt("episode_number", 0)
                val path = episode.optString("still_path", "")
                if (number > 0 && path.matches(Regex("/[A-Za-z0-9._-]+"))) put(number, "https://image.tmdb.org/t/p/w780$path")
            }
        }
    }
    fun trailer(videos: JSONArray?, language: String = "fr"): String? {
        if (videos == null) return null
        return (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") &&
                it.optString("key").matches(Regex("[A-Za-z0-9_-]{11}")) }
            .maxByOrNull { (if (it.optString("iso_639_1") == language) 4 else 0) +
                (if (it.optString("type") == "Trailer") 2 else 0) + (if (it.optBoolean("official")) 1 else 0) }
            ?.optString("key")
    }
}
