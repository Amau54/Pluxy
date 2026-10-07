package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test

class PhotoDateTest {
    @Test fun `camera timestamps use their recorded offset`() {
        val utc = photoDateMs("2025:06:21 12:00:00", null)!!
        assertEquals(utc - 7_200_000, photoDateMs("2025:06:21 12:00:00", "+02:00"))
        assertEquals(utc + 18_000_000, photoDateMs("2025:06:21 12:00:00", "-05:00"))
        assertEquals(utc, photoDateMs("2025:06:21 12:00:00", "unknown"))
    }
    @Test fun `missing malformed and impossible photo dates remain unknown`() {
        listOf(null, "", "0000:00:00 00:00:00", "2025:02:30 12:00:00", "2025:06:21 12:00:00 garbage",
            "2025:06:21 25:00:00").forEach { assertNull(photoDateMs(it, null)) }
    }
}
