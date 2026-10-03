package com.hualuo.engine.search

/**
 * 搜索提供商的静态事实表（五家，与旧 Agora 内置那五家逐字对齐）。
 *
 * 这张表是**唯一事实源**：设置页摆哪几行、工具页标题写谁的名字、引擎发请求时选哪条路、
 * 旧备份兑换时认哪几个 id，全从这里读，不允许别处再抄一份 id 列表
 * （旧仓那种「界面写一份、执行写一份」的漂移就是从这儿开始的）。
 *
 * 三条纪律：
 *  - **认不出的 id 一律回默认那家**（设置文件是手可编辑的文本，脏值不许把网页工具打死）；
 *  - 要不要密钥、要不要实例地址是**静态事实**，界面按它决定显不显示那一格；
 *  - 免费档（DuckDuckGo）不是 API，是抓 HTML：它走 [WebSearchClient] 那条老路，
 *    不进 [ProviderSearchClient] 的 JSON 分支。
 */
data class SearchProviderInfo(
    val id: String,
    val name: String,
    val desc: String,
    /** true = 没密钥就一定搜不了（界面必须显出密钥框，并把失败话说清）。 */
    val needsKey: Boolean,
    /** true = 要用户填自己的实例地址（SearXNG 这类自托管引擎）。 */
    val usesBaseUrl: Boolean,
)

object SearchProviders {

    const val BRAVE = "brave"
    const val SERPER = "serper"
    const val TAVILY = "tavily"
    const val SEARXNG = "searxng"
    const val DUCKDUCKGO = "duckduckgo"

    /** 默认那家：免密钥、零配置（旧仓同款默认，新装机不变手就能搜）。 */
    const val DEFAULT_ID = DUCKDUCKGO

    /** SearXNG 没填地址时走公共实例（旧仓同一条默认；自建实例更稳）。 */
    const val DEFAULT_SEARXNG_BASE = "https://searx.be"

    /** 返回条数的下界与上界（设置里的滑条、模型给的 num_results，两头都按它夹）。 */
    const val MIN_RESULTS = 1
    const val MAX_RESULTS = 10

    private val TABLE: List<SearchProviderInfo> = listOf(
        SearchProviderInfo(
            id = BRAVE,
            name = "Brave",
            desc = "注重隐私的搜索 API。提供免费套餐。",
            needsKey = true,
            usesBaseUrl = false,
        ),
        SearchProviderInfo(
            id = SERPER,
            name = "Serper",
            desc = "快速 Google 搜索 API。每月 2,500 次免费查询。",
            needsKey = true,
            usesBaseUrl = false,
        ),
        SearchProviderInfo(
            id = TAVILY,
            name = "Tavily",
            desc = "面向 AI 优化的搜索 API。专为 LLM 代理构建。",
            needsKey = true,
            usesBaseUrl = false,
        ),
        SearchProviderInfo(
            id = SEARXNG,
            name = "SearXNG",
            desc = "自托管元搜索引擎。建议使用自己的实例。",
            needsKey = false,
            usesBaseUrl = true,
        ),
        SearchProviderInfo(
            id = DUCKDUCKGO,
            name = "DuckDuckGo",
            desc = "免费，无需 API Key。抓取 DuckDuckGo 的 HTML 端点。可能不稳定并触发反爬保护。",
            needsKey = false,
            usesBaseUrl = false,
        ),
    )

    val ALL: List<SearchProviderInfo> get() = TABLE

    /** 按 id 查一家；查不到（null、空串、手输错字）返回 null，由 [normalize] 兜底。 */
    fun byId(id: String?): SearchProviderInfo? =
        TABLE.firstOrNull { it.id == id?.trim()?.lowercase() }

    /** 认不出的 id 回默认那家，绝不抛（设置文件是用户手可编辑的文本）。 */
    fun normalize(id: String?): SearchProviderInfo = byId(id) ?: requireDefault()

    private fun requireDefault(): SearchProviderInfo =
        TABLE.first { it.id == DEFAULT_ID }

    fun labelOf(id: String?): String = normalize(id).name
}

/**
 * 一次搜索的完整配置（设置页那几格拼起来的样子）。
 *
 * 全是**事实**，不含判断：清洗与夹范围放在派生属性里（[cleanedKey]、[cappedNumResults]），
 * 这样设置层、工具页、工具族读到的永远是同一份干净值。
 */
data class SearchConfig(
    val providerId: String = SearchProviders.DEFAULT_ID,
    val apiKey: String = "",
    val baseUrl: String = "",
    val numResults: Int = DEFAULT_NUM_RESULTS,
) {

    /** 认完 id 的那家（脏值已回默认）。 */
    val provider: SearchProviderInfo get() = SearchProviders.normalize(providerId)

    /** 密钥去空白；空串就是「没配」，界面据此提示。 */
    val cleanedKey: String get() = apiKey.trim()

    /** 实例地址去空白与尾部斜杠。 */
    val cleanedBaseUrl: String get() = baseUrl.trim().trimEnd('/')

    /** 自托管引擎实际要用的地址：没填就走公共实例。 */
    val effectiveBaseUrl: String
        get() = cleanedBaseUrl.ifEmpty { SearchProviders.DEFAULT_SEARXNG_BASE }

    /** 条数夹在范围内（模型给的 num_results 也会再夹一次，两头都收口）。 */
    val cappedNumResults: Int
        get() = numResults.coerceIn(SearchProviders.MIN_RESULTS, SearchProviders.MAX_RESULTS)

    companion object {
        const val DEFAULT_NUM_RESULTS = 5
    }
}