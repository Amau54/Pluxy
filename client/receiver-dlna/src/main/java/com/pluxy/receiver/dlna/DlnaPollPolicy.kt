package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.RemotePlaybackPhase
import java.util.Locale

internal class DlnaPollPolicy(private val failureBudget: Int = 3) {
    private var failures = 0
    fun success() { failures = 0 }
    fun failed(): Boolean = ++failures >= failureBudget

    fun phase(value: String): RemotePlaybackPhase = when (value.trim().uppercase(Locale.ROOT)) {
        "PLAYING" -> RemotePlaybackPhase.PLAYING
        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemotePlaybackPhase.PAUSED
        "TRANSITIONING" -> RemotePlaybackPhase.BUFFERING
        "STOPPED", "NO_MEDIA_PRESENT" -> RemotePlaybackPhase.ENDED
        else -> throw IllegalStateException("État DLNA inconnu : ${value.take(80)}")
    }
}
