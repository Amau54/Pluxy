package com.pluxy.tv.player

import androidx.media3.common.PlaybackException

/** A missing original file cannot be recovered by buffering or changing quality.
 * HLS 404/410 can be temporary segment/session expiry and must retain recovery. */
internal object PlaybackFailurePolicy {
    fun isVideoDecoderFailure(errorCode: Int, mimeType: String?): Boolean =
        isVideoRenderer(mimeType) && errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )

    fun canOfferVideoCompatibility(transcodingAvailable: Boolean, preserveAudio: Boolean): Boolean =
        transcodingAvailable && !preserveAudio

    fun isVideoRenderer(mimeType: String?): Boolean = mimeType?.startsWith("video/") == true

    fun requiresUserActionForPath(path: String?, httpStatus: Int?): Boolean =
        requiresUserAction(if (path?.startsWith("/stream/direct/") == true) "direct" else null, httpStatus)

    fun requiresUserAction(delivery: String?, httpStatus: Int?): Boolean =
        httpStatus in setOf(401, 403) ||
            (delivery == "direct" && httpStatus in setOf(404, 410))
}
