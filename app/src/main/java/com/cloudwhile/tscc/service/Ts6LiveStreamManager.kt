package com.cloudwhile.tscc.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.cloudwhile.tscc.data.NativeBridge
import com.cloudwhile.tscc.data.model.BroadcastingStreamState
import com.cloudwhile.tscc.data.model.LiveStreamSourceType
import com.cloudwhile.tscc.data.model.LiveWatchStatus
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.Ts6StreamSignal
import com.cloudwhile.tscc.data.model.WatchingStreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoDecoderFallback
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoEncoderFallback
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * End-to-end TeamSpeak 6 WebRTC Live Streaming coordinator:
 * - Watching peers' desktop/mobile TS6 streams (P2P ICE/STUN + LAN mDNS `.local` resolution)
 * - Broadcasting Android screen share (MediaProjection) or front/back camera to TS6 peers
 * - All native WebRTC lifecycle & JNI disposal calls run strictly serialized on [rtcDispatcher]
 */
class Ts6LiveStreamManager(
    private val context: Context,
    private val onToast: (String, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "Ts6LiveStream"
        // TS6 wire protocol values (verified against tsserver 6.0.0-beta14):
        // StreamAccessibility: NONE=0, PUBLIC=1, CONTACTS_ONLY=2, PRIVATE=3
        // StreamMode: NONE=0, P2P=1, SFU=2
        private const val STREAM_ACCESS_PUBLIC = 1
        private const val STREAM_MODE_P2P = 1
        private const val STREAM_TYPE_SCREEN_DEFAULT = 2

        // Standard TS6 Desktop `StreamP2P` hardcoded `a=msid` stream identifier across Slot 0 (video) & Slot 1 (audio)
        internal const val TS6_P2P_STREAM_MSID = "stream-p2p-av"

        // TS6 server silently drops C2S commands whose escaped wire length exceeds 8192 bytes.
        // Keeping SDP <= 4400 chars guarantees JSON + TS3 command escaping stays well below 7200 bytes.
        internal const val MAX_SAFE_SDP_CHARS = 4400

        private val MDNS_HOST_REGEX = Regex(
            """([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.local|[A-Za-z0-9_-]+\.local)\b"""
        )

        private val sinkLock = Any()
        private val aliveTracks: MutableSet<VideoTrack> =
            Collections.newSetFromMap(IdentityHashMap())
        private val attachedTrackSinks: MutableMap<VideoTrack, MutableSet<VideoSink>> =
            IdentityHashMap()

        internal fun registerAliveTrack(track: VideoTrack) {
            synchronized(sinkLock) {
                aliveTracks.add(track)
            }
        }

        internal fun detachAllAndRetireTrack(track: VideoTrack?) {
            if (track == null) return
            synchronized(sinkLock) {
                aliveTracks.remove(track)
                val sinks = attachedTrackSinks.remove(track)
                sinks?.forEach { sink ->
                    runCatching { track.removeSink(sink) }
                }
            }
        }

        fun bindTrackSink(track: VideoTrack?, sink: VideoSink?) {
            if (track == null || sink == null) return
            synchronized(sinkLock) {
                if (!aliveTracks.contains(track)) return
                val set = attachedTrackSinks.getOrPut(track) {
                    Collections.newSetFromMap(IdentityHashMap())
                }
                if (set.add(sink)) {
                    runCatching { track.addSink(sink) }
                }
            }
        }

        fun unbindTrackSink(track: VideoTrack?, sink: VideoSink?) {
            if (track == null || sink == null) return
            synchronized(sinkLock) {
                val isAlive = aliveTracks.contains(track)
                val removed = attachedTrackSinks[track]?.remove(sink) == true
                if (isAlive && removed) {
                    runCatching { track.removeSink(sink) }
                }
            }
        }

        internal fun normalizeSdp(raw: String): String {
            val cleaned = raw
                .trimStart()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trimEnd('\n', ' ', '\t')
            if (cleaned.isEmpty()) return ""
            return cleaned.split('\n').joinToString("\r\n", postfix = "\r\n")
        }

        internal fun stripEmbeddedCandidatesFromSdp(rawSdp: String): String {
            val normalized = normalizeSdp(rawSdp)
            if (normalized.isEmpty()) return ""
            val filtered = normalized
                .trimEnd('\r', '\n')
                .split("\r\n")
                .filterNot { line ->
                    line.startsWith("a=candidate:", ignoreCase = true) ||
                        line.startsWith("a=end-of-candidates", ignoreCase = true)
                }
            return if (filtered.isEmpty()) "" else filtered.joinToString("\r\n", postfix = "\r\n")
        }

        internal fun videoCodecPriorityRank(codecName: String): Int = when (codecName.uppercase(java.util.Locale.ROOT)) {
            "H264" -> 0
            "VP8" -> 1
            "VP9" -> 2
            "AV1" -> 3
            else -> 4
        }

        /**
         * Reorders payload types on `m=video` lines so low-latency hardware-accelerated codecs
         * (`H264`, then `VP8`, along with their paired `apt=` RTX payload types) come ahead of
         * CPU-heavy `VP9`/`AV1`.
         */
        internal fun preferHardwareVideoCodecsInSdp(rawSdp: String): String {
            val normalized = normalizeSdp(rawSdp)
            if (normalized.isEmpty()) return ""
            val lines = normalized.trimEnd('\r', '\n').split("\r\n").toMutableList()

            var i = 0
            while (i < lines.size) {
                val mLine = lines[i]
                if (!mLine.startsWith("m=video ")) {
                    i++
                    continue
                }
                var sectionEnd = i + 1
                while (sectionEnd < lines.size && !lines[sectionEnd].startsWith("m=")) {
                    sectionEnd++
                }
                val mParts = mLine.split(' ').filter { it.isNotEmpty() }
                if (mParts.size > 4 && mParts[1] != "0") {
                    val headerPrefix = mParts.take(3).joinToString(" ")
                    val originalPts = mParts.drop(3)
                    val ptSet = originalPts.toSet()
                    val rtpmapCodec = mutableMapOf<String, String>()
                    val rtxApt = mutableMapOf<String, String>()

                    for (k in (i + 1) until sectionEnd) {
                        val ln = lines[k]
                        if (ln.startsWith("a=rtpmap:", ignoreCase = true)) {
                            val body = ln.substringAfter(':').trim()
                            val pt = body.substringBefore(' ').trim()
                            val encoding = body.substringAfter(' ', "").substringBefore('/').trim()
                            if (pt.isNotEmpty() && encoding.isNotEmpty()) {
                                rtpmapCodec[pt] = encoding.uppercase(java.util.Locale.ROOT)
                            }
                        } else if (ln.startsWith("a=fmtp:", ignoreCase = true)) {
                            val body = ln.substringAfter(':').trim()
                            val pt = body.substringBefore(' ').trim()
                            val params = body.substringAfter(' ', "")
                            val aptVal = params.split(';')
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("apt=", ignoreCase = true) }
                                ?.substringAfter('=')
                                ?.trim()
                            if (pt.isNotEmpty() && !aptVal.isNullOrEmpty()) {
                                rtxApt[pt] = aptVal
                            }
                        }
                    }

                    val rtxByPrimary = mutableMapOf<String, MutableList<String>>()
                    for (pt in originalPts) {
                        if (rtpmapCodec[pt] == "RTX") {
                            val primary = rtxApt[pt]
                            if (primary != null && primary in ptSet) {
                                rtxByPrimary.getOrPut(primary) { mutableListOf() }.add(pt)
                            }
                        }
                    }

                    val nonRtxPts = originalPts.filter { pt ->
                        !(rtpmapCodec[pt] == "RTX" && rtxApt[pt] in ptSet)
                    }
                    val sortedPrimaryPts = nonRtxPts.sortedBy { pt ->
                        videoCodecPriorityRank(rtpmapCodec[pt].orEmpty())
                    }

                    val reorderedPts = mutableListOf<String>()
                    for (pt in sortedPrimaryPts) {
                        reorderedPts.add(pt)
                        rtxByPrimary[pt]?.let { reorderedPts.addAll(it) }
                    }
                    // Preserve any orphan payload type that might not have matched
                    for (pt in originalPts) {
                        if (pt !in reorderedPts) reorderedPts.add(pt)
                    }

                    lines[i] = "$headerPrefix ${reorderedPts.joinToString(" ")}"
                }
                i = sectionEnd
            }

            return lines.joinToString("\r\n", postfix = "\r\n")
        }

        /**
         * Prepares an outgoing Viewer Answer SDP for smooth, high-bitrate, low-latency LAN/P2P playback:
         * 1. Strips embedded ICE candidates (pure Trickle ICE avoids duplicate candidates and bloated SDP).
         * 2. Prioritizes hardware `H264` & `VP8` on `m=video`.
         * 3. Injects `b=AS` / `b=TIAS` and `x-google-min/start/max-bitrate` fmtp hints on all negotiated
         *    video codecs (including `VP8`, which otherwise lacks an `a=fmtp:` line and starts at 300 kbps).
         */
        internal fun enhanceViewerAnswerSdp(
            rawSdp: String,
            maxBitrateKbps: Int = 12_000,
            startBitrateKbps: Int = 5_000,
            minBitrateKbps: Int = 1_500
        ): String {
            val base = preferHardwareVideoCodecsInSdp(stripEmbeddedCandidatesFromSdp(rawSdp))
            if (base.isEmpty()) return ""
            val srcLines = base.trimEnd('\r', '\n').split("\r\n")
            val outLines = ArrayList<String>(srcLines.size + 8)
            val bweParams =
                "x-google-min-bitrate=$minBitrateKbps;x-google-start-bitrate=$startBitrateKbps;x-google-max-bitrate=$maxBitrateKbps"

            var i = 0
            while (i < srcLines.size) {
                val line = srcLines[i]
                if (!line.startsWith("m=video ")) {
                    outLines.add(line)
                    i++
                    continue
                }
                var sectionEnd = i + 1
                while (sectionEnd < srcLines.size && !srcLines[sectionEnd].startsWith("m=")) {
                    sectionEnd++
                }
                val section = srcLines.subList(i, sectionEnd)
                val mParts = line.split(' ').filter { it.isNotEmpty() }
                if (mParts.size <= 3 || mParts[1] == "0") {
                    outLines.addAll(section)
                    i = sectionEnd
                    continue
                }

                val hasBandwidth = section.any {
                    it.startsWith("b=AS:", ignoreCase = true) || it.startsWith("b=TIAS:", ignoreCase = true)
                }
                val hasMediaConnLine = section.drop(1).any { it.startsWith("c=") }
                val primaryVideoPts = mutableSetOf<String>()
                val ptsWithFmtp = mutableSetOf<String>()

                for (ln in section.drop(1)) {
                    if (ln.startsWith("a=rtpmap:", ignoreCase = true)) {
                        val body = ln.substringAfter(':').trim()
                        val pt = body.substringBefore(' ').trim()
                        val codec = body.substringAfter(' ', "").substringBefore('/').trim().uppercase(java.util.Locale.ROOT)
                        if (codec in setOf("H264", "VP8", "VP9", "AV1")) {
                            primaryVideoPts.add(pt)
                        }
                    } else if (ln.startsWith("a=fmtp:", ignoreCase = true)) {
                        val pt = ln.substringAfter(':').trim().substringBefore(' ').trim()
                        if (pt.isNotEmpty()) ptsWithFmtp.add(pt)
                    }
                }

                var bandwidthInserted = hasBandwidth
                for ((idxInSec, secLine) in section.withIndex()) {
                    if (idxInSec == 0) {
                        outLines.add(secLine)
                        if (!bandwidthInserted && !hasMediaConnLine) {
                            outLines.add("b=AS:$maxBitrateKbps")
                            outLines.add("b=TIAS:${maxBitrateKbps * 1000}")
                            bandwidthInserted = true
                        }
                        continue
                    }

                    if (secLine.startsWith("c=") && !bandwidthInserted) {
                        outLines.add(secLine)
                        outLines.add("b=AS:$maxBitrateKbps")
                        outLines.add("b=TIAS:${maxBitrateKbps * 1000}")
                        bandwidthInserted = true
                        continue
                    }

                    if (secLine.startsWith("a=rtpmap:", ignoreCase = true)) {
                        outLines.add(secLine)
                        val pt = secLine.substringAfter(':').trim().substringBefore(' ').trim()
                        if (pt in primaryVideoPts && pt !in ptsWithFmtp) {
                            outLines.add("a=fmtp:$pt $bweParams")
                            ptsWithFmtp.add(pt)
                        }
                        continue
                    }

                    if (secLine.startsWith("a=fmtp:", ignoreCase = true)) {
                        val pt = secLine.substringAfter(':').trim().substringBefore(' ').trim()
                        if (pt in primaryVideoPts && !secLine.contains("x-google-start-bitrate", ignoreCase = true)) {
                            outLines.add("${secLine.trimEnd(';')};$bweParams")
                        } else {
                            outLines.add(secLine)
                        }
                        continue
                    }

                    outLines.add(secLine)
                }

                i = sectionEnd
            }

            return outLines.joinToString("\r\n", postfix = "\r\n")
        }

        internal fun shouldSendLocalIceCandidate(rawCandidateSdp: String): Boolean {
            val s = rawCandidateSdp.trim()
            if (s.isEmpty()) return false
            if (s.contains(" 127.0.0.1 ") || s.contains(" ::1 ")) return false
            if (s.contains(" tcp ", ignoreCase = true)) return false
            if (s.contains(" fe80:", ignoreCase = true)) return false
            return true
        }

        private fun localCandidateSortKey(c: IceCandidate): Int {
            val s = c.sdp
            val parts = s.trim().split(Regex("\\s+"))
            val ip = parts.getOrNull(4).orEmpty()
            val isIpv4 = ip.isNotEmpty() && !ip.contains(':')
            return when {
                s.contains(" typ host", ignoreCase = true) && isIpv4 -> 0
                s.contains(" typ host", ignoreCase = true) -> 1
                s.contains(" typ srflx", ignoreCase = true) || s.contains(" typ prflx", ignoreCase = true) -> 2
                else -> 3
            }
        }

        /**
         * Ensures an outgoing Offer/Answer SDP stays strictly within TS6 server's 8192-byte
         * command limit after JSON + TS escaping while preserving valid CRLF formatting,
         * ICE credentials, DTLS fingerprint, BUNDLE, SSRC, playout-delay, and primary codecs.
         */
        internal fun compactSdpForTs6Wire(rawSdp: String): String {
            val normalized = normalizeSdp(rawSdp)
            if (normalized.length <= MAX_SAFE_SDP_CHARS) return normalized

            val lines = normalized.trimEnd('\r', '\n').split("\r\n")
            // Pass 1: drop passive TCP candidates, link-local IPv6 candidates, and non-essential experimental extmaps
            // (Keep `playout-delay` so real-time zero-buffering video playout remains active)
            val pass1 = lines.filterNot { line ->
                (line.startsWith("a=candidate:") && (
                    line.contains(" tcp ", ignoreCase = true) ||
                        line.contains(" fe80:", ignoreCase = true)
                    )) ||
                    (line.startsWith("a=extmap:") && !line.contains("playout-delay", ignoreCase = true) && (
                        line.contains("webrtc.org/experiments/", ignoreCase = true) ||
                            line.contains("video-timing", ignoreCase = true) ||
                            line.contains("color-space", ignoreCase = true) ||
                            line.contains("video-layers-allocation", ignoreCase = true)
                        ))
            }
            val joined1 = pass1.joinToString("\r\n", postfix = "\r\n")
            if (joined1.length <= MAX_SAFE_SDP_CHARS) return joined1

            // Pass 2: cap total candidate lines to the first 10 highest-priority UDP candidates
            var candidateCount = 0
            val pass2 = pass1.filter { line ->
                if (line.startsWith("a=candidate:")) {
                    candidateCount++
                    candidateCount <= 10
                } else {
                    true
                }
            }
            return pass2.joinToString("\r\n", postfix = "\r\n")
        }

        internal fun extractOfferSdpStatic(rawOffer: String): String? {
            val trimmed = rawOffer.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("v=0")) return normalizeSdp(trimmed)
            if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
                return parseSignalingItemsStatic(trimmed)
                    .filterIsInstance<ParsedSignaling.Sdp>()
                    .firstOrNull()
                    ?.sdp
            }
            // Fallback: Base64-encoded SDP or Base64-encoded JSON
            val decoded = runCatching {
                String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.trim()
            if (!decoded.isNullOrEmpty()) {
                if (decoded.startsWith("v=0")) return normalizeSdp(decoded)
                if (decoded.startsWith("{")) {
                    return parseSignalingItemsStatic(decoded)
                        .filterIsInstance<ParsedSignaling.Sdp>()
                        .firstOrNull()
                        ?.sdp
                }
            }
            return null
        }

        internal fun parseSignalingItemsStatic(rawJson: String): List<ParsedSignaling> {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.startsWith("v=0")) {
                return listOf(ParsedSignaling.Sdp("offer", normalizeSdp(trimmed)))
            }
            val root = runCatching { NativeBridge.jsonParser.parseToJsonElement(trimmed) }.getOrNull()
                ?: return emptyList()
            val out = mutableListOf<ParsedSignaling>()
            collectSignalingElementStatic(root, out)
            return out
        }

        private fun normalizeCandidateLine(rawCand: String): String {
            val c = rawCand.trim()
            return if (c.startsWith("a=", ignoreCase = true)) c.substring(2).trim() else c
        }

        private fun looksLikeIceCandidateString(s: String): Boolean {
            val c = s.trim()
            return c.startsWith("candidate:", ignoreCase = true) ||
                c.startsWith("a=candidate:", ignoreCase = true)
        }

        /**
         * Standard TS6 `Stream_Signaling_Commands_Base` JSON envelope (`{"cmd":"answer","args":{"answer":"..."}}`).
         */
        internal fun buildTs6AnswerSignalingJson(sdp: String): String {
            val wireSafeSdp = compactSdpForTs6Wire(sdp)
            return buildJsonObject {
                put("cmd", "answer")
                put("args", buildJsonObject {
                    put("answer", wireSafeSdp)
                })
            }.toString()
        }

        /**
         * Standard TS6 Offer / ReconnectOffer JSON envelope (`{"cmd":"offer","args":{"offer":"..."}}`).
         */
        internal fun buildTs6OfferSignalingJson(sdp: String, isReconnect: Boolean = false): String {
            val wireSafeSdp = compactSdpForTs6Wire(sdp)
            return buildJsonObject {
                put("cmd", if (isReconnect) "reconnectOffer" else "offer")
                put("args", buildJsonObject {
                    put("offer", wireSafeSdp)
                })
            }.toString()
        }

        /**
         * Standard TS6 trickle ICE JSON envelope (`{"cmd":"iceCandidate","args":{"sdp":"...","mid":"0","mLine":0}}`).
         */
        internal fun buildTs6IceCandidateSignalingJson(
            candidateSdp: String,
            sdpMid: String?,
            sdpMLineIndex: Int
        ): String {
            val cleanCand = normalizeCandidateLine(candidateSdp)
            val safeMid = sdpMid?.takeIf { it.isNotBlank() } ?: "0"
            val safeMLine = sdpMLineIndex.coerceAtLeast(0)
            return buildJsonObject {
                put("cmd", "iceCandidate")
                put("args", buildJsonObject {
                    put("sdp", cleanCand)
                    put("mid", safeMid)
                    put("mLine", safeMLine)
                })
            }.toString()
        }

        private fun extractTs6IceFromObject(obj: JsonObject, out: MutableList<ParsedSignaling>) {
            val rawCand = ((obj["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (obj["candidate"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                .orEmpty()
            val cleanCand = normalizeCandidateLine(rawCand)
            if (cleanCand.isEmpty()) return
            val iceMid = ((obj["mid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (obj["sdpMid"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?.takeIf { it.isNotBlank() }
                ?: "0"
            val iceMLine = (obj["mLine"] as? JsonPrimitive)?.intOrNull
                ?: (obj["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull
                ?: (obj["mLineIndex"] as? JsonPrimitive)?.intOrNull
                ?: (obj["mlineindex"] as? JsonPrimitive)?.intOrNull
                ?: 0
            out.add(ParsedSignaling.Ice(cleanCand, iceMid, iceMLine))
        }

        private fun collectSignalingElementStatic(el: JsonElement, out: MutableList<ParsedSignaling>) {
            when (el) {
                is JsonArray -> el.forEach { collectSignalingElementStatic(it, out) }
                is JsonPrimitive -> {
                    if (el.isString) {
                        val content = el.content.trim()
                        if (content.startsWith("v=0")) {
                            out.add(ParsedSignaling.Sdp("offer", normalizeSdp(content)))
                        } else if (content.startsWith("{") || content.startsWith("[")) {
                            runCatching { NativeBridge.jsonParser.parseToJsonElement(content) }
                                .getOrNull()
                                ?.let { collectSignalingElementStatic(it, out) }
                        }
                    }
                }
                is JsonObject -> {
                    // 0. Standard TeamSpeak 6 `Stream_Signaling_Commands_Base` schema: {"cmd": "...", "args": {...}}
                    val cmdStr = (el["cmd"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    if (cmdStr.isNotEmpty()) {
                        val argsEl = el["args"]
                        when {
                            cmdStr.equals("answer", ignoreCase = true) -> {
                                val ansSdp = when (argsEl) {
                                    is JsonObject -> ((argsEl["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                        ?: (argsEl["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                                    is JsonPrimitive -> argsEl.takeIf { it.isString }?.content
                                    else -> null
                                }.orEmpty()
                                if (ansSdp.trimStart().startsWith("v=0")) {
                                    out.add(ParsedSignaling.Sdp("answer", normalizeSdp(ansSdp)))
                                }
                                return
                            }

                            cmdStr.equals("offer", ignoreCase = true) ||
                                cmdStr.equals("reconnectOffer", ignoreCase = true) -> {
                                val offSdp = when (argsEl) {
                                    is JsonObject -> ((argsEl["offer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                        ?: (argsEl["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                                    is JsonPrimitive -> argsEl.takeIf { it.isString }?.content
                                    else -> null
                                }.orEmpty()
                                if (offSdp.trimStart().startsWith("v=0")) {
                                    out.add(ParsedSignaling.Sdp("offer", normalizeSdp(offSdp)))
                                }
                                return
                            }

                            cmdStr.equals("iceCandidate", ignoreCase = true) ||
                                cmdStr.equals("iceCandidates", ignoreCase = true) -> {
                                when (argsEl) {
                                    is JsonObject -> extractTs6IceFromObject(argsEl, out)
                                    is JsonArray -> argsEl.forEach { item ->
                                        if (item is JsonObject) {
                                            extractTs6IceFromObject(item, out)
                                        } else {
                                            collectSignalingElementStatic(item, out)
                                        }
                                    }
                                    else -> {}
                                }
                                return
                            }

                            cmdStr.equals("joinResponse", ignoreCase = true) -> {
                                if (argsEl is JsonObject) {
                                    val decPrim = argsEl["decision"] as? JsonPrimitive
                                    val decision = decPrim?.booleanOrNull
                                        ?: decPrim?.intOrNull?.let { it != 0 }
                                        ?: true
                                    if (!decision) {
                                        val msg = ((argsEl["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                            ?: (argsEl["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                                            .orEmpty()
                                        out.add(ParsedSignaling.JoinRejected(msg))
                                    } else {
                                        val offSdp = (argsEl["offer"] as? JsonPrimitive)
                                            ?.takeIf { it.isString }
                                            ?.content
                                            .orEmpty()
                                        if (offSdp.trimStart().startsWith("v=0")) {
                                            out.add(ParsedSignaling.Sdp("offer", normalizeSdp(offSdp)))
                                        }
                                    }
                                }
                                return
                            }

                            cmdStr.equals("reconnect", ignoreCase = true) -> {
                                out.add(ParsedSignaling.Reconnect)
                                return
                            }
                        }
                    }

                    val typeStr = ((el["type"] as? JsonPrimitive)?.content
                        ?: (el["signaling_type"] as? JsonPrimitive)?.content)
                        .orEmpty()
                        .trim()
                    val isIceType = typeStr.contains("candidate", ignoreCase = true) ||
                        typeStr.equals("ice", ignoreCase = true)

                    val mid = ((el["sdpMid"] as? JsonPrimitive)?.content
                        ?: (el["mid"] as? JsonPrimitive)?.content)
                        ?.takeIf { it.isNotBlank() }
                        ?: "0"
                    val mLineIdx = (el["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull
                        ?: (el["mLine"] as? JsonPrimitive)?.intOrNull
                        ?: (el["mLineIndex"] as? JsonPrimitive)?.intOrNull
                        ?: (el["mlineindex"] as? JsonPrimitive)?.intOrNull
                        ?: 0

                    // 1. Check explicit candidate field first
                    val candVal = el["candidate"]
                    if (candVal is JsonPrimitive && candVal.isString) {
                        val cleanCand = normalizeCandidateLine(candVal.content)
                        if (cleanCand.isNotEmpty()) {
                            out.add(ParsedSignaling.Ice(cleanCand, mid, mLineIdx))
                        }
                        return
                    } else if (candVal is JsonObject || candVal is JsonArray) {
                        collectSignalingElementStatic(candVal, out)
                        return
                    }

                    // 2. Check sdp field (guarding against C++ peers putting ICE candidate text in "sdp")
                    val sdpVal = el["sdp"]
                    if (sdpVal is JsonPrimitive && sdpVal.isString) {
                        val content = sdpVal.content
                        if (isIceType || looksLikeIceCandidateString(content)) {
                            val cleanCand = normalizeCandidateLine(content)
                            if (cleanCand.isNotEmpty()) {
                                out.add(ParsedSignaling.Ice(cleanCand, mid, mLineIdx))
                            }
                        } else if (content.trimStart().startsWith("v=0")) {
                            val effectiveType = typeStr.ifBlank { "offer" }
                            out.add(ParsedSignaling.Sdp(effectiveType, normalizeSdp(content)))
                        }
                        return
                    } else if (sdpVal is JsonObject) {
                        collectSignalingElementStatic(sdpVal, out)
                    }

                    // 3. Check direct "offer" / "answer" string or nested object fields
                    for ((key, defaultType) in listOf("offer" to "offer", "answer" to "answer")) {
                        when (val sub = el[key]) {
                            is JsonPrimitive -> if (sub.isString && sub.content.trimStart().startsWith("v=0")) {
                                out.add(ParsedSignaling.Sdp(defaultType, normalizeSdp(sub.content)))
                            }
                            is JsonObject, is JsonArray -> collectSignalingElementStatic(sub, out)
                            else -> {}
                        }
                    }

                    // 4. Check common nested signaling envelopes
                    for (wrapKey in listOf(
                        "args",
                        "description",
                        "ice",
                        "iceCandidate",
                        "iceCandidates",
                        "candidates",
                        "data",
                        "payload",
                        "message",
                        "signaling_data"
                    )) {
                        el[wrapKey]?.let { collectSignalingElementStatic(it, out) }
                    }
                }
            }
        }

        /**
         * Builds a standard RFC 6762 mDNS IPv4 (A) query packet (`unicastResponse` toggles QU `0x8001` vs QM `0x0001`).
         */
        internal fun buildMdnsQueryPacket(hostname: String, unicastResponse: Boolean = true): ByteArray {
            val clean = hostname.trim().trimEnd('.')
            val out = ByteArrayOutputStream(64)
            // Header: ID=0, Flags=0, QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
            out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in clean.split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                if (bytes.isEmpty() || bytes.size > 63) continue
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0) // root terminator
            val qClassHi = if (unicastResponse) 0x80.toByte() else 0x00.toByte()
            out.write(byteArrayOf(0x00, 0x01, qClassHi, 0x01))
            return out.toByteArray()
        }

        private fun decodeDnsName(buf: ByteArray, length: Int, startOffset: Int): Pair<String, Int>? {
            if (startOffset < 0 || startOffset >= length) return null
            val labels = ArrayList<String>(4)
            var pos = startOffset
            var nextPosAfterField = -1
            var pointerJumps = 0
            var guardSteps = 0

            while (pos < length && pointerJumps <= 16 && guardSteps < 64) {
                guardSteps++
                val lenByte = buf[pos].toInt() and 0xFF
                if (lenByte == 0) {
                    if (nextPosAfterField < 0) {
                        nextPosAfterField = pos + 1
                    }
                    return labels.joinToString(".") to nextPosAfterField
                }
                if ((lenByte and 0xC0) == 0xC0) {
                    if (pos + 1 >= length) return null
                    if (nextPosAfterField < 0) {
                        nextPosAfterField = pos + 2
                    }
                    val ptr = ((lenByte and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                    if (ptr >= length) return null
                    pos = ptr
                    pointerJumps++
                    continue
                }
                if ((lenByte and 0xC0) != 0) return null
                val labelStart = pos + 1
                val labelEnd = labelStart + lenByte
                if (labelEnd > length) return null
                labels.add(String(buf, labelStart, lenByte, Charsets.UTF_8))
                pos = labelEnd
            }
            return null
        }

        /**
         * Parses an RFC 1035 / RFC 6762 DNS response packet and returns the matching IPv4 A record.
         * When [expectedHostname] is supplied, only A records whose owner name matches (case-insensitively)
         * are accepted, ignoring unrelated LAN multicast chatter on UDP 5353.
         */
        internal fun parseDnsARecordResponse(
            buf: ByteArray,
            length: Int,
            expectedHostname: String? = null
        ): String? {
            if (length < 12) return null
            val expected = expectedHostname?.trim()?.trimEnd('.')?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            val nsCount = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)
            val arCount = ((buf[10].toInt() and 0xFF) shl 8) or (buf[11].toInt() and 0xFF)
            val totalRr = anCount + nsCount + arCount
            if (totalRr <= 0) return null

            var idx = 12
            repeat(qdCount) {
                val (_, nextIdx) = decodeDnsName(buf, length, idx) ?: return null
                if (nextIdx + 4 > length) return null
                idx = nextIdx + 4
            }
            repeat(totalRr) {
                val (rrName, nextIdx) = decodeDnsName(buf, length, idx) ?: return null
                idx = nextIdx
                if (idx + 10 > length) return null
                val rrType = ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx + 1].toInt() and 0xFF)
                val rrClass = (((buf[idx + 2].toInt() and 0xFF) shl 8) or (buf[idx + 3].toInt() and 0xFF)) and 0x7FFF
                val rdLength = ((buf[idx + 8].toInt() and 0xFF) shl 8) or (buf[idx + 9].toInt() and 0xFF)
                idx += 10
                if (idx + rdLength > length) return null
                val nameMatches = expected == null || rrName.trimEnd('.').equals(expected, ignoreCase = true)
                if (nameMatches && rrType == 1 && rrClass == 1 && rdLength == 4) {
                    val b0 = buf[idx].toInt() and 0xFF
                    val b1 = buf[idx + 1].toInt() and 0xFF
                    val b2 = buf[idx + 2].toInt() and 0xFF
                    val b3 = buf[idx + 3].toInt() and 0xFF
                    if (b0 in 1..223 && b0 != 127) {
                        return "$b0.$b1.$b2.$b3"
                    }
                }
                idx += rdLength
            }
            return null
        }
    }

    /**
     * Exposes hardware `MediaCodec` video decoders first (`H264`, `VP8`, plus hardware-only `VP9`),
     * with software fallback strictly restricted to lightweight `VP8`/`H264` so desktop TS6 never
     * forces mobile CPUs onto software `libdav1d` AV1 or software `libvpx` VP9 decoding.
     */
    private class HardwarePrioritizedVideoDecoderFactory(
        eglContext: EglBase.Context
    ) : VideoDecoderFactory {
        private val hardwareFactory = HardwareVideoDecoderFactory(eglContext)
        private val softwareFactory = SoftwareVideoDecoderFactory()

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            val hwCodecs = hardwareFactory.supportedCodecs
                .filterNot { it.name.equals("AV1", ignoreCase = true) }
            val swFallback = softwareFactory.supportedCodecs.filter { sw ->
                (sw.name.equals("VP8", ignoreCase = true) || sw.name.equals("H264", ignoreCase = true)) &&
                    hwCodecs.none { hw -> hw.name.equals(sw.name, ignoreCase = true) && hw.params == sw.params }
            }
            return (hwCodecs + swFallback)
                .sortedBy { videoCodecPriorityRank(it.name) }
                .toTypedArray()
        }

        override fun createDecoder(codecType: VideoCodecInfo): VideoDecoder? {
            val hw = hardwareFactory.createDecoder(codecType)
            val allowSwFallback = codecType.name.equals("VP8", ignoreCase = true) ||
                codecType.name.equals("H264", ignoreCase = true)
            val sw = if (allowSwFallback) softwareFactory.createDecoder(codecType) else null
            return when {
                hw != null && sw != null -> VideoDecoderFallback(sw, hw)
                hw != null -> hw
                else -> sw
            }
        }
    }

    /**
     * Exposes hardware `MediaCodec` video encoders (`H264`, `VP8`) with software `VP8`/`H264`
     * safety fallback, excluding CPU-intensive software `AV1`/`VP9` encoders.
     */
    private class HardwarePrioritizedVideoEncoderFactory(
        eglContext: EglBase.Context
    ) : VideoEncoderFactory {
        private val hardwareFactory = HardwareVideoEncoderFactory(
            eglContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        private val softwareFactory = SoftwareVideoEncoderFactory()

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            val hwCodecs = hardwareFactory.supportedCodecs.filter {
                it.name.equals("H264", ignoreCase = true) || it.name.equals("VP8", ignoreCase = true)
            }
            val swFallback = softwareFactory.supportedCodecs.filter { sw ->
                (sw.name.equals("VP8", ignoreCase = true) || sw.name.equals("H264", ignoreCase = true)) &&
                    hwCodecs.none { hw -> hw.name.equals(sw.name, ignoreCase = true) && hw.params == sw.params }
            }
            return (hwCodecs + swFallback)
                .sortedBy { videoCodecPriorityRank(it.name) }
                .toTypedArray()
        }

        override fun createEncoder(input: VideoCodecInfo): VideoEncoder? {
            val hw = hardwareFactory.createEncoder(input)
            val allowSwFallback = input.name.equals("VP8", ignoreCase = true) ||
                input.name.equals("H264", ignoreCase = true)
            val sw = if (allowSwFallback) softwareFactory.createEncoder(input) else null
            return when {
                hw != null && sw != null -> VideoEncoderFallback(sw, hw)
                hw != null -> hw
                else -> sw
            }
        }
    }

    // Dedicated single-thread dispatcher serializes all WebRTC state & native C++ disposal
    private val rtcDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TS6WebRtcCtrl").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in TS6LiveStreamManager coroutine", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + rtcDispatcher + exceptionHandler)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val mdnsCache = ConcurrentHashMap<String, String>()
    private val inFlightMdns = ConcurrentHashMap<String, Deferred<String?>>()

    private var eglBase: EglBase? = null
    private var peerFactory: PeerConnectionFactory? = null

    @Volatile
    var isEnglish: Boolean = false

    // --- Viewer State ---
    private val _watchingState = MutableStateFlow(WatchingStreamState())
    val watchingState: StateFlow<WatchingStreamState> = _watchingState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private var remoteAudioTrack: AudioTrack? = null
    private var viewerPeerConnection: PeerConnection? = null
    private var viewerSessionToken: Long = 0L
    private val pendingViewerIce = mutableListOf<IceCandidate>()
    private var viewerRemoteDescSet = false
    private var pendingWatchJoinStreamerClid: Int? = null
    private var watchTimeoutJob: Job? = null
    private var outputDeafened: Boolean = false

    private val resolutionProbeSink = VideoSink { frame ->
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w > 0 && h > 0) {
            val resLabel = "${w}×${h}"
            val cur = _watchingState.value
            if (cur.status != LiveWatchStatus.IDLE &&
                (cur.status != LiveWatchStatus.PLAYING || cur.resolutionText != resLabel)
            ) {
                val firstPlay = cur.status != LiveWatchStatus.PLAYING
                _watchingState.update {
                    if (it.status != LiveWatchStatus.IDLE) {
                        it.copy(
                            status = LiveWatchStatus.PLAYING,
                            resolutionText = resLabel,
                            errorMessage = null
                        )
                    } else it
                }
                if (firstPlay) {
                    scope.launch {
                        watchTimeoutJob?.cancel()
                        watchTimeoutJob = null
                    }
                }
            }
        }
    }

    // --- Broadcaster State ---
    private val _broadcastingState = MutableStateFlow(BroadcastingStreamState())
    val broadcastingState: StateFlow<BroadcastingStreamState> = _broadcastingState.asStateFlow()

    private val _localPreviewTrack = MutableStateFlow<VideoTrack?>(null)
    val localPreviewTrack: StateFlow<VideoTrack?> = _localPreviewTrack.asStateFlow()

    private var captureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var framePumpObserver: StaticFramePumpObserver? = null
    private val broadcasterPeers = ConcurrentHashMap<Int, BroadcasterPeerSession>()

    private class BroadcasterPeerSession(
        val viewerClid: Int,
        val pc: PeerConnection,
        var sender: RtpSender? = null,
        var initialOfferSent: Boolean = false,
        var remoteDescSet: Boolean = false,
        var disposed: Boolean = false,
        val gatherComplete: CompletableDeferred<Unit> = CompletableDeferred(),
        val firstHostGathered: CompletableDeferred<Unit> = CompletableDeferred(),
        val pendingLocalCandidates: MutableList<IceCandidate> = mutableListOf(),
        val queuedCandidates: MutableList<IceCandidate> = mutableListOf()
    )

    /**
     * Re-submits the latest captured frame at ~4 fps when Android's VirtualDisplay goes idle on
     * a motionless screen, ensuring newly joined WebRTC viewers immediately get an H.264/VP8 keyframe.
     */
    private inner class StaticFramePumpObserver(
        private val delegate: CapturerObserver
    ) : CapturerObserver {
        private val frameLock = Any()
        private var latestFrame: VideoFrame? = null
        @Volatile private var lastFrameNs: Long = 0L
        @Volatile private var active: Boolean = false
        private var pumpJob: Job? = null

        override fun onCapturerStarted(success: Boolean) {
            delegate.onCapturerStarted(success)
            if (success) {
                active = true
                startPumpLoop()
            }
        }

        override fun onCapturerStopped() {
            stop()
            runCatching { delegate.onCapturerStopped() }
        }

        override fun onFrameCaptured(frame: VideoFrame?) {
            val f = frame ?: return
            if (!active) return
            lastFrameNs = System.nanoTime()
            synchronized(frameLock) {
                if (active) {
                    f.retain()
                    val prev = latestFrame
                    latestFrame = f
                    prev?.release()
                }
            }
            delegate.onFrameCaptured(f)
        }

        private fun startPumpLoop() {
            pumpJob?.cancel()
            pumpJob = scope.launch(Dispatchers.Default) {
                while (isActive && active) {
                    delay(250L)
                    if (!active || broadcasterPeers.isEmpty()) continue
                    val nowNs = System.nanoTime()
                    if (nowNs - lastFrameNs < 220_000_000L) continue
                    var cloned: VideoFrame? = null
                    synchronized(frameLock) {
                        val cur = latestFrame
                        if (active && cur != null) {
                            cur.buffer.retain()
                            cloned = VideoFrame(cur.buffer, cur.rotation, nowNs)
                        }
                    }
                    if (cloned != null) {
                        try {
                            if (active) {
                                lastFrameNs = nowNs
                                delegate.onFrameCaptured(cloned)
                            }
                        } catch (_: Throwable) {
                        } finally {
                            cloned?.release()
                        }
                    }
                }
            }
        }

        fun nudgeImmediateFrame() {
            if (!active) return
            val nowNs = System.nanoTime()
            var cloned: VideoFrame? = null
            synchronized(frameLock) {
                val cur = latestFrame
                if (active && cur != null) {
                    cur.buffer.retain()
                    cloned = VideoFrame(cur.buffer, cur.rotation, nowNs)
                }
            }
            if (cloned != null) {
                try {
                    if (active) {
                        lastFrameNs = nowNs
                        delegate.onFrameCaptured(cloned)
                    }
                } catch (_: Throwable) {
                } finally {
                    cloned?.release()
                }
            }
        }

        fun stop() {
            active = false
            pumpJob?.cancel()
            pumpJob = null
            synchronized(frameLock) {
                latestFrame?.release()
                latestFrame = null
            }
        }
    }

    @Synchronized
    fun ensureEglContext(): EglBase.Context {
        val existing = eglBase ?: EglBase.create().also { eglBase = it }
        return existing.eglBaseContext
    }

    @Synchronized
    private fun ensureFactory(): PeerConnectionFactory {
        peerFactory?.let { return it }
        val eglCtx = ensureEglContext()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        // Disable WebRTC mic capture input so it never conflicts with our native 48kHz Opus voice engine
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        adm.setMicrophoneMute(true)

        val encoderFactory = HardwarePrioritizedVideoEncoderFactory(eglCtx)
        val decoderFactory = HardwarePrioritizedVideoDecoderFactory(eglCtx)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
            .also {
                adm.release()
                peerFactory = it
            }
    }

    private fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:turn.teamspeak.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:turn2.teamspeak.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        }
    }

    fun setGlobalOutputDeafened(deafened: Boolean) {
        scope.launch {
            outputDeafened = deafened
            updateRemoteAudioEnablement()
        }
    }

    fun toggleWatchAudioMute() {
        scope.launch {
            _watchingState.update { it.copy(streamAudioMuted = !it.streamAudioMuted) }
            updateRemoteAudioEnablement()
        }
    }

    fun setWatchMinimized(minimized: Boolean) {
        _watchingState.update {
            it.copy(
                isMinimized = minimized,
                isFullscreen = if (minimized) false else it.isFullscreen
            )
        }
    }

    fun setWatchFullscreen(fullscreen: Boolean) {
        _watchingState.update {
            it.copy(
                isFullscreen = fullscreen,
                isMinimized = if (fullscreen) false else it.isMinimized
            )
        }
    }

    private fun updateRemoteAudioEnablement() {
        val st = _watchingState.value
        val allowAudio = !outputDeafened && !st.streamAudioMuted
        runCatching { remoteAudioTrack?.setEnabled(allowAudio) }
    }

    // =========================================================================
    // LAN mDNS (.local) CANDIDATE RESOLUTION FOR WINDOWS DESKTOP PEERS
    // =========================================================================

    private fun lookupCachedMdnsInText(input: String): Pair<String, Boolean> {
        if (!input.contains(".local", ignoreCase = true)) return input to true
        val hosts = MDNS_HOST_REGEX.findAll(input).map { it.value }.distinct().toList()
        if (hosts.isEmpty()) return input to true
        var result = input
        var allCached = true
        for (host in hosts) {
            val cached = mdnsCache[host.trim().trimEnd('.').lowercase(java.util.Locale.ROOT)]
            if (!cached.isNullOrEmpty()) {
                result = result.replace(host, cached)
            } else {
                allCached = false
            }
        }
        return result to allCached
    }

    private suspend fun resolveMdnsHost(hostname: String): String? {
        val key = hostname.trim().trimEnd('.').lowercase(java.util.Locale.ROOT)
        if (key.isEmpty()) return null
        mdnsCache[key]?.let { return it }

        val deferred = inFlightMdns.computeIfAbsent(key) {
            ioScope.async {
                try {
                    queryMdnsOnLan(hostname)
                } finally {
                    inFlightMdns.remove(key)
                }
            }
        }
        val resolved = runCatching { deferred.await() }.getOrNull()
        if (!resolved.isNullOrEmpty()) {
            mdnsCache[key] = resolved
        }
        return resolved
    }

    private fun queryMdnsOnLan(hostname: String): String? {
        var multicastLock: WifiManager.MulticastLock? = null
        var mcastSocket: MulticastSocket? = null
        var ucastSocket: DatagramSocket? = null
        val joinedIfaces = mutableListOf<NetworkInterface>()
        val group = runCatching { InetAddress.getByName("224.0.0.251") }.getOrNull() ?: return null
        val groupSockAddr = InetSocketAddress(group, 5353)
        try {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wm?.createMulticastLock("TS6MdnsResolve")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }

            mcastSocket = runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 70
                    bind(InetSocketAddress(5353))
                }
            }.getOrNull()

            if (mcastSocket != null) {
                val ifaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
                    .getOrDefault(emptyList())
                    .filter { iface ->
                        runCatching {
                            iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                                Collections.list(iface.inetAddresses).any { it is Inet4Address }
                        }.getOrDefault(false)
                    }
                if (ifaces.isNotEmpty()) {
                    for (iface in ifaces) {
                        if (runCatching { mcastSocket.joinGroup(groupSockAddr, iface) }.isSuccess) {
                            joinedIfaces.add(iface)
                        }
                    }
                }
                if (joinedIfaces.isEmpty()) {
                    @Suppress("DEPRECATION")
                    runCatching { mcastSocket.joinGroup(group) }
                }
            }

            ucastSocket = runCatching {
                DatagramSocket().apply {
                    reuseAddress = true
                    soTimeout = 60
                }
            }.getOrNull()

            val quBytes = buildMdnsQueryPacket(hostname, unicastResponse = true)
            val qmBytes = buildMdnsQueryPacket(hostname, unicastResponse = false)

            fun sendQueries() {
                val quPkt = DatagramPacket(quBytes, quBytes.size, group, 5353)
                val qmPkt = DatagramPacket(qmBytes, qmBytes.size, group, 5353)
                runCatching { ucastSocket?.send(quPkt) }
                runCatching { mcastSocket?.send(qmPkt) }
                runCatching { mcastSocket?.send(quPkt) }
            }

            sendQueries()
            val recvBuf = ByteArray(1500)
            val startMs = System.currentTimeMillis()
            val deadline = startMs + 280L
            var retried = false

            while (System.currentTimeMillis() < deadline) {
                if (!retried && System.currentTimeMillis() - startMs >= 110L) {
                    retried = true
                    sendQueries()
                }
                for (sock in listOfNotNull(ucastSocket, mcastSocket)) {
                    val pkt = DatagramPacket(recvBuf, recvBuf.size)
                    val gotPacket = runCatching {
                        sock.receive(pkt)
                        true
                    }.getOrDefault(false)
                    if (gotPacket && pkt.length >= 12) {
                        val ip = parseDnsARecordResponse(pkt.data, pkt.length, hostname)
                        if (!ip.isNullOrEmpty()) return ip
                    }
                }
            }
            return null
        } catch (_: Throwable) {
            return null
        } finally {
            if (mcastSocket != null) {
                for (iface in joinedIfaces) {
                    runCatching { mcastSocket.leaveGroup(groupSockAddr, iface) }
                }
                runCatching { mcastSocket.close() }
            }
            runCatching { ucastSocket?.close() }
            runCatching {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }
    }

    private suspend fun resolveMdnsInText(input: String): String {
        val (fastResult, allCached) = lookupCachedMdnsInText(input)
        if (allCached) return fastResult
        val hosts = MDNS_HOST_REGEX.findAll(fastResult).map { it.value }.distinct().toList()
        var result = fastResult
        for (host in hosts) {
            val ip = resolveMdnsHost(host)
            if (!ip.isNullOrEmpty()) {
                result = result.replace(host, ip)
            }
        }
        return result
    }

    // =========================================================================
    // VIEWER FLOW (Watch TS6 Peer Live Stream)
    // =========================================================================

    fun watchPeerStream(peer: OnlineClient) {
        if (!NativeBridge.ensureLoaded()) return
        scope.launch {
            closeViewerInternal(notifyServer = true)
            val myToken = ++viewerSessionToken

            val knownStreamId = peer.streamId.trim()
            _watchingState.value = WatchingStreamState(
                status = LiveWatchStatus.CONNECTING,
                streamerClientId = peer.id,
                streamerName = peer.nickname,
                streamId = knownStreamId,
                streamTitle = peer.streamName.ifBlank {
                    if (isEnglish) "${peer.nickname}'s Stream" else "${peer.nickname} 的直播"
                },
                streamType = if (peer.streamType > 0) peer.streamType else STREAM_TYPE_SCREEN_DEFAULT
            )

            if (knownStreamId.isNotEmpty()) {
                pendingWatchJoinStreamerClid = null
                NativeBridge.nativeJoinStreamRequest(knownStreamId, peer.id, "", false)
            } else {
                pendingWatchJoinStreamerClid = peer.id
                NativeBridge.nativeRequestStreamInfo(peer.id)
            }

            watchTimeoutJob?.cancel()
            watchTimeoutJob = scope.launch {
                delay(18_000L)
                if (viewerSessionToken == myToken && _watchingState.value.status == LiveWatchStatus.CONNECTING) {
                    _watchingState.update {
                        it.copy(
                            status = LiveWatchStatus.ERROR,
                            errorMessage = if (isEnglish) {
                                "P2P media handshake timed out. Check LAN connectivity, UDP firewall, or VPN."
                            } else {
                                "P2P 媒体握手超时，请检查双方局域网互通、防火墙或 VPN 设置"
                            }
                        )
                    }
                }
            }
        }
    }

    fun stopWatching() {
        scope.launch {
            closeViewerInternal(notifyServer = true)
        }
    }

    fun onWatchCommandError(reason: String) {
        scope.launch {
            watchTimeoutJob?.cancel()
            watchTimeoutJob = null
            pendingWatchJoinStreamerClid = null
            if (_watchingState.value.status != LiveWatchStatus.IDLE) {
                _watchingState.update {
                    it.copy(
                        status = LiveWatchStatus.ERROR,
                        errorMessage = reason.ifBlank {
                            if (isEnglish) "Watch stream request rejected" else "观看直播请求被拒绝"
                        }
                    )
                }
            }
        }
    }

    private fun closeViewerInternal(notifyServer: Boolean) {
        viewerSessionToken++
        watchTimeoutJob?.cancel()
        watchTimeoutJob = null
        pendingWatchJoinStreamerClid = null

        val prev = _watchingState.value
        val prevRemoteTrack = _remoteVideoTrack.value
        val pc = viewerPeerConnection

        _remoteVideoTrack.value = null
        remoteAudioTrack = null
        viewerPeerConnection = null
        pendingViewerIce.clear()
        viewerRemoteDescSet = false
        _watchingState.value = WatchingStreamState()

        if (notifyServer &&
            prev.status != LiveWatchStatus.IDLE &&
            prev.streamId.isNotEmpty() &&
            prev.streamerClientId > 0 &&
            NativeBridge.ensureLoaded()
        ) {
            runCatching {
                NativeBridge.nativeJoinStreamRequest(prev.streamId, prev.streamerClientId, "", true)
            }
        }

        detachAllAndRetireTrack(prevRemoteTrack)
        if (pc != null) {
            runCatching { pc.close() }
            runCatching { pc.dispose() }
        }
    }

    private class ViewerGatherTracker {
        val gatherComplete = CompletableDeferred<Unit>()
        val firstHostGathered = CompletableDeferred<Unit>()
        var answerSent: Boolean = false
        val pendingLocalCandidates = mutableListOf<IceCandidate>()
    }

    private fun createViewerPeerConnection(
        streamId: String,
        streamerClid: Int,
        token: Long,
        gatherTracker: ViewerGatherTracker
    ): PeerConnection? {
        viewerPeerConnection?.let { stale ->
            detachAllAndRetireTrack(_remoteVideoTrack.value)
            _remoteVideoTrack.value = null
            remoteAudioTrack = null
            runCatching { stale.close() }
            runCatching { stale.dispose() }
            viewerPeerConnection = null
        }

        val factory = runCatching { ensureFactory() }.getOrElse { err ->
            Log.e(TAG, "WebRTC factory init failed", err)
            _watchingState.update {
                it.copy(
                    status = LiveWatchStatus.ERROR,
                    errorMessage = if (isEnglish) {
                        "Failed to initialize WebRTC video decoder"
                    } else {
                        "WebRTC 解码引擎初始化失败"
                    }
                )
            }
            return null
        }

        // Do NOT pre-add RECV_ONLY transceivers before setRemoteDescription(OFFER)!
        // Letting setRemoteDescription(OFFER) create Unified Plan transceivers guarantees
        // 100% exact m-line order matching (audio, video, SCTP data channel) with Desktop TS6.
        val pc = factory.createPeerConnection(
            buildRtcConfig(),
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    scope.launch {
                        if (viewerSessionToken != token) return@launch
                        when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                // Keep CONNECTING spinner until resolutionProbeSink decodes the first VideoFrame
                                if (_watchingState.value.status == LiveWatchStatus.CONNECTING) {
                                    watchTimeoutJob?.cancel()
                                    watchTimeoutJob = scope.launch {
                                        delay(10_000L)
                                        if (viewerSessionToken == token &&
                                            _watchingState.value.status == LiveWatchStatus.CONNECTING
                                        ) {
                                            _watchingState.update {
                                                it.copy(
                                                    status = LiveWatchStatus.ERROR,
                                                    errorMessage = if (isEnglish) {
                                                        "P2P channel connected, but no video frames received (please retry)"
                                                    } else {
                                                        "P2P 通道已连通，但未收到对端视频帧（请尝试重试）"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                watchTimeoutJob?.cancel()
                                _watchingState.update {
                                    it.copy(
                                        status = LiveWatchStatus.ERROR,
                                        errorMessage = if (isEnglish) {
                                            "WebRTC P2P direct connection failed (UDP blocked or peer unreachable)"
                                        } else {
                                            "WebRTC P2P 直连失败（UDP 端口受阻或对端网络不可达）"
                                        }
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                    if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                        gatherTracker.gatherComplete.complete(Unit)
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    val c = candidate ?: return
                    if (!shouldSendLocalIceCandidate(c.sdp)) return
                    gatherTracker.firstHostGathered.complete(Unit)
                    scope.launch {
                        if (viewerSessionToken != token || !NativeBridge.ensureLoaded()) return@launch
                        if (!gatherTracker.answerSent) {
                            gatherTracker.pendingLocalCandidates.add(c)
                        } else {
                            val activeStreamId = streamId.ifBlank { _watchingState.value.streamId }
                            val payload = buildTs6IceCandidateSignalingJson(c.sdp, c.sdpMid, c.sdpMLineIndex)
                            NativeBridge.nativeSendStreamSignaling(activeStreamId, streamerClid, payload)
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                override fun onAddStream(stream: MediaStream?) {
                    val v = stream?.videoTracks?.firstOrNull()
                    val a = stream?.audioTracks?.firstOrNull()
                    scope.launch {
                        if (viewerSessionToken != token) return@launch
                        v?.let { attachIncomingVideoTrack(it) }
                        a?.let { attachIncomingAudioTrack(it) }
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    val track = receiver?.track() ?: return
                    scope.launch {
                        if (viewerSessionToken != token) return@launch
                        when (track) {
                            is VideoTrack -> attachIncomingVideoTrack(track)
                            is AudioTrack -> attachIncomingAudioTrack(track)
                        }
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track() ?: return
                    scope.launch {
                        if (viewerSessionToken != token) return@launch
                        when (track) {
                            is VideoTrack -> attachIncomingVideoTrack(track)
                            is AudioTrack -> attachIncomingAudioTrack(track)
                        }
                    }
                }
            }
        ) ?: return null

        viewerPeerConnection = pc
        return pc
    }

    private fun attachIncomingVideoTrack(track: VideoTrack) {
        val prev = _remoteVideoTrack.value
        if (prev === track) return
        if (prev != null) {
            detachAllAndRetireTrack(prev)
        }
        registerAliveTrack(track)
        runCatching { track.setEnabled(true) }
        bindTrackSink(track, resolutionProbeSink)
        // Mount track so the EGL TextureView warms up behind the CONNECTING spinner;
        // status transitions to PLAYING inside resolutionProbeSink on the very first decoded frame.
        _remoteVideoTrack.value = track
    }

    private fun attachIncomingAudioTrack(track: AudioTrack) {
        remoteAudioTrack = track
        updateRemoteAudioEnablement()
    }

    private suspend fun handleRemoteOfferForViewer(streamId: String, streamerClid: Int, rawSdpText: String) {
        val token = viewerSessionToken
        val resolvedOfferSdp = normalizeSdp(resolveMdnsInText(rawSdpText))
        if (resolvedOfferSdp.isEmpty()) return

        val gatherTracker = ViewerGatherTracker()
        val pc = createViewerPeerConnection(streamId, streamerClid, token, gatherTracker) ?: return
        viewerRemoteDescSet = false

        val setRemoteOk = CompletableDeferred<String?>()
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    setRemoteOk.complete(null)
                }

                override fun onSetFailure(error: String?) {
                    setRemoteOk.complete(
                        error ?: if (isEnglish) "Failed to parse SDP Offer" else "SDP Offer 解析失败"
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, resolvedOfferSdp)
        )

        val remoteErr = setRemoteOk.await()
        if (viewerSessionToken != token || viewerPeerConnection !== pc) return
        if (remoteErr != null) {
            Log.e(TAG, "Viewer setRemoteDescription failed: $remoteErr")
            _watchingState.update {
                it.copy(
                    status = LiveWatchStatus.ERROR,
                    errorMessage = if (isEnglish) {
                        "Stream media negotiation failed: $remoteErr"
                    } else {
                        "直播媒体协商失败: $remoteErr"
                    }
                )
            }
            return
        }

        viewerRemoteDescSet = true
        // Strictly clamp viewer transceivers to receive-only so WebRTC never opens AudioRecord
        // to compete with AndroidAudioIo's 48kHz Opus microphone capture!
        runCatching {
            pc.transceivers.forEach { tr ->
                when (tr.direction) {
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV ->
                        tr.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY ->
                        tr.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
                    else -> {}
                }
            }
        }
        val drained = pendingViewerIce.toList()
        pendingViewerIce.clear()
        drained.forEach { runCatching { pc.addIceCandidate(it) } }

        val createdAnswer = CompletableDeferred<SessionDescription?>()
        pc.createAnswer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    createdAnswer.complete(desc)
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Viewer createAnswer failed: $error")
                    createdAnswer.complete(null)
                }
            },
            MediaConstraints()
        )

        val answer = createdAnswer.await() ?: return
        if (viewerSessionToken != token || viewerPeerConnection !== pc) return

        val reorderedAnswerSdp = preferHardwareVideoCodecsInSdp(answer.description)
        val localAnswerDesc = SessionDescription(SessionDescription.Type.ANSWER, reorderedAnswerSdp)

        val setLocalOk = CompletableDeferred<Boolean>()
        pc.setLocalDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    setLocalOk.complete(true)
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Viewer setLocalDescription failed: $error")
                    setLocalOk.complete(false)
                }
            },
            localAnswerDesc
        )
        if (!setLocalOk.await() || viewerSessionToken != token || viewerPeerConnection !== pc) return

        // Pure Trickle ICE: send compact, candidate-free, hardware-prioritized high-bitrate Answer SDP
        // immediately after setLocalDescription (zero gather delay), then flush LAN IPv4 host candidates first.
        val activeStreamId = streamId.ifBlank { _watchingState.value.streamId }
        val enhancedAnswerSdp = enhanceViewerAnswerSdp(reorderedAnswerSdp)
        val answerJson = buildTs6AnswerSignalingJson(enhancedAnswerSdp)
        NativeBridge.nativeSendStreamSignaling(activeStreamId, streamerClid, answerJson)

        gatherTracker.answerSent = true
        val bufferedLocal = gatherTracker.pendingLocalCandidates
            .filter { shouldSendLocalIceCandidate(it.sdp) }
            .sortedBy { localCandidateSortKey(it) }
        gatherTracker.pendingLocalCandidates.clear()
        for (c in bufferedLocal) {
            val payload = buildTs6IceCandidateSignalingJson(c.sdp, c.sdpMid, c.sdpMLineIndex)
            NativeBridge.nativeSendStreamSignaling(activeStreamId, streamerClid, payload)
        }
    }

    // =========================================================================
    // BROADCASTER FLOW (Share Android Screen or Camera to TS6 Channel)
    // =========================================================================

    fun startScreenBroadcast(
        projectionResultData: Intent,
        title: String,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        bitrateKbps: Int = 3500
    ) {
        if (!NativeBridge.ensureLoaded()) return
        scope.launch {
            stopBroadcastInternal(notifyServer = true)
            val fallbackScreenTitle = if (isEnglish) "Screen Share" else "手机屏幕共享"
            _broadcastingState.value = BroadcastingStreamState(
                isBroadcasting = false,
                isStarting = true,
                sourceType = LiveStreamSourceType.SCREEN,
                streamTitle = title.ifBlank { fallbackScreenTitle },
                bitrateKbps = bitrateKbps,
                width = width,
                height = height,
                fps = fps
            )

            // Android 14+ strictly requires FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION to be active first
            val svcIntent = Intent(context, VoiceCallService::class.java).apply {
                action = VoiceCallService.ACTION_PROMOTE_SCREEN_SHARE
            }
            runCatching { ContextCompat.startForegroundService(context, svcIntent) }
            withTimeoutOrNull(2200L) {
                VoiceCallService.mediaProjectionFgsReady.first { it }
            }

            val startedOk = runCatching {
                val factory = ensureFactory()
                val eglCtx = ensureEglContext()
                val helper = SurfaceTextureHelper.create("TS6ScreenCapture", eglCtx)
                    ?: throw IllegalStateException(
                        if (isEnglish) "Failed to create OpenGL video capture texture" else "无法创建 OpenGL 视频采集纹理"
                    )
                val capturer = ScreenCapturerAndroid(
                    projectionResultData,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopBroadcast()
                        }
                    }
                )
                val src = factory.createVideoSource(true)
                val pump = StaticFramePumpObserver(src.capturerObserver)
                capturer.initialize(helper, context, pump)
                capturer.startCapture(width, height, fps)

                val track = factory.createVideoTrack("ts6_local_screen", src)
                registerAliveTrack(track)
                track.setEnabled(true)

                captureHelper = helper
                videoCapturer = capturer
                videoSource = src
                framePumpObserver = pump
                localVideoTrack = track
                _localPreviewTrack.value = track
                _broadcastingState.update { it.copy(isBroadcasting = true, isStarting = false) }

                NativeBridge.nativeSetupStream(
                    name = title.ifBlank { fallbackScreenTitle },
                    streamType = LiveStreamSourceType.SCREEN.tsType,
                    bitrateKbps = bitrateKbps,
                    accessibility = STREAM_ACCESS_PUBLIC,
                    mode = STREAM_MODE_P2P,
                    viewerLimit = 12,
                    audio = false
                )
            }.getOrElse { err ->
                Log.e(TAG, "Screen share start failed", err)
                onToast(
                    if (isEnglish) "Failed to start screen share: ${err.message}" else "启动屏幕共享失败: ${err.message}",
                    true
                )
                false
            }

            if (!startedOk) {
                stopBroadcastInternal(notifyServer = false)
            }
        }
    }

    fun startCameraBroadcast(
        title: String,
        frontCamera: Boolean = true,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        bitrateKbps: Int = 2500
    ) {
        if (!NativeBridge.ensureLoaded()) return
        scope.launch {
            stopBroadcastInternal(notifyServer = true)
            val fallbackCameraTitle = if (isEnglish) "Camera Live" else "摄像头直播"
            _broadcastingState.value = BroadcastingStreamState(
                isBroadcasting = false,
                isStarting = true,
                sourceType = LiveStreamSourceType.CAMERA,
                streamTitle = title.ifBlank { fallbackCameraTitle },
                bitrateKbps = bitrateKbps,
                width = width,
                height = height,
                fps = fps,
                isFrontCamera = frontCamera
            )

            val svcIntent = Intent(context, VoiceCallService::class.java).apply {
                action = VoiceCallService.ACTION_PROMOTE_CAMERA_SHARE
            }
            runCatching { ContextCompat.startForegroundService(context, svcIntent) }

            val startedOk = runCatching {
                val enumerator = Camera2Enumerator(context)
                val devNames = enumerator.deviceNames
                val chosenName = devNames.firstOrNull {
                    if (frontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
                } ?: devNames.firstOrNull()
                    ?: throw IllegalStateException(
                        if (isEnglish) "No available camera device detected" else "未检测到可用摄像头设备"
                    )

                val capturer = enumerator.createCapturer(chosenName, null)
                    ?: throw IllegalStateException(
                        if (isEnglish) "Failed to initialize camera capturer" else "初始化摄像头采集器失败"
                    )

                val factory = ensureFactory()
                val eglCtx = ensureEglContext()
                val helper = SurfaceTextureHelper.create("TS6CameraCapture", eglCtx)
                    ?: throw IllegalStateException(
                        if (isEnglish) "Failed to create camera OpenGL texture" else "无法创建摄像头 OpenGL 纹理"
                    )
                val src = factory.createVideoSource(false)
                val pump = StaticFramePumpObserver(src.capturerObserver)
                capturer.initialize(helper, context, pump)
                capturer.startCapture(width, height, fps)

                val track = factory.createVideoTrack("ts6_local_camera", src)
                registerAliveTrack(track)
                track.setEnabled(true)

                captureHelper = helper
                videoCapturer = capturer
                videoSource = src
                framePumpObserver = pump
                localVideoTrack = track
                _localPreviewTrack.value = track
                _broadcastingState.update { it.copy(isBroadcasting = true, isStarting = false) }

                NativeBridge.nativeSetupStream(
                    name = title.ifBlank { fallbackCameraTitle },
                    streamType = LiveStreamSourceType.CAMERA.tsType,
                    bitrateKbps = bitrateKbps,
                    accessibility = STREAM_ACCESS_PUBLIC,
                    mode = STREAM_MODE_P2P,
                    viewerLimit = 12,
                    audio = false
                )
            }.getOrElse { err ->
                Log.e(TAG, "Camera broadcast start failed", err)
                onToast(
                    if (isEnglish) "Failed to start camera stream: ${err.message}" else "启动摄像头直播失败: ${err.message}",
                    true
                )
                false
            }

            if (!startedOk) {
                stopBroadcastInternal(notifyServer = false)
            }
        }
    }

    fun switchBroadcastCamera() {
        scope.launch {
            val camCapturer = videoCapturer as? CameraVideoCapturer ?: return@launch
            runCatching {
                camCapturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFront: Boolean) {
                        _broadcastingState.update {
                            if (it.isBroadcasting) it.copy(isFrontCamera = isFront) else it
                        }
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {}
                })
            }
        }
    }

    fun stopBroadcast() {
        scope.launch {
            stopBroadcastInternal(notifyServer = true)
        }
    }

    private fun disposeBroadcasterPeer(sess: BroadcasterPeerSession) {
        if (sess.disposed) return
        sess.disposed = true
        sess.gatherComplete.complete(Unit)
        sess.firstHostGathered.complete(Unit)
        runCatching {
            sess.sender?.let { sess.pc.removeTrack(it) }
            sess.sender = null
        }
        runCatching { sess.pc.close() }
        runCatching { sess.pc.dispose() }
    }

    /**
     * Strictly serialized & idempotent broadcast teardown:
     * 1. Atomically snapshots and nulls all references BEFORE calling stopCapture(),
     *    so MediaProjection.Callback.onStop() re-entry is a safe no-op (zero C++ double-dispose).
     * 2. Detaches RtpSenders and disposes PeerConnections BEFORE disposing VideoTrack/VideoSource.
     * 3. Stops capture while SurfaceTextureHelper's HandlerThread is still alive, then disposes helper last.
     */
    private fun stopBroadcastInternal(notifyServer: Boolean) {
        val prev = _broadcastingState.value
        val peers = broadcasterPeers.values.toList()
        broadcasterPeers.clear()

        val pump = framePumpObserver
        framePumpObserver = null

        val capturer = videoCapturer
        videoCapturer = null

        val track = localVideoTrack
        localVideoTrack = null

        val source = videoSource
        videoSource = null

        val helper = captureHelper
        captureHelper = null

        _localPreviewTrack.value = null
        _broadcastingState.value = BroadcastingStreamState()

        if (peers.isEmpty() &&
            pump == null &&
            capturer == null &&
            track == null &&
            source == null &&
            helper == null &&
            prev.streamId.isEmpty()
        ) {
            return
        }

        if (notifyServer && prev.streamId.isNotEmpty() && NativeBridge.ensureLoaded()) {
            runCatching { NativeBridge.nativeStopStream(prev.streamId) }
        }

        pump?.stop()
        peers.forEach { disposeBroadcasterPeer(it) }
        detachAllAndRetireTrack(track)

        runCatching { capturer?.stopCapture() }
        runCatching { helper?.stopListening() }
        runCatching { capturer?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { helper?.dispose() }
    }

    private suspend fun handleIncomingViewerJoinRequest(
        viewerClid: Int,
        streamId: String,
        isRemove: Boolean
    ) {
        if (viewerClid <= 0) return
        if (isRemove) {
            broadcasterPeers.remove(viewerClid)?.let { disposeBroadcasterPeer(it) }
            _broadcastingState.update {
                if (it.isBroadcasting) it.copy(viewerCount = broadcasterPeers.size) else it
            }
            return
        }

        val track = localVideoTrack
        val curBroadcast = _broadcastingState.value
        if (!curBroadcast.isBroadcasting || track == null) {
            NativeBridge.nativeRespondJoinStreamRequest(
                streamId,
                viewerClid,
                false,
                "",
                if (isEnglish) "Stream is not ready yet" else "直播尚未就绪"
            )
            return
        }

        val effectiveStreamId = streamId.ifBlank { curBroadcast.streamId }
        broadcasterPeers.remove(viewerClid)?.let { disposeBroadcasterPeer(it) }

        val factory = runCatching { ensureFactory() }.getOrNull() ?: return
        var sessionRef: BroadcasterPeerSession? = null

        val pc = factory.createPeerConnection(
            buildRtcConfig(),
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    scope.launch {
                        when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                // Push an immediate frame so viewers watching a motionless screen see video right away
                                framePumpObserver?.nudgeImmediateFrame()
                            }
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.CLOSED -> {
                                val cur = broadcasterPeers[viewerClid]
                                if (cur != null && cur === sessionRef) {
                                    broadcasterPeers.remove(viewerClid)
                                    disposeBroadcasterPeer(cur)
                                    _broadcastingState.update {
                                        if (it.isBroadcasting) it.copy(viewerCount = broadcasterPeers.size) else it
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    scope.launch {
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                // DTLS/SRTP transport is now fully established — burst frames so an IDR keyframe
                                // arrives immediately over active SRTP even when sharing a static Android screen.
                                framePumpObserver?.nudgeImmediateFrame()
                                delay(150L)
                                val s1 = sessionRef
                                if (s1 != null && !s1.disposed && broadcasterPeers[viewerClid] === s1) {
                                    framePumpObserver?.nudgeImmediateFrame()
                                }
                                delay(250L)
                                val s2 = sessionRef
                                if (s2 != null && !s2.disposed && broadcasterPeers[viewerClid] === s2) {
                                    framePumpObserver?.nudgeImmediateFrame()
                                }
                            }
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                val cur = broadcasterPeers[viewerClid]
                                if (cur != null && cur === sessionRef) {
                                    broadcasterPeers.remove(viewerClid)
                                    disposeBroadcasterPeer(cur)
                                    _broadcastingState.update {
                                        if (it.isBroadcasting) it.copy(viewerCount = broadcasterPeers.size) else it
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                    if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                        sessionRef?.gatherComplete?.complete(Unit)
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    val c = candidate ?: return
                    if (!shouldSendLocalIceCandidate(c.sdp)) return
                    sessionRef?.firstHostGathered?.complete(Unit)
                    scope.launch {
                        val cur = sessionRef
                        if (cur == null || cur.disposed || broadcasterPeers[viewerClid] !== cur || !NativeBridge.ensureLoaded()) {
                            return@launch
                        }
                        if (!cur.initialOfferSent) {
                            cur.pendingLocalCandidates.add(c)
                        } else {
                            val activeStreamId = effectiveStreamId.ifBlank { _broadcastingState.value.streamId }
                            if (activeStreamId.isNotEmpty()) {
                                val payload = buildTs6IceCandidateSignalingJson(c.sdp, c.sdpMid, c.sdpMLineIndex)
                                NativeBridge.nativeSendStreamSignaling(activeStreamId, viewerClid, payload)
                            }
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
            }
        ) ?: return

        // Standard TS6 `StreamP2P::InitTransceivers` contract requires 2 Unified Plan transceivers
        // bound to exact stream ID `"stream-p2p-av"`:
        //   Slot 0: MEDIA_TYPE_VIDEO (SEND_ONLY)
        //   Slot 1: MEDIA_TYPE_AUDIO (INACTIVE when stream audio = false)
        val videoTransceiver = runCatching {
            pc.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    listOf(TS6_P2P_STREAM_MSID)
                )
            )
        }.getOrNull()

        runCatching {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.INACTIVE,
                    listOf(TS6_P2P_STREAM_MSID)
                )
            )
        }

        val sender = videoTransceiver?.sender
        if (sender == null) {
            runCatching { pc.close() }
            runCatching { pc.dispose() }
            return
        }

        runCatching {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            val maxBps = (curBroadcast.bitrateKbps * 1000).coerceAtLeast(1_500_000)
            val minBps = (maxBps / 4).coerceIn(400_000, 1_200_000)
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = maxBps
                enc.minBitrateBps = minBps
                enc.maxFramerate = curBroadcast.fps
            }
            sender.parameters = params
        }

        val session = BroadcasterPeerSession(viewerClid = viewerClid, pc = pc, sender = sender)
        sessionRef = session
        broadcasterPeers[viewerClid] = session
        _broadcastingState.update {
            if (it.isBroadcasting) it.copy(viewerCount = broadcasterPeers.size) else it
        }

        framePumpObserver?.nudgeImmediateFrame()

        val createdOffer = CompletableDeferred<SessionDescription?>()
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    createdOffer.complete(desc)
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Broadcaster createOffer failed: $error")
                    createdOffer.complete(null)
                }
            },
            MediaConstraints()
        )

        val offer = createdOffer.await()
        if (offer == null || session.disposed || broadcasterPeers[viewerClid] !== session) return

        val reorderedOfferSdp = preferHardwareVideoCodecsInSdp(offer.description)
        val localOfferDesc = SessionDescription(SessionDescription.Type.OFFER, reorderedOfferSdp)

        val setLocalOk = CompletableDeferred<Boolean>()
        pc.setLocalDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    setLocalOk.complete(true)
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Broadcaster setLocalDescription failed: $error")
                    setLocalOk.complete(false)
                }
            },
            localOfferDesc
        )
        if (!setLocalOk.await() || session.disposed || broadcasterPeers[viewerClid] !== session) return

        // Pure Trickle ICE matching Standard TS6 `OnPumpCreateOfferSuccess`:
        // Send compact, candidate-free Offer SDP immediately via `respondjoinstreamrequest` (zero wait),
        // then flush buffered Trickle ICE candidates with LAN IPv4 host candidates first.
        val wireSafeOffer = compactSdpForTs6Wire(stripEmbeddedCandidatesFromSdp(reorderedOfferSdp))
        val activeStreamId = effectiveStreamId.ifBlank { _broadcastingState.value.streamId }

        NativeBridge.nativeRespondJoinStreamRequest(
            activeStreamId,
            viewerClid,
            true,
            wireSafeOffer,
            ""
        )
        session.initialOfferSent = true
        if (activeStreamId.isNotEmpty() && NativeBridge.ensureLoaded()) {
            val bufferedLocal = session.pendingLocalCandidates
                .filter { shouldSendLocalIceCandidate(it.sdp) }
                .sortedBy { localCandidateSortKey(it) }
            session.pendingLocalCandidates.clear()
            for (c in bufferedLocal) {
                val payload = buildTs6IceCandidateSignalingJson(c.sdp, c.sdpMid, c.sdpMLineIndex)
                NativeBridge.nativeSendStreamSignaling(activeStreamId, viewerClid, payload)
            }
        }
    }

    private suspend fun handleViewerReconnectRequest(viewerClid: Int, streamId: String) {
        val peerSession = broadcasterPeers[viewerClid] ?: return
        if (peerSession.disposed) return
        val activeStreamId = streamId.ifBlank { _broadcastingState.value.streamId }
        if (activeStreamId.isEmpty() || !NativeBridge.ensureLoaded()) return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val createdOffer = CompletableDeferred<SessionDescription?>()
        peerSession.pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    createdOffer.complete(desc)
                }

                override fun onCreateFailure(error: String?) {
                    createdOffer.complete(null)
                }
            },
            constraints
        )
        val offer = createdOffer.await() ?: return
        if (peerSession.disposed || broadcasterPeers[viewerClid] !== peerSession) return

        val reorderedOfferSdp = preferHardwareVideoCodecsInSdp(offer.description)
        val localOfferDesc = SessionDescription(SessionDescription.Type.OFFER, reorderedOfferSdp)

        val setLocalOk = CompletableDeferred<Boolean>()
        peerSession.pc.setLocalDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    setLocalOk.complete(true)
                }

                override fun onSetFailure(error: String?) {
                    setLocalOk.complete(false)
                }
            },
            localOfferDesc
        )
        if (!setLocalOk.await() || peerSession.disposed || broadcasterPeers[viewerClid] !== peerSession) return

        peerSession.remoteDescSet = false
        val cleanOfferSdp = stripEmbeddedCandidatesFromSdp(reorderedOfferSdp)
        val payload = buildTs6OfferSignalingJson(cleanOfferSdp, isReconnect = true)
        NativeBridge.nativeSendStreamSignaling(activeStreamId, viewerClid, payload)
    }

    // =========================================================================
    // TS6 CORE SIGNALING DISPATCHER
    // =========================================================================

    fun onServerDisconnected() {
        scope.launch {
            closeViewerInternal(notifyServer = false)
            stopBroadcastInternal(notifyServer = false)
        }
    }

    fun onTs6StreamSignal(sig: Ts6StreamSignal, myClientId: Int) {
        scope.launch {
            when (sig.eventType) {
                "started", "updated", "info" -> {
                    if (sig.clientId == myClientId && sig.streamId.isNotEmpty()) {
                        _broadcastingState.update { cur ->
                            if (cur.isBroadcasting || cur.isStarting) {
                                cur.copy(
                                    isBroadcasting = true,
                                    isStarting = false,
                                    streamId = sig.streamId,
                                    viewerCount = maxOf(cur.viewerCount, sig.viewerCount)
                                )
                            } else cur
                        }
                    }

                    if (pendingWatchJoinStreamerClid == sig.clientId && sig.streamId.isNotEmpty()) {
                        pendingWatchJoinStreamerClid = null
                        _watchingState.update {
                            it.copy(
                                streamId = sig.streamId,
                                streamTitle = sig.name.ifBlank { it.streamTitle },
                                streamType = if (sig.streamType > 0) sig.streamType else it.streamType
                            )
                        }
                        NativeBridge.nativeJoinStreamRequest(sig.streamId, sig.clientId, "", false)
                    } else if (_watchingState.value.streamerClientId == sig.clientId) {
                        _watchingState.update {
                            it.copy(
                                streamId = sig.streamId.ifBlank { it.streamId },
                                streamTitle = sig.name.ifBlank { it.streamTitle }
                            )
                        }
                    }
                }

                "stopped" -> {
                    if (sig.clientId == myClientId &&
                        (_broadcastingState.value.isBroadcasting || _broadcastingState.value.isStarting)
                    ) {
                        stopBroadcastInternal(notifyServer = false)
                    }
                    val curWatch = _watchingState.value
                    if (curWatch.status != LiveWatchStatus.IDLE &&
                        (curWatch.streamerClientId == sig.clientId ||
                            (sig.streamId.isNotEmpty() && curWatch.streamId == sig.streamId))
                    ) {
                        closeViewerInternal(notifyServer = false)
                        onToast(if (isEnglish) "Streamer ended the live stream" else "主播已结束直播", false)
                    }
                }

                "viewer_joined", "viewer_left" -> {
                    if (_broadcastingState.value.isBroadcasting &&
                        (sig.streamId.isEmpty() || sig.streamId == _broadcastingState.value.streamId)
                    ) {
                        if (sig.eventType == "viewer_left") {
                            broadcasterPeers.remove(sig.clientId)?.let { disposeBroadcasterPeer(it) }
                        } else {
                            framePumpObserver?.nudgeImmediateFrame()
                        }
                        _broadcastingState.update {
                            if (it.isBroadcasting) {
                                it.copy(viewerCount = maxOf(sig.viewerCount, broadcasterPeers.size))
                            } else it
                        }
                    }
                }

                "join_request" -> {
                    if (!sig.isRemove && sig.streamId.isNotEmpty()) {
                        _broadcastingState.update { cur ->
                            if ((cur.isBroadcasting || cur.isStarting) && cur.streamId.isEmpty()) {
                                cur.copy(streamId = sig.streamId)
                            } else cur
                        }
                    }
                    handleIncomingViewerJoinRequest(sig.clientId, sig.streamId, sig.isRemove)
                }

                "join_response" -> {
                    val curWatch = _watchingState.value
                    if (curWatch.status == LiveWatchStatus.IDLE) return@launch
                    if (!sig.decision) {
                        watchTimeoutJob?.cancel()
                        _watchingState.update {
                            it.copy(
                                status = LiveWatchStatus.ERROR,
                                errorMessage = sig.message.ifBlank {
                                    if (isEnglish) "Streamer declined the watch request" else "主播拒绝了观看请求"
                                }
                            )
                        }
                        return@launch
                    }
                    val streamId = sig.streamId.ifBlank { curWatch.streamId }
                    val streamerClid = if (sig.clientId > 0) sig.clientId else curWatch.streamerClientId
                    val offerSdp = extractOfferSdp(sig.offer)
                    if (!offerSdp.isNullOrBlank()) {
                        handleRemoteOfferForViewer(streamId, streamerClid, offerSdp)
                    }
                }

                "signaling" -> {
                    handleSignalingJson(sig.clientId, sig.streamId, sig.json)
                }
            }
        }
    }

    private suspend fun handleSignalingJson(peerClid: Int, streamId: String, rawJson: String) {
        if (rawJson.isBlank()) return
        val parsedItems = parseSignalingItems(rawJson)
        val curWatch = _watchingState.value
        val isFromWatchedStreamer = curWatch.status != LiveWatchStatus.IDLE &&
            (curWatch.streamerClientId == peerClid || (streamId.isNotEmpty() && curWatch.streamId == streamId))

        for (item in parsedItems) {
            when (item) {
                is ParsedSignaling.Sdp -> {
                    if (item.type.equals("offer", ignoreCase = true) && isFromWatchedStreamer) {
                        handleRemoteOfferForViewer(
                            streamId.ifBlank { curWatch.streamId },
                            peerClid,
                            item.sdp
                        )
                    } else if (item.type.equals("answer", ignoreCase = true)) {
                        val peerSession = broadcasterPeers[peerClid] ?: continue
                        if (peerSession.disposed) continue
                        val resolvedAnswerSdp = normalizeSdp(resolveMdnsInText(item.sdp))
                        if (resolvedAnswerSdp.isEmpty()) continue
                        val setAnsOk = CompletableDeferred<String?>()
                        peerSession.pc.setRemoteDescription(
                            object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    setAnsOk.complete(null)
                                }

                                override fun onSetFailure(error: String?) {
                                    setAnsOk.complete(
                                        error ?: if (isEnglish) "Failed to parse SDP Answer" else "SDP Answer 解析失败"
                                    )
                                }
                            },
                            SessionDescription(SessionDescription.Type.ANSWER, resolvedAnswerSdp)
                        )
                        val ansErr = setAnsOk.await()
                        if (peerSession.disposed || broadcasterPeers[peerClid] !== peerSession) continue
                        if (ansErr != null) {
                            Log.e(TAG, "Broadcaster setRemoteDescription(ANSWER) failed: $ansErr")
                            continue
                        }
                        peerSession.remoteDescSet = true
                        val queued = peerSession.queuedCandidates.toList()
                        peerSession.queuedCandidates.clear()
                        queued.forEach { runCatching { peerSession.pc.addIceCandidate(it) } }
                        framePumpObserver?.nudgeImmediateFrame()
                    }
                }

                is ParsedSignaling.Ice -> {
                    if (item.candidate.contains(" tcp ", ignoreCase = true) ||
                        item.candidate.contains(" fe80:", ignoreCase = true)
                    ) {
                        continue
                    }
                    val (fastCandidateStr, allCached) = lookupCachedMdnsInText(item.candidate)
                    if (allCached) {
                        val ice = IceCandidate(item.sdpMid, item.sdpMLineIndex, fastCandidateStr)
                        if (isFromWatchedStreamer) {
                            val pc = viewerPeerConnection
                            if (pc != null && viewerRemoteDescSet) {
                                runCatching { pc.addIceCandidate(ice) }
                            } else {
                                pendingViewerIce.add(ice)
                            }
                        } else {
                            val peerSession = broadcasterPeers[peerClid]
                            if (peerSession != null && !peerSession.disposed) {
                                if (peerSession.remoteDescSet) {
                                    runCatching { peerSession.pc.addIceCandidate(ice) }
                                } else {
                                    peerSession.queuedCandidates.add(ice)
                                }
                            }
                        }
                    } else {
                        // Resolve `.local` asynchronously on Dispatchers.IO so `rtcDispatcher` never stalls
                        // subsequent STUN/ICE candidates or SDP negotiation.
                        val watchTokenSnap = viewerSessionToken
                        val peerSessionSnap = broadcasterPeers[peerClid]
                        val midSnap = item.sdpMid
                        val mLineSnap = item.sdpMLineIndex
                        val candSnap = fastCandidateStr
                        ioScope.launch {
                            val resolvedStr = resolveMdnsInText(candSnap)
                            withContext(rtcDispatcher) {
                                val ice = IceCandidate(midSnap, mLineSnap, resolvedStr)
                                if (isFromWatchedStreamer) {
                                    if (viewerSessionToken != watchTokenSnap) return@withContext
                                    val pc = viewerPeerConnection
                                    if (pc != null && viewerRemoteDescSet) {
                                        runCatching { pc.addIceCandidate(ice) }
                                    } else {
                                        pendingViewerIce.add(ice)
                                    }
                                } else if (peerSessionSnap != null &&
                                    !peerSessionSnap.disposed &&
                                    broadcasterPeers[peerClid] === peerSessionSnap
                                ) {
                                    if (peerSessionSnap.remoteDescSet) {
                                        runCatching { peerSessionSnap.pc.addIceCandidate(ice) }
                                    } else {
                                        peerSessionSnap.queuedCandidates.add(ice)
                                    }
                                }
                            }
                        }
                    }
                }

                is ParsedSignaling.JoinRejected -> {
                    if (isFromWatchedStreamer) {
                        watchTimeoutJob?.cancel()
                        _watchingState.update {
                            it.copy(
                                status = LiveWatchStatus.ERROR,
                                errorMessage = item.reason.ifBlank {
                                    if (isEnglish) "Streamer declined the watch request" else "主播拒绝了观看请求"
                                }
                            )
                        }
                    }
                }

                is ParsedSignaling.Reconnect -> {
                    handleViewerReconnectRequest(peerClid, streamId)
                }
            }
        }
    }

    internal sealed interface ParsedSignaling {
        data class Sdp(val type: String, val sdp: String) : ParsedSignaling
        data class Ice(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : ParsedSignaling
        data class JoinRejected(val reason: String) : ParsedSignaling
        data object Reconnect : ParsedSignaling
    }

    internal fun extractOfferSdp(rawOffer: String): String? = extractOfferSdpStatic(rawOffer)

    internal fun parseSignalingItems(rawJson: String): List<ParsedSignaling> =
        parseSignalingItemsStatic(rawJson)

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
