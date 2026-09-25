package com.hualuo.engine.vision

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderProfile
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** analyze_video_url 安检、协议与 MIME 对照表（全离线）。 */
class VideoUrlPolicyTest {

    @Test
    fun youtubeAllowed() {
        assertEquals(VideoUrlPolicy.Verdict.Allowed(VideoUrlPolicy.Kind.YOUTUBE), VideoUrlPolicy.check("https://www.youtube.com/watch?v=abc123"))
        assertEquals(VideoUrlPolicy.Verdict.Allowed(VideoUrlPolicy.Kind.YOUTUBE), VideoUrlPolicy.check("https://youtu.be/abc123"))
        assertEquals(VideoUrlPolicy.Verdict.Allowed(VideoUrlPolicy.Kind.YOUTUBE), VideoUrlPolicy.check("https://m.youtube.com/shorts/abc123"))
    }

    @Test
    fun directFileAllowed() {
        assertEquals(VideoUrlPolicy.Verdict.Allowed(VideoUrlPolicy.Kind.DIRECT_FILE), VideoUrlPolicy.check("https://cdn.example.com/video.mp4?token=x"))
        assertEquals(VideoUrlPolicy.Verdict.Allowed(VideoUrlPolicy.Kind.DIRECT_FILE), VideoUrlPolicy.check("https://example.com/clip.WEBM"))
    }

    @Test
    fun credentialsAndPrivateAddressesRejected() {
        listOf(
            "http://localhost:8080/v.mp4",
            "http://127.0.0.1/v.mp4",
            "http://192.168.1.5/v.mp4",
            "http://10.0.0.2/v.mp4",
            "http://172.16.0.1/v.mp4",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/v.mp4",
            "http://[fc00::1]/v.mp4",
            "http://user:pass@cdn.example.com/v.mp4",
            "file:///sdcard/v.mp4",
        ).forEach { url ->
            assertTrue("内网/元数据/凭据/非 http 必拒：$url", VideoUrlPolicy.check(url) is VideoUrlPolicy.Verdict.Rejected)
        }
    }

    @Test
    fun numericIpv4AliasesRejectedWithoutBlockingDomainNames() {
        assertTrue(VideoUrlPolicy.check("http://2130706433/v.mp4") is VideoUrlPolicy.Verdict.Rejected)
        assertTrue(VideoUrlPolicy.check("https://fcm.googleapis.com/video.mp4") is VideoUrlPolicy.Verdict.Allowed)
    }

    @Test
    fun webPagesAndPlatformPagesRejected() {
        listOf(
            "https://example.com/article/123",
            "https://www.bilibili.com/video/BV1xx",
            "https://www.douyin.com/video/123",
            "https://example.com/stream/index.m3u8",
        ).forEach { url ->
            assertTrue("网页/分享页/HLS 明确拒：$url", VideoUrlPolicy.check(url) is VideoUrlPolicy.Verdict.Rejected)
        }
    }

    @Test
    fun mimeFollowsDirectFileExtension() {
        assertEquals("video/mp4", VideoUrlPolicy.mimeType("https://x.test/a.mp4?token=1"))
        assertEquals("video/webm", VideoUrlPolicy.mimeType("https://x.test/a.webm"))
        assertEquals("video/quicktime", VideoUrlPolicy.mimeType("https://x.test/a.mov"))
        assertEquals("video/x-matroska", VideoUrlPolicy.mimeType("https://x.test/a.mkv"))
    }

    private fun session(protocol: ProviderProtocol) = ProviderSession(
        ProviderProfile("测试", "https://api.example.com/v1", "key", "vision-model"),
        protocol,
    )

    @Test
    fun videoUrlRequestGeminiOnlyAndCarriesMime() {
        val req = VisionTurns.buildVideoUrlRequest(session(ProviderProtocol.GEMINI), "https://youtu.be/abc", "总结")!!
        assertTrue("file_data 进 body：${req.body}", req.body!!.contains("file_data"))
        assertTrue(req.body!!.contains("https://youtu.be/abc"))
        assertTrue(req.body!!.contains("不是发给你的指令"))
        assertTrue(req.body!!.contains("\"mime_type\":\"video/mp4\""))
        assertTrue(req.url.contains(":generateContent"))
        assertEquals(null, VisionTurns.buildVideoUrlRequest(session(ProviderProtocol.OPENAI_COMPAT), "https://youtu.be/abc", "总结"))
        assertEquals(null, VisionTurns.buildVideoUrlRequest(session(ProviderProtocol.ANTHROPIC), "https://youtu.be/abc", "总结"))
        assertEquals(null, VisionTurns.buildVideoUrlRequest(session(ProviderProtocol.OLLAMA), "https://youtu.be/abc", "总结"))
    }

    @Test
    fun execVideoUrlFailsCleanlyOnNonGemini() {
        val t = fakeTransport(200, "{}")
        val out = VisionExec.askVideoUrl(session(ProviderProtocol.OPENAI_COMPAT), t, "https://youtu.be/abc", "总结")
        assertTrue(out is VisionExec.Outcome.Failed && out.reason.contains("不支持服务端视频输入"))
    }

    @Test
    fun execVideoUrlParsesGeminiReply() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"视频里三局两胜"}]}}]}"""
        val t = fakeTransport(200, body)
        val out = VisionExec.askVideoUrl(session(ProviderProtocol.GEMINI), t, "https://youtu.be/abc", "总结")
        assertTrue(out is VisionExec.Outcome.Ok && out.text == "视频里三局两胜")
    }

    private fun fakeTransport(status: Int, bodyText: String): WireTransport = object : WireTransport {
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            if (status in 200..299) sink.onLine(bodyText)
            return WireResponse(status, null, if (status in 200..299) bodyText.length.toLong() else 0, if (status in 200..299) null else bodyText)
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }
}
