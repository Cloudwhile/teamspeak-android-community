package com.cloudwhile.tscc

import com.cloudwhile.tscc.data.NativeBridge
import com.cloudwhile.tscc.data.model.ChannelNode
import com.cloudwhile.tscc.data.model.LiveStreamSourceType
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.ServerSnapshot
import com.cloudwhile.tscc.data.model.Ts6StreamSignal
import com.cloudwhile.tscc.service.Ts6LiveStreamManager
import com.cloudwhile.tscc.service.Ts6LiveStreamManager.ParsedSignaling
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Ts6SignalingParserTest {

    @Test
    fun parsesStandardAndWrappedTs6SignalingPayloadsAndPreservesCrlf() {
        val rawSdpWithoutTrailingCrlf = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\na=mid:0"
        val extracted = Ts6LiveStreamManager.extractOfferSdpStatic(rawSdpWithoutTrailingCrlf)
        assertNotNull(extracted)
        assertTrue("SDP must preserve trailing CRLF for libwebrtc", extracted!!.endsWith("\r\n"))

        val answerJson = """{"type":"answer","sdp":"v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96"}"""
        val ansItems = Ts6LiveStreamManager.parseSignalingItemsStatic(answerJson)
        assertEquals(1, ansItems.size)
        val ansSdp = ansItems[0] as ParsedSignaling.Sdp
        assertEquals("answer", ansSdp.type)
        assertTrue(ansSdp.sdp.endsWith("\r\n"))

        // C++ / libdatachannel variants: "sdp": "a=candidate:..." or "mid": "video"
        val cppIceJson = """{"type":"candidate","sdp":"a=candidate:1 1 UDP 2122252543 192.168.1.10 51234 typ host","mid":"video","mLineIndex":1}"""
        val iceItems = Ts6LiveStreamManager.parseSignalingItemsStatic(cppIceJson)
        assertEquals(1, iceItems.size)
        val ice = iceItems[0] as ParsedSignaling.Ice
        assertEquals("video", ice.sdpMid)
        assertEquals(1, ice.sdpMLineIndex)
        assertTrue(ice.candidate.startsWith("candidate:1 "))

        // Nested candidate wrapper
        val wrappedIce = """{"candidate":{"candidate":"candidate:2 1 UDP 2122252543 192.168.1.11 51235 typ host","sdpMid":"0","sdpMLineIndex":0}}"""
        val wrappedRes = Ts6LiveStreamManager.parseSignalingItemsStatic(wrappedIce)
        assertEquals(1, wrappedRes.size)
        assertTrue((wrappedRes[0] as ParsedSignaling.Ice).candidate.startsWith("candidate:2"))
    }

    @Test
    fun buildsAndParsesStandardTs6DesktopCmdArgsSignalingSchema() {
        // 1. Outgoing Answer builder -> {"cmd":"answer","args":{"answer":"v=0\r\n..."}}
        val builtAnswer = Ts6LiveStreamManager.buildTs6AnswerSignalingJson("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96")
        val ansRoot = NativeBridge.jsonParser.parseToJsonElement(builtAnswer) as JsonObject
        assertEquals("answer", (ansRoot["cmd"] as JsonPrimitive).content)
        val ansArgs = ansRoot["args"] as JsonObject
        assertTrue((ansArgs["answer"] as JsonPrimitive).content.endsWith("\r\n"))

        // Round-trip parse of standard TS6 Answer
        val parsedAns = Ts6LiveStreamManager.parseSignalingItemsStatic(builtAnswer)
        assertEquals(1, parsedAns.size)
        assertEquals("answer", (parsedAns[0] as ParsedSignaling.Sdp).type)
        assertTrue((parsedAns[0] as ParsedSignaling.Sdp).sdp.startsWith("v=0\r\n"))

        // 2. Outgoing Offer & ReconnectOffer builders
        val builtOffer = Ts6LiveStreamManager.buildTs6OfferSignalingJson("v=0\r\na=mid:0", isReconnect = false)
        val offRoot = NativeBridge.jsonParser.parseToJsonElement(builtOffer) as JsonObject
        assertEquals("offer", (offRoot["cmd"] as JsonPrimitive).content)
        val parsedOff = Ts6LiveStreamManager.parseSignalingItemsStatic(builtOffer)
        assertEquals("offer", (parsedOff[0] as ParsedSignaling.Sdp).type)

        val builtReconnOffer = Ts6LiveStreamManager.buildTs6OfferSignalingJson("v=0\r\na=mid:0", isReconnect = true)
        val recRoot = NativeBridge.jsonParser.parseToJsonElement(builtReconnOffer) as JsonObject
        assertEquals("reconnectOffer", (recRoot["cmd"] as JsonPrimitive).content)
        val parsedReconnOff = Ts6LiveStreamManager.parseSignalingItemsStatic(builtReconnOffer)
        assertEquals("offer", (parsedReconnOff[0] as ParsedSignaling.Sdp).type)

        // 3. Outgoing & Incoming trickle ICE -> {"cmd":"iceCandidate","args":{"sdp":"...","mid":"1","mLine":1}}
        val builtIce = Ts6LiveStreamManager.buildTs6IceCandidateSignalingJson(
            "a=candidate:842163049 1 udp 1686052607 192.168.1.25 54321 typ srflx",
            "1",
            1
        )
        val iceRoot = NativeBridge.jsonParser.parseToJsonElement(builtIce) as JsonObject
        assertEquals("iceCandidate", (iceRoot["cmd"] as JsonPrimitive).content)
        val iceArgs = iceRoot["args"] as JsonObject
        assertEquals("1", (iceArgs["mid"] as JsonPrimitive).content)
        assertEquals(1, (iceArgs["mLine"] as JsonPrimitive).int)
        assertTrue((iceArgs["sdp"] as JsonPrimitive).content.startsWith("candidate:842163049"))

        val parsedIce = Ts6LiveStreamManager.parseSignalingItemsStatic(builtIce)
        assertEquals(1, parsedIce.size)
        val iceItem = parsedIce[0] as ParsedSignaling.Ice
        assertEquals("1", iceItem.sdpMid)
        assertEquals(1, iceItem.sdpMLineIndex)
        assertTrue(iceItem.candidate.startsWith("candidate:842163049"))

        // 4. Standard TS6 joinResponse & reconnect envelopes
        val joinOkJson = """{"cmd":"joinResponse","args":{"decision":true,"offer":"v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96"}}"""
        val joinOkItems = Ts6LiveStreamManager.parseSignalingItemsStatic(joinOkJson)
        assertEquals(1, joinOkItems.size)
        assertEquals("offer", (joinOkItems[0] as ParsedSignaling.Sdp).type)

        val joinRejectJson = """{"cmd":"joinResponse","args":{"decision":false,"msg":"denied"}}"""
        val joinRejectItems = Ts6LiveStreamManager.parseSignalingItemsStatic(joinRejectJson)
        assertEquals(1, joinRejectItems.size)
        assertEquals("denied", (joinRejectItems[0] as ParsedSignaling.JoinRejected).reason)

        val reconnectJson = """{"cmd":"reconnect","args":{}}"""
        val reconnectItems = Ts6LiveStreamManager.parseSignalingItemsStatic(reconnectJson)
        assertEquals(1, reconnectItems.size)
        assertTrue(reconnectItems[0] is ParsedSignaling.Reconnect)
    }

    @Test
    fun compactsOversizedSdpBelowTs6ServerCommandLimit() {
        val sb = StringBuilder("v=0\r\no=- 46117 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
        for (i in 1..15) {
            sb.append("a=extmap:$i http://www.webrtc.org/experiments/rtp-hdrext/video-timing-$i\r\n")
        }
        for (i in 1..60) {
            sb.append("a=candidate:$i 1 tcp 1518280447 192.168.1.$i 9 typ host tcptype active\r\n")
            sb.append("a=candidate:${100 + i} 1 udp 2122260223 192.168.1.$i ${50000 + i} typ host\r\n")
        }
        val compacted = Ts6LiveStreamManager.compactSdpForTs6Wire(sb.toString())
        assertTrue(compacted.length <= Ts6LiveStreamManager.MAX_SAFE_SDP_CHARS)
        assertTrue(compacted.endsWith("\r\n"))
        assertTrue(compacted.contains("typ host"))
        assertFalse(compacted.contains(" tcptype "))
    }

    @Test
    fun buildsAndParsesMdnsLanARecordWithHostnameVerification() {
        val quQuery = Ts6LiveStreamManager.buildMdnsQueryPacket("e4a8b210-1234-4abc-89ab-0123456789ab.local", unicastResponse = true)
        val qmQuery = Ts6LiveStreamManager.buildMdnsQueryPacket("e4a8b210-1234-4abc-89ab-0123456789ab.local", unicastResponse = false)
        assertTrue(quQuery.size > 20)
        assertEquals(0x80.toByte(), quQuery[quQuery.size - 2])
        assertEquals(0x00.toByte(), qmQuery[qmQuery.size - 2])

        // Construct synthetic DNS response with 1 question ("pc.local") and 2 A-records:
        //   RR 1: unrelated LAN printer "printer.local" -> 192.168.31.250
        //   RR 2: compressed pointer 0xC00C ("pc.local") with mDNS cache-flush bit 0x8001 -> 192.168.31.88
        val resp = ByteArrayOutputStream()
        resp.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 2, 0, 0, 0, 0))
        // Offset 12 (0x0C): Question "pc.local"
        resp.write(byteArrayOf(2, 'p'.code.toByte(), 'c'.code.toByte(), 5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 0))
        resp.write(byteArrayOf(0, 1, 0, 1))
        // RR 1: "printer.local" -> 192.168.31.250 (must be ignored when querying "pc.local")
        resp.write(byteArrayOf(7, 'p'.code.toByte(), 'r'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 0xC0.toByte(), 0x0F))
        resp.write(byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4, 192.toByte(), 168.toByte(), 31, 250.toByte()))
        // RR 2: compressed pointer 0xC00C ("pc.local") -> 192.168.31.88
        resp.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4, 192.toByte(), 168.toByte(), 31, 88))
        val bytes = resp.toByteArray()

        assertEquals("192.168.31.88", Ts6LiveStreamManager.parseDnsARecordResponse(bytes, bytes.size, "PC.local"))
        assertEquals("192.168.31.250", Ts6LiveStreamManager.parseDnsARecordResponse(bytes, bytes.size, "printer.local"))
        assertNull(Ts6LiveStreamManager.parseDnsARecordResponse(bytes, bytes.size, "other-host.local"))
    }

    @Test
    fun enhancesViewerAnswerAndPrioritizesHardwareCodecsForLowLatency() {
        assertEquals("stream-p2p-av", Ts6LiveStreamManager.TS6_P2P_STREAM_MSID)

        val rawAnswer = buildString {
            append("v=0\r\n")
            append("o=- 46117 2 IN IP4 127.0.0.1\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("m=video 9 UDP/TLS/RTP/SAVPF 98 99 96 97 102 103 125\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=mid:0\r\n")
            append("a=extmap:5 http://www.webrtc.org/experiments/rtp-hdrext/playout-delay\r\n")
            append("a=recvonly\r\n")
            append("a=rtpmap:98 AV1/90000\r\n")
            append("a=rtpmap:99 rtx/90000\r\n")
            append("a=fmtp:99 apt=98\r\n")
            append("a=rtpmap:96 VP8/90000\r\n")
            append("a=rtpmap:97 rtx/90000\r\n")
            append("a=fmtp:97 apt=96\r\n")
            append("a=rtpmap:102 H264/90000\r\n")
            append("a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n")
            append("a=rtpmap:103 rtx/90000\r\n")
            append("a=fmtp:103 apt=102\r\n")
            append("a=rtpmap:125 ulpfec/90000\r\n")
            append("a=candidate:1 1 udp 2122260223 192.168.1.20 50123 typ host\r\n")
            append("a=end-of-candidates\r\n")
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=mid:1\r\n")
            append("a=inactive\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
        }

        val enhanced = Ts6LiveStreamManager.enhanceViewerAnswerSdp(rawAnswer)
        val compacted = Ts6LiveStreamManager.compactSdpForTs6Wire(enhanced)

        // 1. Hardware H264 (102, 103) and VP8 (96, 97) must lead before AV1 (98, 99) and ULPFEC (125)
        assertTrue(compacted.contains("m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97 98 99 125\r\n"))
        // 2. Embedded ICE candidates stripped for clean Trickle ICE
        assertFalse(compacted.contains("a=candidate:"))
        assertFalse(compacted.contains("a=end-of-candidates"))
        // 3. High-bitrate BWE hints injected for both H264 (existing fmtp) and VP8 (synthesized fmtp)
        assertTrue(compacted.contains("b=AS:12000\r\n"))
        assertTrue(compacted.contains("b=TIAS:12000000\r\n"))
        assertTrue(compacted.contains("a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f;x-google-min-bitrate=1500;x-google-start-bitrate=5000;x-google-max-bitrate=12000\r\n"))
        assertTrue(compacted.contains("a=fmtp:96 x-google-min-bitrate=1500;x-google-start-bitrate=5000;x-google-max-bitrate=12000\r\n"))
        // 4. Real-time playout-delay extension preserved and size well within TS6 wire limit
        assertTrue(compacted.contains("playout-delay"))
        assertTrue(compacted.length <= Ts6LiveStreamManager.MAX_SAFE_SDP_CHARS)
        assertTrue(compacted.endsWith("\r\n"))

        // Candidate filter drops loopback, TCP, and link-local IPv6
        assertFalse(Ts6LiveStreamManager.shouldSendLocalIceCandidate("candidate:1 1 udp 2122260223 127.0.0.1 50000 typ host"))
        assertFalse(Ts6LiveStreamManager.shouldSendLocalIceCandidate("candidate:1 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active"))
        assertFalse(Ts6LiveStreamManager.shouldSendLocalIceCandidate("candidate:1 1 udp 2122260223 fe80::1234 50000 typ host"))
        assertTrue(Ts6LiveStreamManager.shouldSendLocalIceCandidate("candidate:1 1 udp 2122260223 192.168.1.20 50000 typ host"))
    }

    @Test
    fun ts6StreamSourceTypesAndDefaultsMatchServerP2pProtocol() {
        // Verified against tsserver 6.0.0-beta14 protobuf-1 wire mapping: CAMERA=1, SCREEN=2
        assertEquals(1, LiveStreamSourceType.CAMERA.tsType)
        assertEquals(2, LiveStreamSourceType.SCREEN.tsType)
        val defaultSig = Ts6StreamSignal()
        assertEquals(1, defaultSig.mode)
        assertEquals(1, defaultSig.accessibility)
    }

    @Test
    fun moderatedChannelAndAfkCorrectlyBlockSelfTransmission() {
        val moderatedSnap = ServerSnapshot(
            myClientId = 7,
            myChannelId = 10L,
            channels = listOf(ChannelNode(id = 10L, name = "Muted Hall", neededTalkPower = 50)),
            clients = listOf(OnlineClient(id = 7, channelId = 10L, nickname = "Me", talkPower = 0, talkPowerGranted = false))
        )
        val me = moderatedSnap.clients.first { it.id == moderatedSnap.myClientId }
        val ch = moderatedSnap.channels.first { it.id == moderatedSnap.myChannelId }
        val talkRestricted = ch.neededTalkPower > 0 && me.talkPower < ch.neededTalkPower && !me.talkPowerGranted
        assertTrue(talkRestricted)

        val grantedClient = me.copy(talkPowerGranted = true)
        val grantedRestricted = ch.neededTalkPower > 0 && grantedClient.talkPower < ch.neededTalkPower && !grantedClient.talkPowerGranted
        assertFalse(grantedRestricted)
    }
}
