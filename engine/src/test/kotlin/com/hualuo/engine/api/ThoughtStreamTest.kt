package com.hualuo.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考段（reasoning_content）接线：走独立的 onThought 回调，不混进正文。
 * 正文要喂历史、思考不喂——两条通道在解析器里就分开，不许到界面层再拆。
 */
class ThoughtStreamTest {

    @Test
    fun `reasoning content goes to thought callback not text`() {
        val text = StringBuilder()
        val thought = StringBuilder()
        val parser = OpenAiSseParser({ text.append(it) }, { thought.append(it) })

        parser.onLine("""data: {"choices":[{"delta":{"reasoning_content":"先想想"}}]}""")
        parser.onLine("""data: {"choices":[{"delta":{"reasoning_content":"再想想"}}]}""")
        parser.onLine("""data: {"choices":[{"delta":{"content":"答案"}}]}""")
        parser.onLine("data: [DONE]")

        assertEquals("正文只有 content", "答案", text.toString())
        assertEquals("思考段按序拼齐", "先想想再想想", thought.toString())
        assertTrue(parser.finished)
        assertTrue("思考段不算正文内容字节", parser.sawText)
    }

    @Test
    fun `non-streaming message shape also yields thought`() {
        val thought = StringBuilder()
        val parser = OpenAiSseParser({}, { thought.append(it) })

        parser.onLine("""{"choices":[{"message":{"role":"assistant","reasoning_content":"想过","content":"答"},"finish_reason":"stop"}]}""")

        assertEquals("想过", thought.toString())
        assertTrue("finish_reason 也算语义收尾", parser.finished)
    }

    @Test
    fun `thought callback defaults to dropping chunks quietly`() {
        val text = StringBuilder()
        // 旧调用形状：只给 onText，思考段不许炸也不许混进正文
        val parser = OpenAiSseParser({ text.append(it) })

        val keepGoing = parser.onLine("""data: {"choices":[{"delta":{"reasoning_content":"没人听的思考"}}]}""")
        parser.onLine("""data: {"choices":[{"delta":{"content":"答"}}]}""")

        assertTrue(keepGoing)
        assertEquals("答", text.toString())
        assertFalse("没给回调时思考段无声落地", text.contains("思考"))
    }
}
