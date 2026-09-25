package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.ProviderProfile
import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** analyze_video_url 工具接线：假 transport，不碰真网。 */
class VideoUrlToolTest {
    private fun session(protocol: ProviderProtocol) = ProviderSession(ProviderProfile("测试", "https://api.example.com/v1", "key", "gemini-video"), protocol)
    private class FakeTransport(private val body: String) : WireTransport {
        var calls = 0
        var lastRequest: WireRequest? = null
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse { calls++; lastRequest = request; sink.onLine(body); return WireResponse(200, null, body.length.toLong(), null) }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }
    @Test fun geminiYoutubeUrlReturnsSummary() {
        val transport = FakeTransport("""{"candidates":[{"content":{"parts":[{"text":"总结完毕"}]}}]}""")
        val registry = ToolRegistry(); VideoUrlTool.register(registry, { session(ProviderProtocol.GEMINI) }, { transport })
        val out = registry.execute("analyze_video_url", """{"url":"https://youtu.be/abc","goal":"列出关键步骤"}""")
        assertTrue(out.ok); assertTrue(out.text.contains("总结完毕")); assertEquals(1, transport.calls); assertTrue(transport.lastRequest!!.body!!.contains("https://youtu.be/abc"))
    }
    @Test fun directFileFailsWithoutCallingNetwork() {
        val transport = FakeTransport("{}"); val registry = ToolRegistry(); VideoUrlTool.register(registry, { session(ProviderProtocol.GEMINI) }, { transport })
        val out = registry.execute("analyze_video_url", """{"url":"https://cdn.example.com/clip.webm"}""")
        assertTrue(out.ok); assertTrue(out.text.contains("url_rejected")); assertEquals(0, transport.calls)
    }
    @Test fun nonGeminiIsInvisibleAndDoesNotCallNetwork() {
        val transport = FakeTransport("{}"); val registry = ToolRegistry(); VideoUrlTool.register(registry, { session(ProviderProtocol.OPENAI_COMPAT) }, { transport })
        assertFalse(registry.specs().any { it.name == "analyze_video_url" })
        val out = registry.execute("analyze_video_url", """{"url":"https://youtu.be/abc"}""")
        assertTrue(out.text.contains("不存在") || out.text.contains("不可用")); assertEquals(0, transport.calls)
    }
    @Test fun rejectedPrivateUrlFailsWithoutCallingNetwork() {
        val transport = FakeTransport("{}"); val registry = ToolRegistry(); VideoUrlTool.register(registry, { session(ProviderProtocol.GEMINI) }, { transport })
        val out = registry.execute("analyze_video_url", """{"url":"http://127.0.0.1/watch?v=x"}""")
        assertTrue(out.ok); assertTrue(out.text.contains("url_rejected")); assertEquals(0, transport.calls)
    }
}
