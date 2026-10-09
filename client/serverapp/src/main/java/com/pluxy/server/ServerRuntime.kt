package com.pluxy.server

import com.pluxy.server.library.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ServerSnapshot(
    val running: Boolean = false,
    val starting: Boolean = false,
    val scanning: Boolean = false,
    val mediaCount: Int = 0,
    val address: String? = null,
    val wifiConnected: Boolean = false,
    val error: String? = null,
    val metadataStatus: String = "",
)

object ServerRuntime {
    private val mutable = MutableStateFlow(ServerSnapshot())
    val state = mutable.asStateFlow()
    private val mutableCatalog = MutableStateFlow<List<MediaEntry>>(emptyList())
    val catalog = mutableCatalog.asStateFlow()

    fun update(transform: (ServerSnapshot) -> ServerSnapshot) {
        mutable.update(transform)
    }

    fun replace(snapshot: ServerSnapshot) {
        mutable.value = snapshot
    }

    fun replaceCatalog(items: List<MediaEntry>) {
        mutableCatalog.value = items.toList()
    }
}
