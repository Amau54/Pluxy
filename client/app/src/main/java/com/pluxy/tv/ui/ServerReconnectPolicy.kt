package com.pluxy.tv.ui

import com.pluxy.tv.api.ServerInfo
import com.pluxy.tv.api.ApiException
import java.io.IOException

/** Un seul serveur disponible se connecte automatiquement ; plusieurs laissent le choix. */
internal object ServerReconnectPolicy {
    fun select(servers: List<ServerInfo>): ServerInfo? = servers.distinctBy { it.baseUrl }.singleOrNull()

    fun isConnectionFailure(error: Exception): Boolean = error is IOException && error !is ApiException
}
