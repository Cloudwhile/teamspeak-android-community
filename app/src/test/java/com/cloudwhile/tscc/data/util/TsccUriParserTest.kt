package com.cloudwhile.tscc.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsccUriParserTest {

    @Test
    fun `builds and parses full tscc invite uri`() {
        val original = TsccServerUri(
            host = "voice.example.com",
            port = 9988,
            nickname = "Cloud 队长",
            serverPassword = "srv_p+w",
            channel = "Lobby/Alpha Room",
            channelPassword = "ch_pw",
            privilegeKey = "tok_abc123",
            label = "战队语音服"
        )
        val encoded = original.toUriString()
        assertTrue(encoded.startsWith("tscc://voice.example.com?"))
        assertFalse(encoded.contains(" "))

        val parsed = TsccUriParser.parse(encoded, requireScheme = true)
        assertEquals(original, parsed)
    }

    @Test
    fun `parses path channel, url fragment, and connect authority placeholder`() {
        val withPathAndFragment = TsccUriParser.parse(
            "tscc://ts.clan.org:10022/Apex%20Legends/Squad%201?nickname=Runner#join-now",
            requireScheme = true
        )
        assertNotNull(withPathAndFragment)
        assertEquals("ts.clan.org", withPathAndFragment?.host)
        assertEquals(10022, withPathAndFragment?.port)
        assertEquals("Apex Legends/Squad 1", withPathAndFragment?.channel)
        assertEquals("Runner", withPathAndFragment?.nickname)

        val queryPlaceholder = TsccUriParser.parse(
            "tscc://connect?host=198.51.100.42&port=9991&channel=Main&token=key99",
            requireScheme = true
        )
        assertNotNull(queryPlaceholder)
        assertEquals("198.51.100.42", queryPlaceholder?.host)
        assertEquals(9991, queryPlaceholder?.port)
        assertEquals("Main", queryPlaceholder?.channel)
        assertEquals("key99", queryPlaceholder?.privilegeKey)
    }

    @Test
    fun `unwraps chromium android intent scheme=tscc uri and extracts shared text`() {
        val intentUri =
            "intent://voice.clan.org?port=9989&nickname=MobileUser&label=Clan#Intent;scheme=tscc;package=com.cloudwhile.tscc;end"
        assertTrue(TsccUriParser.isTsccDeepLink(intentUri))
        val parsed = TsccUriParser.parse(intentUri, requireScheme = true)
        assertNotNull(parsed)
        assertEquals("voice.clan.org", parsed?.host)
        assertEquals(9989, parsed?.port)
        assertEquals("MobileUser", parsed?.nickname)
        assertEquals("Clan", parsed?.label)

        val sharedMessage = "今晚开黑直接点这个进房间：tscc://voice.clan.org?port=9987&channel=%E5%A4%A7%E5%8E%85，密码群公告。"
        val extracted = TsccUriParser.extractFirstTsccUri(sharedMessage)
        assertEquals("tscc://voice.clan.org?port=9987&channel=%E5%A4%A7%E5%8E%85", extracted)
        val parsedShared = TsccUriParser.parse(extracted!!, requireScheme = true)
        assertEquals("大厅", parsedShared?.channel)
    }

    @Test
    fun `supports ipv6 literals and plain host port when scheme not required`() {
        val v6 = TsccUriParser.parse("tscc://[2001:db8::42]:9990?nickname=IPv6User", requireScheme = true)
        assertNotNull(v6)
        assertEquals("2001:db8::42", v6?.host)
        assertEquals(9990, v6?.port)
        assertEquals("tscc://[2001:db8::42]?port=9990&nickname=IPv6User", v6?.toUriString())

        val plain = TsccUriParser.parse("ts.example.net:9999", requireScheme = false)
        assertNotNull(plain)
        assertEquals("ts.example.net", plain?.host)
        assertEquals(9999, plain?.port)

        assertNull(TsccUriParser.parse("ts.example.net:9999", requireScheme = true))
    }

    @Test
    fun `rejects legacy schemes and unrelated urls`() {
        val legacyScheme = "ts3" + "server://voice.example.com?port=9987"
        assertFalse(TsccUriParser.isTsccDeepLink(legacyScheme))
        assertNull(TsccUriParser.parse(legacyScheme, requireScheme = false))
        assertNull(TsccUriParser.parse(legacyScheme, requireScheme = true))
        assertNull(TsccUriParser.parse("https://teamspeak.com", requireScheme = false))
        assertNull(
            TsccUriParser.parse(
                "intent://voice.example.com#Intent;scheme=https;end",
                requireScheme = true
            )
        )
    }
}
