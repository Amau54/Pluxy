package com.pluxy.receiver.core

/** A route switch must neither relabel a load nor let its old controller stop the new TV. */
class ReceiverIdentity(private val preferredId: String?) {
    private var loadedId: String? = null
    fun load(actualId: String) {
        check(preferredId == null || preferredId == actualId) { "L'appareil Google Cast a changé. Sélectionnez-le à nouveau." }
        loadedId = actualId
    }
    fun owns(actualId: String?): Boolean = loadedId != null && loadedId == actualId
    val loaded: Boolean get() = loadedId != null
}
