package com.hualuo.engine.search

/**
 * 搜索动作的最小契约：吃一个词，吐 [SearchOutcome]。
 *
 * 为什么抽这一层：工具族（web_search）与工具页都只该认「能搜一把」这件事，
 * 不该知道底下是 DuckDuckGo 抓 HTML 还是 Brave 调 JSON API。同一件事一份实现：
 * 免费档走 [WebSearchClient]，其余四家走 [ProviderSearchClient]。
 */
fun interface SearchRunner {
    fun search(query: String): SearchOutcome
}