package com.cloudwhile.tscc.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Ts3UriParserTest {

    @Test
    fun `parses full ts3server deep link with encoded params`() {
        val raw = "ts3server://voice.example.org?port=9999&nickname=Player%20One&password=sec%2Bret&channel=Lobby%2FSquad%201&channelpassword=chpass&token=PRIVKEY123"
        val parsed = Ts3UriParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("voice.example.org", parsed!!.host)
        assertEquals(9999, parsed.port)
        assertEquals("Player One", parsed.nickname)
        assertEquals("sec+ret", parsed.serverPassword)
        assertEquals("Lobby/Squad 1", parsed.channel)
        assertEquals("chpass", parsed.channelPassword)
        assertEquals("PRIVKEY123", parsed.privilegeKey)
    }

    @Test
    fun `parses plain host port and ipv6 literal`() {
        val hostPort = Ts3UriParser.parse("ts.gaming.cn:10050")
        assertNotNull(hostPort)
        assertEquals("ts.gaming.cn", hostPort!!.host)
        assertEquals(10050, hostPort.port)

        val ipv6 = Ts3UriParser.parse("ts3server://[2001:db8::42]:9988?nickname=Ace")
        assertNotNull(ipv6)
        assertEquals("2001:db8::42", ipv6!!.host)
        assertEquals(9988, ipv6.port)
        assertEquals("Ace", ipv6.nickname)
    }

    @Test
    fun `round trips toUriString and rejects invalid scheme`() {
        val original = Ts3ServerUri(
            host = "clan.ts6.io",
            port = 9987,
            nickname = "指挥官",
            channel = "开黑房 #1"
        )
        val encoded = original.toUriString()
        val decoded = Ts3UriParser.parse(encoded)
        assertNotNull(decoded)
        assertEquals("clan.ts6.io", decoded!!.host)
        assertEquals(9987, decoded.port)
        assertEquals("指挥官", decoded.nickname)
        assertEquals("开黑房 #1", decoded.channel)

        assertNull(Ts3UriParser.parse("https://example.com"))
    }
}
