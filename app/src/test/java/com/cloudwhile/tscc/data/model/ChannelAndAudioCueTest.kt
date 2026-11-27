package com.cloudwhile.tscc.data.model

import com.cloudwhile.tscc.data.NativeBridge
import com.cloudwhile.tscc.service.SoundCuePlayer
import com.cloudwhile.tscc.ui.i18n.resolveAppStrings
import com.cloudwhile.tscc.ui.screens.room.formatDurationHuman
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChannelAndAudioCueTest {

    @Test
    fun `computeExcludedParentChannelIds excludes self and all nested descendants`() {
        val channels = listOf(
            ChannelNode(id = 1L, parentId = 0L, name = "Lobby"),
            ChannelNode(id = 2L, parentId = 0L, name = "Squad Root"),
            ChannelNode(id = 3L, parentId = 2L, name = "Squad Sub A"),
            ChannelNode(id = 4L, parentId = 3L, name = "Squad Leaf A1"),
            ChannelNode(id = 5L, parentId = 0L, name = "Music")
        )

        val excluded = computeExcludedParentChannelIds(editingChannelId = 2L, allChannels = channels)
        assertEquals(setOf(2L, 3L, 4L), excluded)
        assertFalse(1L in excluded)
        assertFalse(5L in excluded)
    }

    @Test
    fun `synthesizeTwoToneChimePcm generates click-free bounded waveform`() {
        val pcm = SoundCuePlayer.synthesizeTwoToneChimePcm(
            freq1Hz = 523.25,
            dur1Ms = 60,
            freq2Hz = 783.99,
            dur2Ms = 90,
            peakGain = 0.25
        )
        assertTrue(pcm.isNotEmpty())
        // First sample starts at zero (raised-cosine envelope) to prevent speaker pop
        assertEquals(0, pcm[0].toInt())
        // Waveform has audible energy and stays strictly within 16-bit bounds
        assertTrue(pcm.any { kotlin.math.abs(it.toInt()) > 1000 })
    }

    @Test
    fun `resolveAppStrings switches cleanly between Chinese English and System`() {
        val zh = resolveAppStrings(AppLanguage.ZH_CN, Locale.US)
        assertFalse(zh.isEnglish)
        assertEquals("书签", zh.tabBookmarks)

        val en = resolveAppStrings(AppLanguage.EN, Locale.SIMPLIFIED_CHINESE)
        assertTrue(en.isEnglish)
        assertEquals("Bookmarks", en.tabBookmarks)

        val autoZh = resolveAppStrings(AppLanguage.SYSTEM, Locale.SIMPLIFIED_CHINESE)
        assertFalse(autoZh.isEnglish)

        val autoEn = resolveAppStrings(AppLanguage.SYSTEM, Locale.UK)
        assertTrue(autoEn.isEnglish)
    }

    @Test
    fun `resolveBadges orders channel and server group icons by sortId and formats naming tags`() {
        val snap = ServerSnapshot(
            serverGroups = listOf(
                ServerGroupInfo(id = 10L, name = "Guest", iconId = 0L, sortId = 0),
                ServerGroupInfo(id = 20L, name = "VIP", iconId = 2002L, sortId = 30, namingMode = 2),
                ServerGroupInfo(id = 6L, name = "Admin", iconId = 300L, sortId = 10, namingMode = 1),
                ServerGroupInfo(id = 8L, name = "Mod", iconId = 1500L, sortId = 20),
                ServerGroupInfo(id = 9L, name = "Veteran", iconId = 1600L, sortId = 40)
            ),
            channelGroups = listOf(
                ChannelGroupInfo(id = 5L, name = "CA", iconId = 100L, sortId = 5, namingMode = 1)
            )
        )
        val client = OnlineClient(
            id = 1,
            channelId = 1L,
            nickname = "Cloud",
            serverGroups = listOf(20L, 6L, 8L, 9L),
            channelGroup = 5L,
            iconId = -500_000_000L // negative signed i32 representation of custom CRC32 icon
        )

        val resolved = client.resolveBadges(snap)
        assertEquals("[CA] [Admin] Cloud [VIP]", resolved.displayName)
        assertEquals(listOf(6L, 8L, 20L, 9L), resolved.serverGroups.map { it.id })

        val expectedUnsignedClientIcon = -500_000_000L and 0xFFFFFFFFL
        assertEquals(
            listOf(100L, 300L, 1500L, 2002L, 1600L, expectedUnsignedClientIcon),
            resolved.orderedIconIds
        )

        val (inline4, overflow) = resolved.inlineIcons(maxVisible = 4)
        assertEquals(listOf(100L, 300L, 1500L, 2002L), inline4)
        assertEquals(2, overflow)
    }

    @Test
    fun `ServerSnapshot deserializes both legacy and extended Rust JSON payloads`() {
        val legacyJson = """{"sessionId":1,"serverName":"Legacy","maxClients":32,"myClientId":2,"myChannelId":1}"""
        val legacy = NativeBridge.jsonParser.decodeFromString<ServerSnapshot>(legacyJson)
        assertEquals("Legacy", legacy.serverName)
        assertTrue(legacy.serverGroups.isEmpty())

        val extendedJson = """
            {
              "sessionId": 2,
              "serverName": "TS6 Hub",
              "serverAddress": "ts.example.com:9987",
              "uptimeSec": 90061,
              "neededSecurityLevel": 23,
              "serverGroups": [{"id":6,"name":"Admin","groupType":1,"iconId":300,"sortId":10,"namingMode":1}],
              "clients": [{"id":7,"databaseId":42,"channelId":1,"nickname":"Alice","talkPowerRequestTime":1700000100}]
            }
        """.trimIndent()
        val ext = NativeBridge.jsonParser.decodeFromString<ServerSnapshot>(extendedJson)
        assertEquals(90061L, ext.uptimeSec)
        assertEquals(23, ext.neededSecurityLevel)
        assertEquals(1, ext.serverGroups.size)
        assertTrue(ext.clients.first().hasTalkPowerRequest)
        assertEquals("1天 1小时 1分", formatDurationHuman(ext.uptimeSec, isEnglish = false))
        assertEquals("1d 1h 1m", formatDurationHuman(ext.uptimeSec, isEnglish = true))
    }
}
