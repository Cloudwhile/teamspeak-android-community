package com.cloudwhile.tscc.data.util

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class TsccServerUri(
    val host: String,
    val port: Int = 9987,
    val nickname: String? = null,
    val serverPassword: String? = null,
    val channel: String? = null,
    val channelPassword: String? = null,
    val privilegeKey: String? = null,
    val label: String? = null
) {
    fun toUriString(): String {
        val params = mutableListOf<String>()
        if (port != 9987) params += "port=$port"
        nickname?.takeIf { it.isNotBlank() }?.let { params += "nickname=${encode(it)}" }
        serverPassword?.takeIf { it.isNotBlank() }?.let { params += "password=${encode(it)}" }
        channel?.takeIf { it.isNotBlank() }?.let { params += "channel=${encode(it)}" }
        channelPassword?.takeIf { it.isNotBlank() }?.let { params += "channelpassword=${encode(it)}" }
        privilegeKey?.takeIf { it.isNotBlank() }?.let { params += "token=${encode(it)}" }
        label?.takeIf { it.isNotBlank() }?.let { params += "label=${encode(it)}" }

        val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        return if (params.isEmpty()) {
            "${TsccUriParser.SCHEME_PREFIX}$hostPart"
        } else {
            "${TsccUriParser.SCHEME_PREFIX}$hostPart?${params.joinToString("&")}"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

object TsccUriParser {

    const val SCHEME = "tscc"
    const val SCHEME_PREFIX = "tscc://"

    private val sharedTextTsccRegex = Regex(
        """tscc://[^\s"'<>，。！？；：、（）【】「」『』《》\u3000]+""",
        RegexOption.IGNORE_CASE
    )

    private val queryHostPlaceholderAuthorities = setOf("connect", "join", "invite", "server")

    fun isTsccDeepLink(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val t = raw.trim()
        if (t.startsWith("tscc://", ignoreCase = true) || t.startsWith("tscc:", ignoreCase = true)) {
            return true
        }
        if (t.startsWith("intent:", ignoreCase = true)) {
            return unwrapAndroidIntentUri(t) != null
        }
        return false
    }

    fun extractFirstTsccUri(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()
        if (isTsccDeepLink(trimmed) && !trimmed.contains(' ') && !trimmed.contains('\n')) {
            return unwrapAndroidIntentUri(trimmed) ?: trimmed
        }
        val hit = sharedTextTsccRegex.find(trimmed)?.value ?: return null
        return hit.trimEnd('.', ',', ';', ')', ']', '>', '！', '。', '，', '；')
    }

    /**
     * Parses `tscc://host[:port][/channel][?port=9987&nickname=...&password=...&channel=...&token=...]`
     * (including `intent://...#Intent;scheme=tscc;...;end`), as well as plain `host[:port]` inputs
     * when [requireScheme] is false.
     *
     * Strictly rejects non-`tscc` URI schemes.
     */
    fun parse(rawInput: String, requireScheme: Boolean = false): TsccServerUri? {
        val unwrapped = unwrapAndroidIntentUri(rawInput.trim()) ?: rawInput.trim()
        if (unwrapped.isEmpty()) return null

        val hasTsccScheme: Boolean
        val afterScheme = when {
            unwrapped.startsWith("tscc://", ignoreCase = true) -> {
                hasTsccScheme = true
                unwrapped.substring(7).trimStart('/')
            }
            unwrapped.startsWith("tscc:", ignoreCase = true) -> {
                hasTsccScheme = true
                unwrapped.substring(5).trimStart('/')
            }
            unwrapped.contains("://") || unwrapped.substringBefore(':').all { it.isLetter() } &&
                !unwrapped.startsWith("[") &&
                unwrapped.substringAfter(':', "").let { it.isNotEmpty() && it.toIntOrNull() == null } -> {
                return null
            }
            else -> {
                hasTsccScheme = false
                unwrapped
            }
        }

        if (requireScheme && !hasTsccScheme) return null

        // Strip URL fragment (#...) before splitting query and path
        val hashIdx = afterScheme.indexOf('#')
        val withoutFragment = if (hashIdx >= 0) afterScheme.substring(0, hashIdx) else afterScheme

        val qIdx = withoutFragment.indexOf('?')
        val authorityAndPath = if (qIdx >= 0) withoutFragment.substring(0, qIdx) else withoutFragment
        val queryRaw = if (qIdx >= 0) withoutFragment.substring(qIdx + 1) else ""

        val slashIdx = authorityAndPath.indexOf('/')
        val authority = (if (slashIdx >= 0) authorityAndPath.substring(0, slashIdx) else authorityAndPath).trim()
        val pathChannel = if (slashIdx >= 0) {
            authorityAndPath.substring(slashIdx + 1)
                .trim('/')
                .takeIf { it.isNotEmpty() }
                ?.split('/')
                ?.joinToString("/") { decode(it) }
        } else null

        val params = mutableMapOf<String, String>()
        if (queryRaw.isNotBlank()) {
            for (pair in queryRaw.split('&')) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = decode(pair.substring(0, eq)).trim().lowercase(Locale.ROOT)
                    val v = decode(pair.substring(eq + 1))
                    params[k] = v
                }
            }
        }

        val effectiveAuthority = if (
            authority.isEmpty() ||
            (hasTsccScheme && authority.lowercase(Locale.ROOT) in queryHostPlaceholderAuthorities &&
                (params["host"] != null || params["server"] != null || params["address"] != null))
        ) {
            (params["host"] ?: params["server"] ?: params["address"]).orEmpty().trim()
        } else {
            authority
        }

        if (effectiveAuthority.isEmpty()) return null

        var host: String
        var port = 9987

        if (effectiveAuthority.startsWith("[")) {
            // IPv6 literal: [2001:db8::1]:9987
            val closeBracket = effectiveAuthority.indexOf(']')
            if (closeBracket <= 1) return null
            host = effectiveAuthority.substring(1, closeBracket)
            if (closeBracket + 1 < effectiveAuthority.length) {
                if (effectiveAuthority[closeBracket + 1] != ':') return null
                port = effectiveAuthority.substring(closeBracket + 2).toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: return null
            }
        } else {
            val firstColon = effectiveAuthority.indexOf(':')
            val lastColon = effectiveAuthority.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                host = effectiveAuthority.substring(0, firstColon)
                port = effectiveAuthority.substring(firstColon + 1).toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: return null
            } else if (firstColon == 0) {
                return null
            } else {
                host = effectiveAuthority
            }
        }

        host = host.trim()
        if (host.isBlank() || host.any { it.isWhitespace() }) return null

        params["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }

        val resolvedChannel = params["channel"]?.takeIf { it.isNotBlank() } ?: pathChannel

        return TsccServerUri(
            host = host,
            port = port,
            nickname = params["nickname"]?.takeIf { it.isNotBlank() },
            serverPassword = params["password"],
            channel = resolvedChannel,
            channelPassword = params["channelpassword"],
            privilegeKey = params["token"] ?: params["privilegekey"],
            label = params["label"] ?: params["addbookmark"]
        )
    }

    /**
     * Unwraps Chromium/Android `intent://host?query#Intent;scheme=tscc;...;end` URIs into `tscc://host?query`.
     */
    private fun unwrapAndroidIntentUri(raw: String): String? {
        if (!raw.startsWith("intent:", ignoreCase = true)) return null
        val markerIdx = raw.indexOf("#Intent;", ignoreCase = true)
        if (markerIdx < 0) return null
        val bodyPart = raw.substring(if (raw.startsWith("intent://", ignoreCase = true)) 9 else 7, markerIdx)
        val metaPart = raw.substring(markerIdx + 8)
        val tokens = metaPart.split(';')
        val schemeToken = tokens.firstOrNull { it.startsWith("scheme=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?: return null
        if (!schemeToken.equals(SCHEME, ignoreCase = true)) return null
        return "$SCHEME_PREFIX${bodyPart.trimStart('/')}"
    }

    private fun decode(raw: String): String =
        runCatching { URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
            .getOrDefault(raw)
}
