package com.pluxy.receiver.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class DlnaParsersTest {
    @Test
    fun `SSDP accepts only private endpoints`() {
        val privateResponse = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20:1400/device.xml\r\nUSN: uuid:tv\r\n\r\n"
        val publicResponse = "HTTP/1.1 200 OK\r\nLOCATION: https://8.8.8.8/device.xml\r\n\r\n"
        assertNotNull(SsdpParser.parse(privateResponse))
        assertNull(SsdpParser.parse(publicResponse))
    }

    @Test
    fun `SSDP location must belong to the responding device`() {
        val response = "HTTP/1.0 200 OK\r\nLOCATION: http://192.168.1.20/device.xml\r\n\r\n"
        assertNotNull(SsdpParser.parse(response, InetAddress.getByName("192.168.1.20")))
        assertNull(SsdpParser.parse(response, InetAddress.getByName("192.168.1.21")))
    }

    @Test
    fun `protocol info derives capabilities without assuming MKV`() {
        val caps = DlnaProtocolInfo.capabilities(
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_1080i_AAC," +
                "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3," +
                "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG",
        )
        assertTrue("mp4" in caps.containers)
        assertTrue("h264" in caps.videoCodecs)
        assertTrue("aac" in caps.audioCodecs)
        assertFalse("mkv" in caps.containers)
    }

    @Test
    fun `DLNA time is stable for long movies`() {
        assertEquals("02:03:04", DlnaTime.format(7_384_999))
        assertEquals(7_384_000L, DlnaTime.parse("02:03:04.000"))
        assertNull(DlnaTime.parse("NOT_IMPLEMENTED"))
    }

    @Test
    fun `DLNA time rejects negative or overflowing device values`() {
        assertNull(DlnaTime.parse("-1:00:00"))
        assertNull(DlnaTime.parse("9223372036854775807:00:00"))
        assertNull(DlnaTime.parse("00:60:00"))
        assertNull(DlnaTime.parse("00:00:60"))
    }

    @Test
    fun `device description keeps the advertised service version`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>TV Salon</friendlyName><UDN>uuid:salon</UDN>
                <serviceList>
                  <service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType><controlURL>/av</controlURL></service>
                  <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>
                </serviceList>
              </device>
            </root>
        """.trimIndent().toByteArray()

        val result = DlnaDescriptionParser.parse(xml, "http://192.168.1.20:1400/device.xml")

        assertEquals("TV Salon", result?.name)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", result?.avTransportServiceType)
        assertEquals("http://192.168.1.20:1400/av", result?.avTransportControlUrl)
    }

    @Test
    fun `UPnP XML refuses doctypes`() {
        val malicious = "<!DOCTYPE x [<!ENTITY file SYSTEM 'file:///etc/passwd'>]><x>&file;</x>".toByteArray()
        assertThrows(Exception::class.java) { SafeXml.parse(malicious) }
    }

    @Test
    fun `device description cannot redirect SOAP to another LAN host`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Piège</friendlyName><serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>http://192.168.1.99/private</controlURL>
              </service></serviceList></device></root>
        """.trimIndent().toByteArray()
        assertNull(DlnaDescriptionParser.parse(xml, "http://192.168.1.20/device.xml"))
    }
}
