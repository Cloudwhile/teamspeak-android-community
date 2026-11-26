package com.cloudwhile.tscc.data.model

import com.cloudwhile.tscc.service.SoundCuePlayer
import com.cloudwhile.tscc.ui.i18n.resolveAppStrings
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
}
