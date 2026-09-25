package com.hualuo.engine.vision

import java.net.URI

/**
 * 视频 URL 准入与分类（analyze_video_url 工具的安检件，纯 JVM 全测）。
 *
 * 准入原则：
 *  - 只认 http/https；URL 自带凭据、内网、回环、链路本地、保留地址一律拒；
 *  - 放行两类：YouTube 链接，以及直接视频文件 URL；
 *  - 普通网页、平台分享页、HLS 第一版明确拒，不假装已经看过视频。
 */
object VideoUrlPolicy {

    enum class Kind { YOUTUBE, DIRECT_FILE }

    sealed class Verdict {
        data class Allowed(val kind: Kind) : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    private val DIRECT_EXTENSIONS = listOf("mp4", "webm", "mov", "m4v", "mkv")

    fun check(url: String): Verdict {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrElse {
            return Verdict.Rejected("这不是一个能解析的 URL：$trimmed")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Verdict.Rejected("只允许 http/https 链接（来了个「$scheme」）")
        }
        if (uri.userInfo != null) {
            return Verdict.Rejected("URL 里不能夹用户名或密码")
        }
        val host = uri.host?.trim()?.trimEnd('.')?.lowercase()
            ?: return Verdict.Rejected("URL 里没有主机名")
        if (isPrivateHost(host)) {
            return Verdict.Rejected("内网/回环/链路本地/保留地址不放行（$host）")
        }
        val path = uri.path?.lowercase().orEmpty()
        return when {
            isYouTube(host, path) -> Verdict.Allowed(Kind.YOUTUBE)
            isDirectFile(path) -> Verdict.Allowed(Kind.DIRECT_FILE)
            else -> Verdict.Rejected(
                "这是网页或平台分享页，不是直接视频地址。第一版只认 YouTube 链接与直接视频文件 URL（.mp4/.webm/.mov/.m4v/.mkv 结尾）",
            )
        }
    }

    /** 按文件后缀给 Gemini 一个不容易误报的 MIME；YouTube 没有可靠后缀，按 mp4 交由服务端处理。 */
    fun mimeType(url: String): String {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mov") -> "video/quicktime"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".m4v") -> "video/mp4"
            else -> "video/mp4"
        }
    }

    private fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.removeSurrounding("[", "]").lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "metadata.google.internal" || host == "metadata") return true
        if (host == "0.0.0.0" || host == "::" || host == "::1") return true
        if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("ff")) return true
        // fe80::/10 是链路本地地址；URI.host 可能是压缩写法，这里只拦明确前缀。
        if (host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")) return true
        if (host.startsWith("::ffff:")) {
            val embedded = host.substringAfterLast(':')
            if (isPrivateIpv4(embedded)) return true
        }
        return isPrivateIpv4(host)
    }

    private fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        val a = octets[0]
        val b = octets[1]
        val c = octets[2]
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 100 && b in 64..127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 0 && c == 0 -> true
            a == 192 && b == 0 && c == 2 -> true
            a == 192 && b == 88 && c == 99 -> true
            a == 192 && b == 168 -> true
            a == 198 && b in 18..19 -> true
            a == 198 && b == 51 && c == 100 -> true
            a == 203 && b == 0 && c == 113 -> true
            a >= 224 -> true
            else -> false
        }
    }

    private fun isYouTube(host: String, path: String): Boolean =
        (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host == "m.youtube.com") &&
            (path.startsWith("/watch") || path.startsWith("/shorts") || host == "youtu.be")

    private fun isDirectFile(path: String): Boolean {
        val clean = path.substringBefore('?').substringBefore('#')
        return DIRECT_EXTENSIONS.any { clean.endsWith(".$it") }
    }
}
