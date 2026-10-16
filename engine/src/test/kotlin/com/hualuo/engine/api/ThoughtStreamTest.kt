package com.hualuo.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考段（reasoning_content）接线：走独立的 onThought 回调，不混进正文。
 * 正文要喂历史、思考不喂——两条通道在解析器里就分开，不许到界面层再拆。
 *
 * 调用形状钉死：OpenAiSseParser 的尾随 lambda 永远绑 onText（老测试全是
 * `OpenAiSseParser {}` 裸尾随写法），onThought 排在前面带默认值。
 */
class ThoughtStreamTest {

    @Test
    fun `reasoning content goes to thought callback not text`() {
        val text = StringBuilder()
        val thought = StringBuilder()
        val parser = OpenAiSseParser(
            onThought = { thought.append(it) },
            onText = { text.append(it) },
        )

        parser.onLine("""data: {"choices":[{"delta":{"reasoning_content":"先想想"}}]}""")
        parser.onLine("""data: {"choices":[{"delta":{"reasoning_content":"再想想"}}]}""")
        parser.onLine("""data: {"choices":[{"delta":{"content":"答案"}}]}""")
        parser.onLine("data: [DONE]")

        assertEquals("正文只有 content", "答案", text.toString())
        assertEquals("思考段按序拼齐", "先想想再想想", thought.toString())
        assertTrue(parser.finished)
        assertTrue("正文内容到场", parser.sawText)
    }

    @Test
    fun `non-streaming message shape also yields thought`() {
        val thought = StringBuilder()
        val parser = OpenAiSseParser(onThought = { thought.append(it) }, onText = {})

        parser.onLine("""{"choices":[{"message":{"role":"assistant","reasoning_content":"想过","content":"答"},"finish_reason":"stop"}]}""")

        assertEquals("想过", thought.toString())
        assertTrue("finish_reason 也算语义收尾", parser.finished)
    }

    @Test
    fun `legacy trailing lambda still binds onText and thought drops quietly`() {
        val text = StringBuilder()
        // 老调用形状：裸尾随 lambda 必须还绑 onText（GatewayBodyAttributionTest 全这么写）
        val parser = OpenAiSseParser { text.append(it) }

        val keepGoing = parser.onLine("""data: {"choices":[{"delta":{"reasoning_content":"没人听的思考"}}]}""")
        parser.onLine("""data: {"choices":[{"delta":{"content":"答"}}]}""")

        assertTrue(keepGoing)
        assertEquals("答", text.toString())
        assertFalse("没给回调时思考段无声落地", text.contains("思考"))
    }
}
