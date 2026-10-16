package com.hualuo.repotool.ui.state

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 思考胶囊接线（2026-10-14 刀）：reasoning_content 走独立回调进卡片折叠区，
 * 不进正文（正文要喂历史）；开关关着时收到也不上屏（旧 Agora thinkingEnabled 语义）。
 * 骨架与 ChatRuntimeTest 同款：worker 当场跑完，假 transport 按脚本喂 SSE 帧。
 */
class ChatRuntimeThinkingTest {

    private class MemPersist(initial: Map<String, String>) : UiPersistence {
        val map = initial.toMutableMap()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, value: String) { map[key] = value }
        override fun flush(): String? = null
        override fun drainMessages(): List<String> = emptyList()
    }

    private class FakeWire(
        private val steps: MutableList<(WireRequest, LineSink) -> WireResponse>,
    ) : WireTransport {
        val seen = mutableListOf<WireRequest>()
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            seen += request
            val step = if (steps.size > 1) steps.removeAt(0) else steps[0]
            return step(request, sink)
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private val configured = mapOf(
        "provider.base_url" to "https://gw.example.com",
        "provider.name" to "测试端",
    )

    private fun streamThoughtThenText(thought: String, text: String) = { _: WireRequest, sink: LineSink ->
        sink.onLine("""data: {"choices":[{"delta":{"reasoning_content":"$thought"}}]}""")
        sink.onLine("""data: {"choices":[{"delta":{"content":"$text"}}]}""")
        sink.onLine("data: [DONE]")
        WireResponse(200, null, 40L, null)
    }

    private fun runtimeOf(wire: FakeWire, thinkingOn: Boolean = true): ChatRuntime = ChatRuntime(
        MemPersist(configured),
        transportFactory = { wire },
        worker = { thread -> thread.run() },
        thinkingEnabled = { thinkingOn },
        clock = { 1_700_000_000_000L },
    )

    @Test
    fun thoughtLandsInFoldedBlockAndStaysOutOfHistory() {
        val wire = FakeWire(
            mutableListOf(
                streamThoughtThenText("先想一下", "答一"),
                streamThoughtThenText("再想想", "答二"),
            ),
        )
        val runtime = runtimeOf(wire)

        runtime.send("问一", "m")
        val card = runtime.messages[1]
        assertEquals("正文只有答案", "答一", card.text)
        assertEquals("思考进折叠区", "先想一下", card.thinkBody)
        assertEquals("折叠区有标签", "思考过程", card.thinkLabel)
        assertFalse("成功收场不带错误样式", card.isError)

        runtime.send("问二", "m")
        val secondBody = wire.seen[1].body!!
        assertFalse("思考段不许喂回历史", secondBody.contains("先想一下"))
        assertEquals("答二", runtime.messages[3].text)
        assertEquals("再想想", runtime.messages[3].thinkBody)
    }

    @Test
    fun thinkingOffSuppressesDisplayButKeepsAnswer() {
        val wire = FakeWire(mutableListOf(streamThoughtThenText("秘密思考", "答")))
        val runtime = runtimeOf(wire, thinkingOn = false)

        runtime.send("问", "m")

        val card = runtime.messages[1]
        assertEquals("答", card.text)
        assertNull("关着时思考不上屏（对齐旧 Agora）", card.thinkBody)
        assertNull(card.thinkLabel)
    }
}
