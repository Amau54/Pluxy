package com.pluxy.server.metadata

import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiagnosticTest {
    @Test fun `authentication quota and outages have actionable messages`() {
        assertTrue(tmdbHttpMessage(401).contains("Clé TMDB refusée"))
        assertTrue(tmdbHttpMessage(403).contains("Clé TMDB refusée"))
        assertTrue(tmdbHttpMessage(429).contains("quelques minutes"))
        assertTrue(tmdbHttpMessage(503).contains("temporairement indisponible"))
        assertTrue(tmdbHttpMessage(418).contains("HTTP 418"))
    }
}
