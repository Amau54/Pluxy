package com.pluxy.receiver.dlna

import org.junit.Assert.*
import org.junit.Test

class DlnaUrlBaseTest {
    private fun description(base: String): ByteArray = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>$base</URLBase><device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>TV</friendlyName>
          <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
          <controlURL>control/av</controlURL></service></serviceList></device></root>
    """.trimIndent().toByteArray()
    @Test fun `relative control URL honors declared base directory and port`() {
        val result = DlnaDescriptionParser.parse(description("http://192.168.1.20:1234/services/"), "http://192.168.1.20:1400/device.xml")
        assertEquals("http://192.168.1.20:1234/services/control/av", result?.avTransportControlUrl)
    }
    @Test fun `URLBase cannot redirect commands to public or different private host`() {
        listOf("http://8.8.8.8/", "http://192.168.1.99/", "file:///etc/", "http://user@192.168.1.20/").forEach {
            assertNull(DlnaDescriptionParser.parse(description(it), "http://192.168.1.20:1400/device.xml"))
        }
    }
    @Test fun `absent URLBase preserves LOCATION resolution`() {
        val result = DlnaDescriptionParser.parse(description(""), "http://192.168.1.20:1400/devices/device.xml")
        assertEquals("http://192.168.1.20:1400/devices/control/av", result?.avTransportControlUrl)
    }
}
