package com.hualuo.repotool.ui.state

import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchProviderInfo
import com.hualuo.engine.search.SearchProviders

/**
 * 网页搜索设置舱：选哪家、那家的密钥、自托管实例地址、默认条数。
 *
 * **收口靠界面那条活通道**：取值与存值都由 [AppUiState] 的 `text` / `setText` 进来，
 * 于是本舱天然跟着自动保存与重组走（不另存一份影子状态，那正是「两份事实」的老病）。
 *
 * **执行那一刻现读**：[config] 每次重新拼一份，不缓存 —— 设置页改完下一句对话就生效。
 *
 * 收口细节（认不出的 id 回默认、密钥去空白、条数夹范围）委托给 [searchConfigFrom] 等
 * 纯函数，界面舱与纯 JVM 测试共用一份，不各写一套。
 */
class WebSearchState(
    private val load: (String) -> String,
    private val save: (String, String) -> Unit,
) {

    /** 当前那家（认过 id 的，脏值已回默认）。 */
    val provider: SearchProviderInfo get() = searchProviderFrom(load)

    /** 这家要不要密钥（决定显不显密钥框）。 */
    val needsKey: Boolean get() = provider.needsKey

    /** 这家要不要实例地址（SearXNG 这类自托管引擎才要）。 */
    val usesBaseUrl: Boolean get() = provider.usesBaseUrl

    /** 当前那家的密钥（没配就是空串）。 */
    fun apiKey(): String = load(UiKeys.webSearchKey(provider.id)).trim()

    /** 自托管实例地址（没填就是空串，执行侧会回退到公共实例）。 */
    fun baseUrl(): String = load(UiKeys.WEB_SEARCH_BASE_URL).trim().trimEnd('/')

    /** 默认返回条数（1..10，读不懂回 5）。 */
    fun numResults(): Int = searchNumResultsFrom(load)

    /** 换一家：id 落设置；**不动其它家的密钥**（换一家不丢上一家的钥匙）。 */
    fun setProvider(id: String) {
        save(UiKeys.WEB_SEARCH_PROVIDER, SearchProviders.normalize(id).id)
    }

    fun setApiKey(value: String) = save(UiKeys.webSearchKey(provider.id), value.trim())

    fun setBaseUrl(value: String) = save(UiKeys.WEB_SEARCH_BASE_URL, value.trim().trimEnd('/'))

    fun setNumResults(value: Int) {
        save(
            UiKeys.WEB_SEARCH_NUM_RESULTS,
            value.coerceIn(SearchConfig.MIN_NUM_RESULTS, SearchConfig.MAX_NUM_RESULTS).toString(),
        )
    }

    /** 引擎件要的配置快照（现读现拼，不缓存）。 */
    fun config(): SearchConfig = searchConfigFrom(load)

    /** 界面上那一句「现在会用谁」：认过 id 的名字，不是手输的原始串。 */
    fun providerLabel(): String = provider.name

    /** 界面上那一句「这次能不能搜」：缺什么当场说清，不让人白搜一次才发现没钥匙。 */
    fun statusLine(): String {
        val info = provider
        return if (info.needsKey && apiKey().isEmpty()) {
            "${info.name}：还没填密钥（去下面那格填上）"
        } else {
            "${info.name}：每搜最多 ${numResults()} 条"
        }
    }
}