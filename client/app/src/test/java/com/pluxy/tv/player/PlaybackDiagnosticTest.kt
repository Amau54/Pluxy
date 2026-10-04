package com.pluxy.tv.player

import com.pluxy.tv.api.PlaybackDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackDiagnosticTest {
    @Test
    fun `direct play est presente comme qualite originale`() {
        val ui = PlaybackDiagnostic.from(decision(mode = "direct_play", summary = "Direct Play · qualité originale"))
        assertEquals(DiagnosticLevel.DIRECT, ui.level)
    }

    @Test
    fun `une incompatibilite reste explicite meme si le mode technique est direct`() {
        val ui = PlaybackDiagnostic.from(
            decision(
                mode = "direct_play",
                playable = false,
                severity = "unsupported",
                reasons = listOf("HEVC non décodable"),
            ),
        )
        assertEquals(DiagnosticLevel.UNSUPPORTED, ui.level)
        assertFalse(ui.playable)
        assertEquals("HEVC non décodable", ui.detail)
    }

    private fun decision(
        mode: String,
        playable: Boolean = true,
        severity: String = "success",
        summary: String = "",
        reasons: List<String> = emptyList(),
    ) = PlaybackDecision(
        mode = mode,
        streamUrl = "/stream",
        playable = playable,
        severity = severity,
        summary = summary,
        reasons = reasons,
    )
}
