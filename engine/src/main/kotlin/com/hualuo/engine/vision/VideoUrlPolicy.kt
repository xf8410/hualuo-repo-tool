package com.hualuo.engine.vision

import java.net.URI

/**
 * 视频 URL 准入与分类（analyze_video_url 工具的安检件，纯 JVM 全测）。
 *
 * Gemini 的 fileData.fileUri 第一版可靠支持的是 YouTube URL；任意公网 MP4
 * 不能假装成同一个形状直接交给服务端。以后若接文件上传/File API，再单独放行
 * 服务端文件 URI 或上传结果。
 */
object VideoUrlPolicy {

    enum class Kind { YOUTUBE }

    sealed class Verdict {
        data class Allowed(val kind: Kind) : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

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
            isDirectFile(path) -> Verdict.Rejected(
                "直接视频文件 URL 暂不接入：Gemini fileData.fileUri 当前不能把它当成 YouTube 链接直接读取；" +
                    "本版只放行 YouTube，后续接 File API/上传适配器后再开放",
            )
            else -> Verdict.Rejected(
                "这是网页或平台分享页，不是 YouTube 视频链接。第一版只支持 YouTube URL；" +
                    "普通网页、平台分享页、HLS 和直接 MP4 都不会在手机端偷偷下载。",
            )
        }
    }

    fun mimeType(@Suppress("UNUSED_PARAMETER") url: String): String = "video/mp4"

    private fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.removeSurrounding("[", "]").lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "metadata.google.internal" || host == "metadata") return true
        if (host.contains(":")) return isPrivateIpv6(host)
        if (isNumericIpv4Alias(host)) return isPrivateIpv4(decodeIpv4Alias(host))
        return isPrivateIpv4(host)
    }

    private fun isPrivateIpv6(host: String): Boolean {
        if (host == "::" || host == "::1") return true
        if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("ff")) return true
        if (host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")) return true
        val mapped = host.substringAfter("::ffff:", missingDelimiterValue = "")
        return mapped.isNotEmpty() && isPrivateIpv4(mapped)
    }

    private fun isNumericIpv4Alias(host: String): Boolean =
        host.isNotEmpty() && (host.all { it.isDigit() } || (host.startsWith("0x") && host.drop(2).all { it in "0123456789abcdefABCDEF" }))

    private fun decodeIpv4Alias(host: String): String {
        val lower = host.lowercase()
        val value = if (lower.startsWith("0x")) lower.removePrefix("0x").toLongOrNull(16) else lower.toLongOrNull(10) ?: return "0.0.0.0"
        if (value < 0L || value > 0xffff_ffffL) return "0.0.0.0"
        return "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}.${(value ushr 8) and 0xff}.${value and 0xff}"
    }

    private fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return true
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
        return listOf("mp4", "webm", "mov", "m4v", "mkv").any { clean.endsWith(".$it") }
    }
}
