package com.hualuo.repotool.ui.state

import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchProviderInfo
import com.hualuo.engine.search.SearchProviders

/**
 * 网页搜索设置舱：选哪家、那家的密钥、SearXNG 实例地址、默认条数。
 *
 * 与 [ObserveUiState] 同一路数：值一律落设置文件（键名见 [UiKeys]），
 * **执行那一刻现读**（[config] 每次都重新读一遍底层存储，不缓存），所以设置页改完
 * 下一句对话就生效，不用重启。界面重算靠读底层（[UiPersistence.load] 走的是同一份
 * SettingsStore 快照），不需要这里再存一份影子状态。
 *
 * 读侧的收口规矩（都在这里做，别散在界面里）：
 *  - 认不出的提供商 id 回默认那家（[SearchProviders.normalize]）：设置文件是手可编辑的，
 *    手输错字不许把网页工具打死；
 *  - 密钥去空白，空串就是「没配」：界面据此显出提示，不假装有钥匙；
 *  - 条数读不懂回 5、越界夹到 1..10。
 */
class WebSearchState(
    private val persist: UiPersistence,
    /** 改值后推一格修订号：根界面的自动保存看护只盯这一个数，界面增删字段不用改看护点。 */
    private val bumpRevision: () -> Unit = {},
) {

    /** 当前那家（认过 id 的，脏值已回默认）。 */
    val provider: SearchProviderInfo
        get() = SearchProviders.normalize(persist.load(UiKeys.WEB_SEARCH_PROVIDER))

    /** 这家要不要密钥（决定显不显密钥框）。 */
    val needsKey: Boolean get() = provider.needsKey

    /** 这家要不要实例地址（SearXNG 这类自托管引擎才要）。 */
    val usesBaseUrl: Boolean get() = provider.usesBaseUrl

    /** 当前那家的密钥（没配就是空串）。 */
    fun apiKey(): String = persist.load(UiKeys.webSearchKey(provider.id)).orEmpty()

    /** 自托管实例地址（没填就是空串，执行侧会回退到公共实例）。 */
    fun baseUrl(): String = persist.load(UiKeys.WEB_SEARCH_BASE_URL).orEmpty()

    /** 默认返回条数（1..10，读不懂回 5）。 */
    fun numResults(): Int = readWebSearchNumResults(persist)

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
    fun config(): SearchConfig = readWebSearchConfig(persist)

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

    private fun save(key: String, value: String) {
        if (persist.load(key) == value) return
        persist.save(key, value)
        bumpRevision()
    }
}