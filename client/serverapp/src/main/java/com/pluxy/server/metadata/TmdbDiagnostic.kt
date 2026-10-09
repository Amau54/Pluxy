package com.pluxy.server.metadata

internal fun tmdbHttpMessage(code: Int): String = when (code) {
    401, 403 -> "Clé TMDB refusée. Vérifiez la clé ou le jeton dans les options."
    429 -> "TMDB reçoit trop de demandes. Réessayez dans quelques minutes."
    in 500..599 -> "TMDB est temporairement indisponible. Les médias restent lisibles."
    else -> "TMDB ne répond pas correctement (HTTP $code)."
}
