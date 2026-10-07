package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.api.OpenAiCompatClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 报告三件套（报告页实装刀）的瞬时态：数据分析 / 汇报文档 / PPT 大纲。
 *
 * 与 [WebSearchRunState] 同一套纪律：
 *  - 瞬时不落盘（关掉重开从空开始——报告是即用即弃的产物，真要留就从结果区复制全文）；
 *  - 大会计 IO 全进后台线程，busy 灯控重入；
 *  - 失败明示失败，不留旧结果顶数。
 *
 * 三件共用一条真 AI 通路：当前模型 + 预置系统指令 + 一次性 chat（不走会话库，
 * 不占会话列表——报告是工具产物不是对话）。模型没配好就在 [ensureModel] 拦下并出声。
 */
class ReportRunState(
    private val toast: (String) -> Unit,
    /** 当前选的模型（报告用聊天页同款模型；没配好这里解不出 profile 就出声）。 */
    private val currentModel: () -> String,
    /** 解析当前模型的会话（配置 + 协议）；null = 没配好。 */
    private val sessionFor: (String) -> com.hualuo.engine.api.ProviderSession?,
) {

    /** 一份分析结果：指标行 + 折线数据 + 结论 + 建议。 */
    data class Analysis(
        val metrics: List<Triple<String, String, String>>,
        val seriesName: String,
        val points: List<Pair<Double, Double>>,
        val conclusion: String,
        val advice: String,
    )

    /** 一页幻灯片：标题 + 要点。 */
    data class Slide(val heading: String, val bullets: List<String>)

    /** 一份文档：标题 + 原文全文（渲染按行解析标题/列表/正文）。 */
    data class Document(val title: String, val body: String)

    // ---- 数据分析 ----
    var analysisInput by mutableStateOf("")
    var analysisBusy by mutableStateOf(false)
        private set
    var analysis by mutableStateOf<Analysis?>(null)
        private set
    var analysisNote by mutableStateOf<String?>(null)
        private set

    // ---- 汇报文档 ----
    var docInput by mutableStateOf("")
    var docBusy by mutableStateOf(false)
        private set
    var document by mutableStateOf<Document?>(null)
        private set
    var docNote by mutableStateOf<String?>(null)
        private set

    // ---- PPT 大纲 ----
    var pptInput by mutableStateOf("")
    var pptBusy by mutableStateOf(false)
        private set
    var slides by mutableStateOf<List<Slide>>(emptyList())
        private set
    var pptTitle by mutableStateOf("")
        private set
    var pptNote by mutableStateOf<String?>(null)
        private set

    /** PPT 翻页指针（页卡上一页/下一页用）。 */
    var slideIndex by mutableStateOf(0)

    /** 跑一次数据分析。 */
    fun runAnalysis() { runOnce(analysisBusy, { analysisBusy = true }, { analysisBusy = false }, ::blockAnalysis) }

    /** 生成一份汇报文档。 */
    fun runDocument() { runOnce(docBusy, { docBusy = true }, { docBusy = false }, ::blockDoc) }

    /** 生成一份 PPT 大纲。 */
    fun runPpt() { runOnce(pptBusy, { pptBusy = true }, { pptBusy = false }, ::blockPpt) }

    /** 后台线程跑一轮生成（三件共用骨架：拦空输入、拦没配模型、拦重入）。 */
    private inline fun runOnce(
        busy: Boolean, noinline setBusy: (Boolean) -> Unit, noinline unsetBusy: (Boolean) -> Unit,
        crossinline block: () -> Unit,
    ) {
        if (busy) return
        setBusy(true)
        Thread({ block(); unsetBusy(false) }, "hualuo-report").start()
    }

    /** 三件共用的挡板：输入为空/模型没配好都出声并返回 null。 */
    private fun ensureModel(input: String): Pair<com.hualuo.engine.api.ProviderSession, String>? {
        val text = input.trim()
        if (text.isEmpty()) {
            toast("先在框里写要分析或生成的内容")
            return null
        }
        val model = currentModel()
        val session = sessionFor(model)
        if (session == null) {
            toast("模型还没配好（设置里配一家提供商和密钥），报告生成用不了")
            return null
        }
        return session to text
    }

    /** 一次性生成：系统指令 + 用户输入，流式收进 StringBuilder，返回全文；失败返回 null。 */
    private fun generateOnce(systemPrompt: String, userText: String): String? {
        val session = sessionFor(currentModel()) ?: return null
        val history = listOf(
            com.hualuo.engine.api.ChatTurn("system", systemPrompt),
            com.hualuo.engine.api.ChatTurn("user", userText),
        )
        val sb = StringBuilder()
        val client = OpenAiCompatClient(
            transport = com.hualuo.engine.api.UrlConnTransport(),
            slot = com.hualuo.engine.generation.GenerationSlot(),
            policy = com.hualuo.engine.http.RetryPolicy(),
            watchdog = com.hualuo.engine.generation.IdleWatchdog(com.hualuo.engine.generation.IdleWatchdog.GENERATION_IDLE_MS),
        )
        val error = client.chat(session.profile, history, 0.3, null, null) { chunk ->
            sb.append(chunk)
        }
        return if (error == null) sb.toString() else null
    }

    // ---------- 数据分析 ----------

    private fun blockAnalysis() {
        val pair = ensureModel(analysisInput)
        if (pair == null) { analysisNote = "没跑成（输入或模型没就绪）"; return }
        val raw = generateOnce(ANALYSIS_SYSTEM, pair.second)
        if (raw == null) { analysis = null; analysisNote = "生成失败（网络或模型出错，原文失败不打折）"; return }
        val parsed = parseAnalysis(raw)
        if (parsed == null) { analysis = null; analysisNote = "模型没按约定回 JSON（重试一次通常就好）"; return }
        analysis = parsed
        analysisNote = "已生成：指标 ${parsed.metrics.size} 条 · 数据点 ${parsed.points.size} 个（模型 ${currentModel()}）"
    }

    /** 从模型回文里抠 JSON（容错：前后可能有废话或代码围栏）。 */
    private fun parseAnalysis(raw: String): Analysis? = runCatching {
        val json = extractJson(raw) ?: return null
        val o = JSONObject(json)
        val metrics = o.optJSONArray("metrics")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(m.optString("label"), m.optString("value"), m.optString("trend"))
            }
        }.orEmpty()
        val series = o.optJSONObject("series")
        val points = series?.optJSONArray("points")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                Pair(p.optDouble("x", Double.NaN), p.optDouble("y", 0.0))
            }
        }.orEmpty().filter { !it.first.isNaN() }
        Analysis(
            metrics = metrics,
            seriesName = series?.optString("name").orEmpty().ifBlank { "序列" },
            points = points,
            conclusion = o.optString("conclusion"),
            advice = o.optString("advice"),
        )
    }.getOrNull()

    // ---------- 汇报文档 ----------

    private fun blockDoc() {
        val pair = ensureModel(docInput)
        if (pair == null) { docNote = "没跑成（输入或模型没就绪）"; return }
        val raw = generateOnce(DOC_SYSTEM, pair.second)
        if (raw == null) { document = null; docNote = "生成失败（网络或模型出错）"; return }
        val title = raw.lineSequence().firstOrNull { it.trimStart().startsWith("#") }?.trimStart('#')?.trim()
            ?: pair.second.take(24)
        document = Document(title, raw.trim())
        docNote = "已生成：约 ${raw.length} 字（模型 ${currentModel()}）"
    }

    // ---------- PPT 大纲 ----------

    private fun blockPpt() {
        val pair = ensureModel(pptInput)
        if (pair == null) { pptNote = "没跑成（输入或模型没就绪）"; return }
        val raw = generateOnce(PPT_SYSTEM, pair.second)
        if (raw == null) { slides = emptyList(); pptNote = "生成失败（网络或模型出错）"; return }
        val parsed = parseSlides(raw)
        if (parsed == null) { slides = emptyList(); pptNote = "模型没按约定回 JSON（重试一次通常就好）"; return }
        pptTitle = parsed.first
        slides = parsed.second
        slideIndex = 0
        pptNote = "已生成：${parsed.second.size} 页（模型 ${currentModel()}）"
    }

    private fun parseSlides(raw: String): Pair<String, List<Slide>>? = runCatching {
        val json = extractJson(raw) ?: return null
        val o = JSONObject(json)
        val arr = o.optJSONArray("pages") ?: return null
        val pages = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            Slide(
                heading = p.optString("heading").ifBlank { "第 ${i + 1} 页" },
                bullets = p.optJSONArray("bullets")?.let { b ->
                    (0 until b.length()).mapNotNull { j -> b.optString(j).takeIf { it.isNotBlank() } }
                }.orEmpty(),
            )
        }
        if (pages.isEmpty()) null else o.optString("title").ifBlank { "演示文稿" } to pages
    }.getOrNull()

    /** 抠出第一段平衡的 JSON 对象文本（从第一个 { 到配对的 }；容忍围栏与前后废话）。 */
    private fun extractJson(raw: String): String? {
        val cleaned = raw.replace("```json", "").replace("```", "")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth += 1
                !inString && c == '}' -> {
                    depth -= 1
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        return null
    }

    companion object {
        /** 数据分析系统指令：只要 JSON，别的一律不说。 */
        private val ANALYSIS_SYSTEM = """
            你是数据分析引擎。用户给你一段原始数据或现象描述，你输出严格 JSON（别加任何解释或代码围栏）：
            {"metrics":[{"label":"指标名","value":"值","trend":"涨/跌/平"}],
             "series":{"name":"系列名","points":[{"x":1,"y":12.3}]},
             "conclusion":"两三句结论",
             "advice":"一句可执行建议"}
            规则：x 用序号或时间序数字；points 至少 4 个、最多 24 个；数据不足就如实写「样本不足」进 conclusion，不许编数。
        """.trimIndent().replace('\n', ' ')

        /** 汇报文档系统指令：Markdown 结构化文档。 */
        private val DOC_SYSTEM = """
            你是公文写手。用户给你主题与素材，你输出一份结构化汇报文档（Markdown）：
            一级标题一份；每节用 ##；重点条目用 - 列表；结尾给一行「建议」段。
            长度 800 到 3000 字；不许编造没给到的事实，素材不足的地方明写「待补充」。
        """.trimIndent().replace('\n', ' ')

        /** PPT 大纲系统指令：只要 JSON。 */
        private val PPT_SYSTEM = """
            你是演示文稿策划。用户给你主题，你输出严格 JSON（别加任何解释或代码围栏）：
            {"title":"文稿名","pages":[{"heading":"页标题","bullets":["要点一","要点二","要点三"]}]}
            规则：6 到 10 页；每页 2 到 4 个要点；每要点不超过 20 字；不许编造数据。
        """.trimIndent().replace('\n', ' ')
    }
}
