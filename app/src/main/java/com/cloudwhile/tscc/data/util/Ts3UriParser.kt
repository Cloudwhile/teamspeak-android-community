package com.cloudwhile.tscc.data.util

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class Ts3ServerUri(
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
        label?.takeIf { it.isNotBlank() }?.let { params += "addbookmark=${encode(it)}" }

        val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        return if (params.isEmpty()) {
            "ts3server://$hostPart"
        } else {
            "ts3server://$hostPart?${params.joinToString("&")}"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

object Ts3UriParser {

    /**
     * Parses `ts3server://host[:port]?port=9987&nickname=...&password=...&channel=...&token=...`
     * as well as `teamspeak://` and plain `host:port` user inputs.
     */
    fun parse(rawInput: String): Ts3ServerUri? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val withoutScheme = when {
            trimmed.startsWith("ts3server://", ignoreCase = true) -> trimmed.substring(12)
            trimmed.startsWith("teamspeak://", ignoreCase = true) -> trimmed.substring(12)
            trimmed.contains("://") -> return null
            else -> trimmed
        }

        val qIdx = withoutScheme.indexOf('?')
        val authorityRaw = if (qIdx >= 0) withoutScheme.substring(0, qIdx) else withoutScheme
        val queryRaw = if (qIdx >= 0) withoutScheme.substring(qIdx + 1) else ""

        val authority = authorityRaw.trimEnd('/')
        if (authority.isEmpty()) return null

        var host: String
        var port = 9987

        if (authority.startsWith("[")) {
            // IPv6 literal: [2001:db8::1]:9987
            val closeBracket = authority.indexOf(']')
            if (closeBracket <= 1) return null
            host = authority.substring(1, closeBracket)
            if (closeBracket + 1 < authority.length && authority[closeBracket + 1] == ':') {
                port = authority.substring(closeBracket + 2).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
        } else {
            val firstColon = authority.indexOf(':')
            val lastColon = authority.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                host = authority.substring(0, firstColon)
                port = authority.substring(firstColon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                host = authority
            }
        }

        if (host.isBlank()) return null

        val params = mutableMapOf<String, String>()
        if (queryRaw.isNotBlank()) {
            for (pair in queryRaw.split('&')) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = decode(pair.substring(0, eq)).lowercase()
                    val v = decode(pair.substring(eq + 1))
                    params[k] = v
                }
            }
        }

        params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }

        return Ts3ServerUri(
            host = host,
            port = port,
            nickname = params["nickname"],
            serverPassword = params["password"],
            channel = params["channel"],
            channelPassword = params["channelpassword"],
            privilegeKey = params["token"] ?: params["privilegekey"],
            label = params["addbookmark"] ?: params["label"]
        )
    }

    private fun decode(raw: String): String =
        runCatching { URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
            .getOrDefault(raw)
}
