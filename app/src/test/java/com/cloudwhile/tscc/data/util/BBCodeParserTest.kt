package com.cloudwhile.tscc.data.util

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class BBCodeParserTest {

    @Test
    fun `strips tags cleanly and preserves bold and url annotations`() {
        val raw = "欢迎来到 [b][color=#22c55e]战队语音服[/color][/b]！点击 [url=https://teamspeak.com]官网[/url] 或直接进 ts3server://ts.clan.org?port=9987"
        val annotated = BBCodeParser.parse(raw)
        assertEquals(
            "欢迎来到 战队语音服！点击 官网 或直接进 ts3server://ts.clan.org?port=9987",
            annotated.text
        )

        val boldSpans = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals("战队语音服", annotated.text.substring(boldSpans[0].start, boldSpans[0].end))

        val urls = annotated.getStringAnnotations(BBCodeParser.URL_TAG, 0, annotated.length)
        assertEquals(2, urls.size)
        assertEquals("https://teamspeak.com", urls[0].item)
        assertEquals("官网", annotated.text.substring(urls[0].start, urls[0].end))
        assertEquals("ts3server://ts.clan.org?port=9987", urls[1].item)
    }
}
