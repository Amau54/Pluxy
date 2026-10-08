package com.pluxy.tv.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

/** Une annonce UDP ne suffit pas : le serveur doit aussi répondre en HTTP. */
internal object ServerAvailability {
    const val TIMEOUT_MS = 2_000L

    suspend fun probe(url: String): ServerInfo? {
        val api = PluxyApi(url, timeoutMs = TIMEOUT_MS)
        return try {
            val info = api.serverInfo()?.takeIf { it.app == "pluxy" } ?: return null
            val address = URI(url)
            info.copy(name = info.name.take(60), ip = address.host, port = address.port.takeIf { it > 0 } ?: 80)
        } finally { api.close() }
    }

    suspend fun available(
        servers: List<ServerInfo>,
        probe: suspend (String) -> ServerInfo? = ::probe,
    ): List<ServerInfo> = coroutineScope {
        val limit = Semaphore(4)
        servers.distinctBy { it.baseUrl }.take(16).map { server ->
            async { limit.withPermit { probe(server.baseUrl)?.takeIf { it.app == "pluxy" } } }
        }.awaitAll().filterNotNull().sortedWith(compareBy({ it.name.lowercase() }, { it.baseUrl }))
    }
}
