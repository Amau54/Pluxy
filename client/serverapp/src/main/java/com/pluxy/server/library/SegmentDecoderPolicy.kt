package com.pluxy.server.library

internal object SegmentDecoderPolicy {
    // Some Android builds report false for Google's own software AVC decoder.
    fun software(name: String, reportedSoftware: Boolean): Boolean = reportedSoftware ||
        name.startsWith("OMX.google.") || name.startsWith("c2.android.")
}
