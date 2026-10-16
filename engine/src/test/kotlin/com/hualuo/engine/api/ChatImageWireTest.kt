package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.RetryPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 图片进请求体的契约测试（2026-10-16 图片附件刀，纯 JVM）：
 *  - user 轮带图：content 升成 parts 数组（text 在前、image_url 在后，data URI 内联）；
 *  - 图文件读不动：退成纯文本字符串（与不带图的旧形状逐字节一致），不许发出半份；
 *  - 不带图的老形状一字不变（回归闸）。
 */
class ChatImageWireTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class CaptureTransport : WireTransport {
        var lastBody: String? = null
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            lastBody = request.body
            sink.onLine("""data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""")
            return WireResponse(200, null, 10L, null)
        }

        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private val profile = ProviderProfile("测试端", "https://gw.example.com", "sk-test", "m1")

    private fun clientOf(transport: WireTransport): OpenAiCompatClient = OpenAiCompatClient(
        transport = transport,
        slot = GenerationSlot(),
        policy = RetryPolicy(maxAutomaticRetries = 0, backoffBaseMs = 1L, backoffMaxMs = 2L),
        watchdog = IdleWatchdog(IdleWatchdog.GENERATION_IDLE_MS),
        sleeper = {},
    )

    @Test
    fun `user turn with image becomes parts array with inline data uri`() {
        val img = File(tmp.root, "shot.png")
        img.outputStream().use { it.write(byteArrayOf(1, 2, 3, 4)) }
        val transport = CaptureTransport()

        clientOf(transport).chatTurns(
            profile,
            listOf(ChatTurn("user", "看图说话", images = listOf(img.absolutePath))),
        ) { }

        val msg = Json.parseToJsonElement(transport.lastBody!!).jsonObject
            .getValue("messages").jsonArray.single().jsonObject
        val content = msg.getValue("content")
        assertTrue("带图必须升成 parts 数组", content is JsonArray)
        val parts = (content as JsonArray).map { it.jsonObject }
        assertEquals("text 分件在前", "text", parts[0].getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("看图说话", parts[0].getValue("text").jsonPrimitive.contentOrNull)
        assertEquals("image_url", parts[1].getValue("type").jsonPrimitive.contentOrNull)
        val url = parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.contentOrNull
        assertTrue("内联 data URI：$url", url != null && url.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `unreadable image falls back to plain text content`() {
        val transport = CaptureTransport()

        clientOf(transport).chatTurns(
            profile,
            listOf(ChatTurn("user", "图没了", images = listOf(File(tmp.root, "gone.jpg").absolutePath))),
        ) { }

        val msg = Json.parseToJsonElement(transport.lastBody!!).jsonObject
            .getValue("messages").jsonArray.single().jsonObject
        val content = msg.getValue("content")
        assertTrue("读不动必须退回纯文本，不许发半份", content is JsonPrimitive)
        assertEquals("图没了", (content as JsonPrimitive).contentOrNull)
    }

    @Test
    fun `no images keeps the old plain string shape`() {
        val transport = CaptureTransport()

        clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "老形状"))) { }

        val msg = Json.parseToJsonElement(transport.lastBody!!).jsonObject
            .getValue("messages").jsonArray.single().jsonObject
        assertTrue("不带图一字不变", msg.getValue("content") is JsonPrimitive)
        assertTrue("不带图不写 tools 之外的任何新字段", !msg.containsKey("images"))
    }

    @Test
    fun `assistant turn never carries images even if asked`() {
        val img = File(tmp.root, "a.jpg")
        img.outputStream().use { it.write(byteArrayOf(9)) }
        val transport = CaptureTransport()

        clientOf(transport).chatTurns(
            profile,
            listOf(ChatTurn("assistant", "答", images = listOf(img.absolutePath)), ChatTurn("user", "问")),
        ) { }

        val messages = Json.parseToJsonElement(transport.lastBody!!).jsonObject
            .getValue("messages").jsonArray
        val assistant = messages[0].jsonObject
        assertTrue("助手轮带图是无效输入，按纯文本发", assistant.getValue("content") is JsonObject || assistant.getValue("content") is JsonPrimitive)
        assertTrue("助手轮必须还是纯文本", assistant.getValue("content") is JsonPrimitive)
    }
}
