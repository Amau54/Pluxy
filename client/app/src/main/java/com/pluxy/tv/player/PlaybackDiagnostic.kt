package com.pluxy.tv.player

import com.pluxy.tv.api.PlaybackDecision

enum class DiagnosticLevel { DIRECT, ADAPTATION, WARNING, UNSUPPORTED }

data class PlaybackDiagnosticUi(
    val title: String,
    val detail: String,
    val level: DiagnosticLevel,
    val playable: Boolean,
)

object PlaybackDiagnostic {
    fun from(decision: PlaybackDecision): PlaybackDiagnosticUi {
        val level = when {
            !decision.playable || decision.severity == "unsupported" -> DiagnosticLevel.UNSUPPORTED
            decision.severity == "warning" -> DiagnosticLevel.WARNING
            decision.mode == "direct_play" -> DiagnosticLevel.DIRECT
            else -> DiagnosticLevel.ADAPTATION
        }
        val title = decision.summary.ifBlank {
            when (level) {
                DiagnosticLevel.DIRECT -> "Direct Play · qualité originale"
                DiagnosticLevel.ADAPTATION -> "Adaptation automatique avant lecture"
                DiagnosticLevel.WARNING -> "Direct Play possible, réseau à surveiller"
                DiagnosticLevel.UNSUPPORTED -> "Adaptation nécessaire sur ce serveur"
            }
        }
        val detail = decision.reasons
            .filterNot { it.equals(title, ignoreCase = true) }
            .take(3)
            .joinToString(" · ")
            .ifBlank { "Profil du téléviseur vérifié avant la lecture." }
        return PlaybackDiagnosticUi(title, detail, level, decision.playable)
    }
}
