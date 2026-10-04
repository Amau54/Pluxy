package com.pluxy.server.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class HttpRequestParserTest {
    @Test
    fun `empty connection has no request`() {
        assertNull(parse(""))
    }

    @Test
    fun `valid JSON request reads exactly its declared bytes`() {
        val request = parse("POST /api/settings HTTP/1.1\r\nHost: phone\r\nContent-Length: 2\r\n\r\n{}ignored")!!
        assertEquals("POST", request.method)
        assertEquals("/api/settings", request.path)
        assertArrayEquals("{}".toByteArray(), request.body)
    }

    @Test
    fun `identical repeated lengths can be normalized`() {
        val request = parse("POST / HTTP/1.1\r\nHost: phone\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}")!!
        assertEquals(2, request.body.size)
    }

    @Test
    fun `conflicting lengths and transfer framing fail before dispatch`() {
        assertFailure(400, "POST / HTTP/1.1\r\nHost: phone\r\nContent-Length: 0\r\nContent-Length: 2\r\n\r\n{}")
        assertFailure(400, "POST / HTTP/1.1\r\nHost: phone\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n")
        assertFailure(501, "POST / HTTP/1.1\r\nHost: phone\r\nTransfer-Encoding: chunked\r\n\r\n")
    }

    @Test
    fun `invalid or unbounded lengths are rejected`() {
        assertFailure(400, "POST / HTTP/1.1\r\nHost: phone\r\nContent-Length: +2\r\n\r\n{}")
        assertFailure(400, "POST / HTTP/1.1\r\nHost: phone\r\nContent-Length: -1\r\n\r\n")
        assertFailure(413, "POST / HTTP/1.1\r\nHost: phone\r\nContent-Length: 1048577\r\n\r\n")
        assertFailure(413, "POST / HTTP/1.1\r\nHost: phone\r\nContent-Length: 999999999999999999999\r\n\r\n")
    }

    @Test
    fun `truncated requests cannot reach state changing routes`() {
        assertFailure(400, "POST /api/library/scan HTTP/1.1\r\nHost: phone\r\n")
        assertFailure(400, "POST /api/settings HTTP/1.1\r\nHost: phone\r\nContent-Length: 2\r\n\r\n{")
        assertFailure(400, "GET / HTTP/1.1")
    }

    @Test
    fun `malformed headers and request versions are rejected`() {
        assertFailure(400, "GET / HTTP/2.0\r\nHost: phone\r\n\r\n")
        assertFailure(400, "GET / HTTP/1.1\r\nHost : phone\r\n\r\n")
        assertFailure(400, "GET / HTTP/1.1\r\nHost: phone\r\n folded: value\r\n\r\n")
        assertFailure(400, "GET / HTTP/1.1\r\nHost: phone\r\nHost: other\r\n\r\n")
    }

    @Test
    fun `HTTP 1_0 remains compatible without host`() {
        assertEquals("GET", parse("GET /health HTTP/1.0\r\n\r\n")?.method)
    }

    @Test
    fun `invalid query escapes are bad request and plus stays literal in paths`() {
        assertFailure(400, "GET /health?q=%zz HTTP/1.1\r\nHost: phone\r\n\r\n")
        val request = parse("GET /hello+world?q=a+b HTTP/1.1\r\nHost: phone\r\n\r\n")!!
        assertEquals("/hello+world", request.path)
        assertEquals("a b", request.query["q"])
    }

    @Test
    fun `header and request line sizes are bounded`() {
        assertFailure(414, "GET /${"a".repeat(8192)} HTTP/1.1\r\n\r\n")
        assertFailure(431, "GET / HTTP/1.1\r\nHost: phone\r\nX-Large: ${"a".repeat(8192)}\r\n\r\n")
        assertFailure(431, "GET / HTTP/1.1\r\nHost: phone\r\n${"X: ${"a".repeat(1000)}\r\n".repeat(66)}\r\n")
    }

    @Test
    fun `expectation is rejected before waiting for a body`() {
        assertFailure(417, "POST / HTTP/1.1\r\nHost: phone\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\n")
    }

    private fun parse(value: String): HttpRequest? = HttpRequestParser.parse(
        ByteArrayInputStream(value.toByteArray(StandardCharsets.ISO_8859_1)),
    )

    private fun assertFailure(status: Int, value: String) {
        assertEquals(status, assertThrows(HttpFailure::class.java) { parse(value) }.status)
    }
}
