package com.pluxy.server.metadata

import org.junit.Assert.*
import org.junit.Test

class TitleMatchTest {
    @Test fun `accent ponctuation et faute legere gardent un titre sans fausse affiche`() {
        assertTrue(TitleMatch.matches("Été à Paris", "Ete a Paris"))
        assertTrue(TitleMatch.matches("Interstellar", "Interstelar"))
        assertTrue(TitleMatch.matches("Up", "Up"))
        assertFalse(TitleMatch.matches("A la piscine avec la famille", "La piscine"))
        assertFalse(TitleMatch.matches("Voyage", "V"))
        assertFalse(TitleMatch.matches("", "Example"))
    }
}
