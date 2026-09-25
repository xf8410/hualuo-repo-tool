package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.ProviderProfile
import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 本地视频账本的路径与对齐防线（纯 JVM）。 */
class VideoToolSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session() = ProviderSession(
        ProviderProfile("测试", "https://api.example.com/v1", "key", "eye"),
        ProviderProtocol.OPENAI_COMPAT,
    )

    private class NoNetwork : WireTransport {
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse = WireResponse(500, null, 0L, "not called")
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    @Test
    fun manifestWithTraversalFrameIsRejected() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        val manifest = File(inbox, "escape.manifest.json")
        manifest.writeText("""{"name":"escape","durationMs":1000,"frameTimes":[0],"frames":["../outside.jpg"]}""")
        assertTrue("路径穿越账本必须拒绝：${VideoTool.readManifest(manifest)}", VideoTool.readManifest(manifest) == null)
        val registry = ToolRegistry()
        VideoTool.register(registry, inbox, frames, { session() }, NoNetwork())
        val out = registry.execute("watch_video", """{"name":"escape"}""")
        assertTrue(out.text.contains("没有叫") || out.text.contains("路径不安全"))
    }

    @Test
    fun manifestWithMismatchedTimesAndFramesIsRejected() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        val manifest = File(inbox, "bad.manifest.json")
        manifest.writeText("""{"name":"bad","durationMs":1000,"frameTimes":[0,1000],"frames":["bad/f0.jpg"]}""")
        assertFalse("时间点和帧数必须一一对应", VideoTool.readManifest(manifest) != null)
    }

    @Test
    fun videoNameCannotContainPathSeparators() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        val registry = ToolRegistry()
        VideoTool.register(registry, inbox, frames, { session() }, NoNetwork())
        val out = registry.execute("watch_video", """{"name":"../outside"}""")
        assertTrue(out.text.contains("不合法"))
    }
}
