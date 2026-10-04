package com.pluxy.tv.ui

import com.pluxy.tv.api.ServerInfo

/** Discovery must never silently replace the selected library with an arbitrary server. */
internal object ServerReconnectPolicy {
    fun select(servers: List<ServerInfo>, selectedUrl: String, selectedName: String): ServerInfo? {
        servers.firstOrNull { it.baseUrl.trimEnd('/') == selectedUrl.trimEnd('/') }?.let { return it }
        // Older servers expose a name, not a stable ID. A default name is not an identity.
        val name = selectedName.trim()
        if (name.isBlank() || name.equals("Pluxy", ignoreCase = true)) return null
        return servers.filter { it.name.trim() == name }.singleOrNull()
    }
}
