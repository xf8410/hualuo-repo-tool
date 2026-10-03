package com.hualuo.engine.search

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五家提供商的发请求与收结果对照表（全离线：取网动作喂假响应，绝不碰真网）。
 *
 * 钉的事：
 *  - 认不出的 id 回默认那家（设置文件手可编辑，脏值不许把工具打死）；
 *  - 要密钥的三家没密钥当场拒，**且不许发请求**（发出去就是拿空钥匙撞人家门）；
 *  - 每家的请求形状（方法、URL、头）与回执形状（web/organic/results）逐条对上；
 *  - 零结果与非 JSON 都按失败报，不冒充成功；
 *  - 密钥只进请求，绝不出现在失败文案里。
 */
class ProviderSearchClientTest {

    /** 记录最后一次请求的假取网：可以按请求回不同正文，也可以直接抛。 */
    private class FakeFetch(private val responder: (SearchRequest) -> String) {
        var last: SearchRequest? = null
        var calls = 0

        fun send(request: SearchRequest): String {
            last = request
            calls += 1
            return responder(request)
        }
    }

    private fun client(
        cfg: SearchConfig,
        fetch: FakeFetch,
        freeTier: SearchRunner = SearchRunner { SearchOutcome.Ok("x", emptyList()) },
    ) = ProviderSearchClient(config = { cfg }, fetch = { fetch.send(it) }, freeTier = freeTier)

    private fun braveJson(count: Int = 1): String {
        val rows = (1..count).joinToString(",") { i ->
            """{"title":"标题 $i","url":"https://example.com/$i","description":"摘要 $i"}"""
        }
        return """{"web":{"results":[$rows]}}"""
    }

    @Test
    fun braveSendsTokenHeaderAndReadsWebResults() {
        val fetch = FakeFetch { braveJson(2) }
        val outcome = client(
            SearchConfig(SearchProviders.BRAVE, apiKey = "BSA-key", numResults = 2),
            fetch,
        ).search("kotlin")

        assertTrue("该搜到：$outcome", outcome is SearchOutcome.Ok)
        val ok = outcome as SearchOutcome.Ok
        assertEquals(2, ok.results.size)
        assertEquals("https://example.com/1", ok.results[0].url)
        assertEquals("摘要 1", ok.results[0].snippet)

        val request = fetch.last!!
        assertEquals("GET", request.method)
        assertTrue("URL 对：${request.url}", request.url.startsWith("https://api.search.brave.com/res/v1/web/search?q=kotlin"))
        assertEquals("BSA-key", request.headers.first { it.first == "X-Subscription-Token" }.second)
    }

    @Test
    fun serperPostsJsonAndReadsOrganicSnippet() {
        val fetch = FakeFetch {
            """{"organic":[{"title":"甲","link":"https://a.example/1","snippet":"摘要甲"}]}"""
        }
        val outcome = client(SearchConfig(SearchProviders.SERPER, apiKey = "sk-serper"), fetch).search("q")

        assertTrue(outcome is SearchOutcome.Ok)
        val ok = outcome as SearchOutcome.Ok
        assertEquals("https://a.example/1", ok.results[0].url)
        assertEquals("摘要甲", ok.results[0].snippet)
        val request = fetch.last!!
        assertEquals("POST", request.method)
        assertEquals("https://google.serper.dev/search", request.url)
        assertTrue("体里有词：${request.body}", request.body!!.contains("\"q\":\"q\""))
        assertEquals("sk-serper", request.headers.first { it.first == "X-API-KEY" }.second)
    }

    @Test
    fun tavilyPostsKeyInBodyAndReadsContentField() {
        val fetch = FakeFetch {
            """{"results":[{"title":"乙","url":"https://b.example/2","content":"正文乙"}]}"""
        }
        val outcome = client(SearchConfig(SearchProviders.TAVILY, apiKey = "tvly-key"), fetch).search("q")

        assertTrue(outcome is SearchOutcome.Ok)
        assertEquals("正文乙", (outcome as SearchOutcome.Ok).results[0].snippet)
        val request = fetch.last!!
        assertEquals("https://api.tavily.com/search", request.url)
        assertTrue("钥匙进体不进头：${request.headers}", request.headers.none { it.first.contains("KEY", true) })
        assertTrue("体里有钥匙：${request.body}", request.body!!.contains("tvly-key"))
    }

    @Test
    fun searxngUsesConfiguredInstanceAndFallsBackToPublic() {
        val fetch = FakeFetch { """{"results":[{"title":"丙","url":"https://c.example/3","content":"正文丙"}]}""" }
        val custom = client(
            SearchConfig(SearchProviders.SEARXNG, baseUrl = "https://searx.my.example/"),
            fetch,
        ).search("q")
        assertTrue(custom is SearchOutcome.Ok)
        assertTrue(
            "实例地址要用设置那串（尾部斜杠削掉）：${fetch.last!!.url}",
            fetch.last!!.url.startsWith("https://searx.my.example/search?q=q&format=json"),
        )

        val blank = client(SearchConfig(SearchProviders.SEARXNG, baseUrl = "  "), fetch).search("q")
        assertTrue(blank is SearchOutcome.Ok)
        assertTrue(
            "留空回公共实例：${fetch.last!!.url}",
            fetch.last!!.url.startsWith(SearchProviders.DEFAULT_SEARXNG_BASE + "/search"),
        )
    }

    @Test
    fun missingKeyFailsWithoutSendingAnything() {
        val fetch = FakeFetch { braveJson() }
        val outcome = client(SearchConfig(SearchProviders.BRAVE, apiKey = "   "), fetch).search("q")

        assertTrue(outcome is SearchOutcome.Failed)
        val reason = (outcome as SearchOutcome.Failed).reason
        assertTrue("说清缺什么：$reason", reason.contains("需要 API 密钥"))
        assertTrue("说了去哪配：$reason", reason.contains("网页搜索"))
        assertEquals("没配就不许发请求：${fetch.last}", 0, fetch.calls)
    }

    @Test
    fun unknownProviderFallsBackToTheFreeTier() {
        var freeTierCalls = 0
        val freeTier = SearchRunner {
            freeTierCalls += 1
            SearchOutcome.Ok("x", listOf(WebSearchResult("免费档结果", "https://free.example/1", "抓来的")))
        }
        val fetch = FakeFetch { braveJson() }
        val outcome = client(SearchConfig("bing", apiKey = "whatever"), fetch, freeTier).search("q")

        assertTrue(outcome is SearchOutcome.Ok)
        assertEquals(1, freeTierCalls)
        assertEquals(0, fetch.calls)
    }

    @Test
    fun emptyAndGarbageResponsesFailHonestly() {
        val blank = client(SearchConfig(SearchProviders.BRAVE, apiKey = "k"), FakeFetch { "" }).search("q")
        assertTrue(blank is SearchOutcome.Failed)
        assertTrue((blank as SearchOutcome.Failed).reason.contains("空页"))

        val garbage = client(SearchConfig(SearchProviders.BRAVE, apiKey = "k"), FakeFetch { "<html>限流了</html>" }).search("q")
        assertTrue(garbage is SearchOutcome.Failed)
        assertTrue((garbage as SearchOutcome.Failed).reason.contains("没搜到结果"))

        val emptyList = client(
            SearchConfig(SearchProviders.BRAVE, apiKey = "k"),
            FakeFetch { """{"web":{"results":[]}}""" },
        ).search("q")
        assertTrue(emptyList is SearchOutcome.Failed)
    }

    @Test
    fun transportFailureCarriesStatusAndNeverLeaksTheKey() {
        val fetch = FakeFetch { throw IOException("HTTP 429") }
        val outcome = client(SearchConfig(SearchProviders.SERPER, apiKey = "secret-key"), fetch).search("q")

        assertTrue(outcome is SearchOutcome.Failed)
        val reason = (outcome as SearchOutcome.Failed).reason
        assertTrue("带状态码：$reason", reason.contains("HTTP 429"))
        assertTrue("说了怎么办：$reason", reason.contains("换"))
        assertTrue("密钥不许进失败文案：$reason", !reason.contains("secret-key"))
    }

    @Test
    fun numResultsIsClampedAndHonoured() {
        val fetch = FakeFetch { braveJson(10) }
        val outcome = client(SearchConfig(SearchProviders.BRAVE, apiKey = "k", numResults = 99), fetch).search("q")
        assertTrue(outcome is SearchOutcome.Ok)
        assertEquals(
            "上限 10 条：${(outcome as SearchOutcome.Ok).results.size}",
            SearchProviders.MAX_RESULTS,
            outcome.results.size,
        )
        assertTrue(
            "请求里 count 也夹过：${fetch.last!!.url}",
            fetch.last!!.url.contains("count=${SearchProviders.MAX_RESULTS}"),
        )
    }

    @Test
    fun blankQueryFailsBeforeAnyRequest() {
        val fetch = FakeFetch { braveJson() }
        val outcome = client(SearchConfig(SearchProviders.BRAVE, apiKey = "k"), fetch).search("   ")
        assertTrue(outcome is SearchOutcome.Failed)
        assertEquals(0, fetch.calls)
    }
}