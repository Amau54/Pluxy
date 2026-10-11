package com.pluxy.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerLinksTest {
    @Test fun recognizesVideoLinksWithoutForwardingTrackingParameters() {
        for (url in listOf(
            "https://www.youtube.com/watch?v=nLJR2twdK00&feature=share",
            "https://m.youtube.com/watch?feature=share&v=nLJR2twdK00",
            "https://youtu.be/nLJR2twdK00?si=tracking",
            "https://youtube.com/embed/nLJR2twdK00",
            "https://www.youtube.com/shorts/nLJR2twdK00",
        )) {
            val links = TrailerLinks.from(url)!!
            assertEquals("https://www.youtube.com/watch?v=nLJR2twdK00", links.web)
            assertEquals("vnd.youtube://nLJR2twdK00", links.video)
            assertEquals("nLJR2twdK00", java.net.URI(links.video).host)
        }
    }

    @Test fun refusesNonVideosAndExternalIntents() {
        for (url in listOf(null, "", "intent://nLJR2twdK00", "http://youtu.be/nLJR2twdK00",
            "https://youtube.com.evil.test/watch?v=nLJR2twdK00", "https://user@youtube.com/watch?v=nLJR2twdK00",
            "https://youtube.com:8443/watch?v=nLJR2twdK00", "https://youtube.com/watch?v=abc",
            "https://youtu.be/nLJR2twdK00/extra", "https://youtube.com/results?search_query=trailer")) {
            assertNull(url, TrailerLinks.from(url))
        }
    }
}
