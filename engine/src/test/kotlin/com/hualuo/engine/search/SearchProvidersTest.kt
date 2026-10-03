package com.hualuo.engine.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 事实表与配置值的钉子：五家不多不少、id 与名字对得上、脏 id 回默认、条数两端夹住。
 *
 * 这层是界面与执行共用的唯一事实源：它漂了，界面显示的和真发出去的就会分家
 * （旧仓就是界面写一份 id 列表、执行写另一份）。
 */
class SearchProvidersTest {

    @Test
    fun tableHasExactlyTheFiveProvidersInOrder() {
        assertEquals(
            listOf("brave", "serper", "tavily", "searxng", "duckduckgo"),
            SearchProviders.ALL.map { it.id },
        )
        assertEquals("Brave", SearchProviders.byId("brave")?.name)
        assertEquals("SearXNG", SearchProviders.byId("searxng")?.name)
    }

    @Test
    fun onlyDuckDuckGoAndSearxngNeedNoKey() {
        assertEquals(
            listOf("brave", "serper", "tavily"),
            SearchProviders.ALL.filter { it.needsKey }.map { it.id },
        )
        assertTrue("只有 SearXNG 要实例地址", SearchProviders.ALL.filter { it.usesBaseUrl }.map { it.id } == listOf("searxng"))
    }

    @Test
    fun unknownOrBlankIdFallsBackToDefault() {
        assertEquals(SearchProviders.DEFAULT_ID, SearchProviders.normalize("bing").id)
        assertEquals(SearchProviders.DEFAULT_ID, SearchProviders.normalize(null).id)
        assertEquals(SearchProviders.DEFAULT_ID, SearchProviders.normalize("   ").id)
        assertEquals(SearchProviders.DEFAULT_ID, SearchProviders.normalize("BRAVE ")?.let { "brave" })
        assertNull(SearchProviders.byId("bing"))
        assertEquals("DuckDuckGo", SearchProviders.labelOf("不存在"))
    }

    @Test
    fun configCleansAndClamps() {
        val cfg = SearchConfig(
            providerId = " brave ",
            apiKey = "  key  ",
            baseUrl = "https://searx.my.example//  ",
            numResults = 999,
        )
        assertEquals("brave", cfg.provider.id)
        assertEquals("key", cfg.cleanedKey)
        assertEquals("https://searx.my.example", cfg.cleanedBaseUrl)
        assertEquals(SearchProviders.MAX_RESULTS, cfg.cappedNumResults)
        assertEquals(0, SearchConfig(numResults = 0).cappedNumResults.coerceAtLeast(SearchProviders.MIN_RESULTS))
        assertEquals(SearchProviders.MIN_RESULTS, SearchConfig(numResults = -5).cappedNumResults)
    }

    @Test
    fun blankBaseUrlFallsBackToPublicInstance() {
        assertEquals(
            SearchProviders.DEFAULT_SEARXNG_BASE,
            SearchConfig(providerId = "searxng", baseUrl = "  ").effectiveBaseUrl,
        )
    }

    @Test
    fun defaultsAreTheFreeTier() {
        val cfg = SearchConfig()
        assertEquals("duckduckgo", cfg.provider.id)
        assertFalse("默认那家不该要密钥", cfg.provider.needsKey)
        assertEquals(5, cfg.cappedNumResults)
        assertTrue("默认没配任何东西", cfg.cleanedKey.isEmpty() && cfg.cleanedBaseUrl.isEmpty())
    }
}