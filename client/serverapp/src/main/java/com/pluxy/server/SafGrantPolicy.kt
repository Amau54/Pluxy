package com.pluxy.server

internal object SafGrantPolicy {
    fun canRelease(uri: String, remainingReferences: Collection<String>): Boolean = uri !in remainingReferences
}
