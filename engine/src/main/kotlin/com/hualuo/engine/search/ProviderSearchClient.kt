package com.hualuo.engine.search

import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 五家提供商的发请求与收结果（键档三家 + 自托管一家 + 免费抓取一家）。
 *
 * 移植口径（照旧仓 `WebSearchToolProvider` 与 `DuckDuckGoScraper` 逐条对齐，只搬行为不搬依赖）：
 *  - **密钥只进请求头/请求体**，绝不进任何结果文本与报错（GitHub 那条老规矩一样）；
 *  - **失败要能行动**：没配密钥就说「去设置里配」，实例地址空就说走公共实例，
 *    非 2xx 带状态码，形状不认识就说形状变了——绝不拿空列表冒充「搜完没有」；
 *  - **取网动作经 [fetch] 缝隙注入**：纯 JVM 测试喂假响应，绝不碰真网；
 *  - **响应有界**（[FETCH_CHAR_CAP] 字符封顶），一家回一兆 HTML 也不许撑爆内存；
 *  - 免费档（DuckDuckGo）不是 API：**原样交给 [WebSearchClient] 抓 HTML**，
 *    这里不重写它的分页与反爬识别（同一件事只留一份实现）。
 */
class ProviderSearchClient(
    /** 现读设置：改完设置下一句就生效，不缓存（老规矩：执行那一刻读现场）。 */
    private val config: () -> SearchConfig,
    private val fetch: (SearchRequest) -> String = { request -> defaultFetch(request) },
    private val freeTier: WebSearchClient = WebSearchClient(),
) {

    /**
     * 搜一把。任何收场都不抛异常——失败在 [SearchOutcome.Failed] 里说清。
     *
     * 关键词空、配置不认、密钥没配、连不上、返回形状变了，各给各的话。
     */
    fun search(query: String): SearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchOutcome.Failed("没给搜索词")
        val cfg = config()
        val info = cfg.provider
        if (info.needsKey && cfg.cleanedKey.isEmpty()) {
            return SearchOutcome.Failed(
                "${info.name} 需要 API 密钥：去设置「网页搜索」把密钥填上再搜（这格留空就一定搜不了）",
            )
        }
        if (info.id == SearchProviders.DUCKDUCKGO) return freeTier.search(trimmed)
        val request = try {
            buildRequest(info, cfg, trimmed)
        } catch (e: Exception) {
            return SearchOutcome.Failed("搜索请求拼不出来（${e.message ?: "地址或参数有问题"}）：核对实例地址")
        }
        val body = try {
            fetch(request)
        } catch (e: Exception) {
            return SearchOutcome.Failed(
                "${info.name} 连不上（${e.message ?: "网络出错了"}）：核对密钥与实例地址，或换个提供商",
            )
        }
        if (body.isBlank()) {
            return SearchOutcome.Failed("${info.name} 回了空页（多半被限流）：稍后再试或换个提供商")
        }
        val results = parse(info.id, body)
        if (results.isEmpty()) {
            return SearchOutcome.Failed(
                "${info.name} 这次没搜到结果（页面形状变了或额度用完）：不冒充成功，换个词或稍后再试",
            )
        }
        return SearchOutcome.Ok(trimmed, results.take(cfg.cappedNumResults))
    }

    // ---------- 请求怎么拼 ----------

    private fun buildRequest(info: SearchProviderInfo, cfg: SearchConfig, query: String): SearchRequest {
        val q = URLEncoder.encode(query, "UTF-8")
        val key = cfg.cleanedKey
        return when (info.id) {
            SearchProviders.BRAVE -> SearchRequest(
                method = "GET",
                url = "https://api.search.brave.com/res/v1/web/search?q=$q&count=${cfg.cappedNumResults}",
                headers = listOf(
                    "Accept" to "application/json",
                    "X-Subscription-Token" to key,
                ),
            )
            SearchProviders.SERPER -> SearchRequest(
                method = "POST",
                url = "https://google.serper.dev/search",
                headers = listOf("Content-Type" to "application/json", "X-API-KEY" to key),
                body = """{"q":"$query","num":${cfg.cappedNumResults}}""",
            )
            SearchProviders.TAVILY -> SearchRequest(
                method = "POST",
                url = "https://api.tavily.com/search",
                headers = listOf("Content-Type" to "application/json"),
                body = """{"api_key":"$key","query":"$query","max_results":${cfg.cappedNumResults},""" +
                    """"search_depth":"advanced","include_answer":false}""",
            )
            SearchProviders.SEARXNG -> SearchRequest(
                method = "GET",
                // 自托管实例的 JSON 端点；实例地址来自设置，空值已在 cfg.effectiveBaseUrl 里回退到公共实例
                url = "${cfg.effectiveBaseUrl}/search?q=$q&format=json",
                headers = listOf("Accept" to "application/json", "User-Agent" to BROWSER_UA),
            )
            else -> throw IllegalStateException("未知提供商 ${info.id}：请求不该走到这里")
        }
    }

    // ---------- 回执怎么拆 ----------

    /**
     * 四家各一个形状，认不出就返回空列表（上层会把它翻成一句能行动的失败，不当成功）：
     *  - Brave：`web.results[]`，字段 title/url/description；
     *  - Serper：`organic[]`，字段 title/link/snippet；
     *  - Tavily：`results[]`，字段 title/url/content；
     *  - SearXNG：`results[]`，字段 title/url/content。
     *
     * 用 kotlinx 的动态读（与全仓同一条路：engine 不引序列化编译器插件）。
     */
    private fun parse(providerId: String, body: String): List<WebSearchResult> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val rows: JsonArray = when (providerId) {
            SearchProviders.BRAVE -> root.obj("web")?.arr("results") ?: return emptyList()
            SearchProviders.SERPER -> root.arr("organic") ?: return emptyList()
            SearchProviders.TAVILY, SearchProviders.SEARXNG -> root.arr("results") ?: return emptyList()
            else -> return emptyList()
        }
        val out = ArrayList<WebSearchResult>(rows.size)
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val title = row.str("title")
            val url = row.str("link").ifBlank { row.str("url") }
            val snippet = row.str("snippet").ifBlank {
                row.str("content").ifBlank { row.str("description") }
            }
            if (title.isBlank() || url.isBlank()) continue
            out.add(WebSearchResult(title, url, snippet))
        }
        return out
    }

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    companion object {

        /** 抓取端的 UA：默认 JDK UA 会被不少搜索端点拒。 */
        const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

        /** 响应字符封顶（同 GitHub 那条纪律）：一家回一兆 HTML 也不许撑爆内存。 */
        const val FETCH_CHAR_CAP = 512_000

        /**
         * 真网取一次：15 秒超时、非 2xx 抛 IOException 带状态码（由 [search] 翻成人话）。
         * POST 的请求体一次性给全（JSON 文本，量级很小，不构成红线二的整文件读入）。
         */
        fun defaultFetch(request: SearchRequest): String {
            val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = request.method
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            try {
                request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                request.body?.let { text ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val reader = InputStreamReader(conn.inputStream, Charsets.UTF_8)
                val out = StringBuilder()
                val buf = CharArray(8 * 1024)
                while (out.length < FETCH_CHAR_CAP) {
                    val n = reader.read(buf, 0, minOf(buf.size, FETCH_CHAR_CAP - out.length))
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
                return out.toString()
            } finally {
                conn.disconnect()
            }
        }

        /** 只读一个 JSON 字段的小工具（测试与解析共用，避免各处重复 cast）。 */
        internal fun jsonField(text: String, key: String): String? =
            ((runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get(key)
                as? JsonPrimitive)?.contentOrNull
    }
}