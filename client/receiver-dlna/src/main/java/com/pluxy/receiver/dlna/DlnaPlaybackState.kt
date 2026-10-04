package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.RemoteMedia
import com.pluxy.receiver.core.RemotePlaybackPhase
import com.pluxy.receiver.core.RemotePlaybackStatus

internal object DlnaPlaybackState {
    fun connecting(device: ReceiverDevice, media: RemoteMedia): RemotePlaybackStatus = RemotePlaybackStatus(
        device = device,
        media = media,
        phase = RemotePlaybackPhase.CONNECTING,
        positionMs = 0,
        durationMs = media.durationMs.coerceAtLeast(0),
        error = null,
    )
}
