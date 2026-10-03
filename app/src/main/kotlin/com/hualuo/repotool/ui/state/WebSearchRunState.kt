package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.search.ProviderSearchClient
import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchOutcome
import com.hualuo.engine.search.WebSearchResult

/**
 * 工具页那张「网页搜索」卡的瞬时态：搜索词、忙灯、结果、收场话。
 *
 * 独立成件的原因有两条：
 *  - [AppUiState] 已经贴着红线三（单文件不许过 999 行），网页搜索这几格不该再往里塞；
 *  - 它本来就是**瞬时**的（跟另外五个工具开关不同：搜索词与结果不该落盘，
 *    关掉应用重开就该从空开始）。
 *
 * 用哪家由外部两个 lambda 现给（[configProvider]、[providerLabel]），本件不读设置文件，
 * 于是「设置里换一家」这件事不需要改这里任何一行。
 *
 * 家规照旧：大会计 IO 进后台线程；成功的旧结果不偷偷留着顶数——失败就明示失败。
 */
class WebSearchRunState(
    private val configProvider: () -> SearchConfig,
    private val providerLabel: () -> String,
    private val toast: (String) -> Unit,
) {

    /** 搜索框里的词。 */
    var query by mutableStateOf("")

    /** 正在搜：按钮与提示行都看它。 */
    var busy by mutableStateOf(false)
        private set

    /** 最近一次的搜索结果（真数据，引擎件清净过）。 */
    var results by mutableStateOf(emptyList<WebSearchResult>())
        private set

    /** 最近一次搜索的收场话（成功报条数与走了哪家，失败给理由）；null = 还没搜过。 */
    var note by mutableStateOf<String?>(null)
        private set

    /**
     * 搜一把：真网络、真结果、失败出声不冒充。
     *
     * 空词当场拒（不发网络）；已经在搜也不重复发车（按钮禁得住，这里再兜一道）。
     */
    fun run() {
        if (busy) return
        val q = query.trim()
        if (q.isEmpty()) {
            toast("先在框里写要搜什么")
            return
        }
        busy = true
        note = null
        Thread({
            val outcome = ProviderSearchClient(config = configProvider).search(q)
            busy = false
            when (outcome) {
                is SearchOutcome.Ok -> {
                    results = outcome.results
                    note = "搜到 ${outcome.results.size} 条（${outcome.query}，走 ${providerLabel()}）"
                }
                is SearchOutcome.Failed -> {
                    results = emptyList()
                    note = outcome.reason
                }
            }
        }, "hualuo-web-search").start()
    }
}