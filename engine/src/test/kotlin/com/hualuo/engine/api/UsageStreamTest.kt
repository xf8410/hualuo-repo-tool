package com.hualuo.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 流尾 usage 块的解析：provider 报多少记多少，缺字段记 null——引擎不估算不补数。
 * 这是顶栏「缓存命中 · 未缓存输入 · 输出」条的数据源，账不准不如没账。
 */
class UsageStreamTest {

    @Test
    fun `deepseek style cache fields are picked up`() {
        val parser = OpenAiSseParser { }
        parser.onLine("""data: {"choices":[{"delta":{"content":"好"}}]}""")
        parser.onLine(
            """data: {"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":30,"total_tokens":1030,"prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}}""",
        )
        parser.onLine("data: [DONE]")

        val u = parser.usage
        assertEquals(1000L, u?.promptTokens)
        assertEquals(30L, u?.completionTokens)
        assertEquals(1030L, u?.totalTokens)
        assertEquals(800L, u?.cachedTokens)
        assertEquals(200L, u?.uncachedTokens)
    }

    @Test
    fun `openai style cached tokens derive uncached`() {
        val parser = OpenAiSseParser { }
        parser.onLine(
            """data: {"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":640}}}""",
        )

        val u = parser.usage
        assertEquals(900L, u?.promptTokens)
        assertEquals(640L, u?.cachedTokens)
        assertEquals("未缓存没单独给时按 prompt−cached 推", 260L, u?.uncachedTokens)
    }

    @Test
    fun `no usage block means null not zeros`() {
        val parser = OpenAiSseParser { }
        parser.onLine("""data: {"choices":[{"delta":{"content":"好"}}]}""")
        parser.onLine("data: [DONE]")

        assertNull("流上没带 usage 就是 null，不是 0", parser.usage)
    }

    @Test
    fun `usage block with empty choices does not stop the stream`() {
        val parser = OpenAiSseParser { }
        // usage 块的 choices 常是空数组：解析完 usage 还得继续读到 [DONE] 才算收尾
        val keepGoing = parser.onLine(
            """data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":1}}""",
        )
        parser.onLine("data: [DONE]")

        assertEquals("usage 块不吃掉收尾", true, keepGoing)
        assertEquals(true, parser.finished)
        assertEquals(5L, parser.usage?.promptTokens)
    }
}
