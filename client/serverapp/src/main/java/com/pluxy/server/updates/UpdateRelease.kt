package com.pluxy.server.updates

import org.json.JSONObject
import java.io.IOException

internal data class UpdateRelease(val version: String, val code: Long, val hash: String, val certificates: Set<String>) {
    companion object {
        fun parse(json: String, packageName: String, sdk: Int): UpdateRelease {
            val root = JSONObject(json)
            if (root.getInt("schema") != 1 || root.getString("channel") != "debug") throw IOException("Publication Android invalide.")
            val items = root.getJSONArray("artifacts")
            val matching = (0 until items.length()).map { items.getJSONObject(it) }
                .filter { it.getString("name") == "Pluxy-debug.apk" && it.getString("package") == packageName }
            if (matching.size != 1) throw IOException("Aucune mise à jour compatible avec cette application.")
            val item = matching.single()
            if (item.getInt("min_sdk") > sdk) throw IOException("Cette version demande un Android plus récent.")
            val code = item.getLong("version_code")
            val hash = item.getString("sha256").lowercase()
            val version = item.getString("version_name")
            val certs = item.getJSONArray("signing_cert_sha256").let { array ->
                (0 until array.length()).map { array.getString(it).lowercase() }.toSet()
            }
            if (code <= 0 || !version.matches(Regex("\\d+\\.\\d+\\.\\d+")) || !validHash(hash) || certs.isEmpty() || certs.any { !validHash(it) })
                throw IOException("Informations de mise à jour invalides.")
            return UpdateRelease(version, code, hash, certs)
        }
        private fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    }
}
