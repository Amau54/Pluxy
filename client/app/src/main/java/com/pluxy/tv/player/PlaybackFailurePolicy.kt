package com.pluxy.tv.player

/** A missing original file cannot be recovered by buffering or changing quality.
 * HLS 404/410 can be temporary segment/session expiry and must retain recovery. */
internal object PlaybackFailurePolicy {
    fun requiresUserActionForPath(path: String?, httpStatus: Int?): Boolean =
        requiresUserAction(if (path?.startsWith("/stream/direct/") == true) "direct" else null, httpStatus)

    fun requiresUserAction(delivery: String?, httpStatus: Int?): Boolean =
        httpStatus in setOf(401, 403) ||
            (delivery == "direct" && httpStatus in setOf(404, 410))
}
