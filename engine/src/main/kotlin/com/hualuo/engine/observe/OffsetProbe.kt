package com.hualuo.engine.observe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * IL2CPP 偏移漂移试探器（IL2CPP 立项第一块引擎件，字段卡冷启动的探针）。
 *
 * 背景（2026-10-09 立项，外部生态实证见 sandbox/il2cpp-study/）：
 * 游戏版本更新后，类字段偏移会漂移。Il2CppDumper 的做法是按 metadata 版本号
 * 切换结构布局并做「候选试探」（invokerPointersCount 超 0x50000 就退一版重读——
 * Il2Cpp/Il2Cpp.cs AutoPlusInit）；hlpatch 走运行时按名反射
 * （il2cpp_class_get_fields -> FieldInfo.offset），名字在偏移就在。
 * 但「按名反射」救不了两种场景：
 *   a) 字段改名/新增/删除——旧名字找不到，新名字没记忆；
 *   b) 拿旧版本 dump 记忆（字段卡）对账新版本——需要一个「差多少」的判决器。
 *
 * 本件做 b，并给 a 提供候选集：
 *   1. [diff] —— 旧字段表 vs 新字段表（都来自观测桥 /fields/<class> 的
 *      {"total":N,"fields":[{"name","offset","class","type_enum","type_name"}]}），
 *      输出对账差账：每字段 保留/平移/改名候选/新增/消失。
 *   2. [candidates] —— 对「消失字段」按 IL2CPP 对齐规则穷举候选偏移：
 *      Android arm64 引用/long=8 字节自然对齐，int/float=4，short=2，byte=1；
 *      实例字段头（Il2CppObject 头 16 字节：klass+monitor 各 8）之外按声明序排。
 *      旧偏移 + 头部/邻居插入的字节数 = 新候选；候选集 = 旧偏移沿对齐格点
 *      向后穷举（上限可调，默认 ±8 格 = ±64 字节，覆盖一次字段插入的常见量）。
 *      「8 或 16」的出处：arm64 上一次引用字段插入正好推 8，一个对象头变化推 16
 *      （Il2CppDumper GetFieldOffsetFromIndex 里 isValueType 的 -8/-16 同款事实）。
 *   3. [verify] —— 候选命中判据：拿 read_obscured_int_at 语义的只读口
 *      （观测桥 /il2cpp/read_mem?addr=&size=）读候选偏移处的字节，
 *      与「上版本同字段已知值/值域」比对；值域由调用方给（字段卡记着的范围）。
 *
 * 纪律：
 *  - 纯逻辑、无 IO（试探请求由 UmaTool 的既有 call() 走，本类只算账）；
 *  - 只登记事实不下结论——差账里「改名候选」是名字相似度 ≥ 阈值的**候选**，
 *    不是断言；判决权在调用方（预言-对账流水：AI 提预言，MasterDB 对账）；
 *  - dump.cs 定位=参照物非判据（用户红线：最终事实以 hook 实测+MasterDB 对账为准）。
 */
object OffsetProbe {

    /** 一张字段卡：名字 + 偏移 + 类型（旧版本记忆或新版本实测）。 */
    data class FieldCard(
        val name: String,
        val offset: Int,
        val typeName: String,
    )

    /** 字段差账的一行。 */
    sealed interface DiffRow {
        val name: String

        /** 两边都在且偏移相等：什么都不用做。 */
        data class Kept(override val name: String, val offset: Int, val typeName: String) : DiffRow

        /** 两边都在但偏移变了：最常见的漂移形态（前面插了字段）。 */
        data class Shifted(
            override val name: String,
            val oldOffset: Int,
            val newOffset: Int,
            val delta: Int,
            val typeName: String,
        ) : DiffRow

        /** 旧表消失、新表里有名字长得像的：候选，不是断言。 */
        data class Renamed(
            override val name: String,
            val newName: String,
            val oldOffset: Int,
            val newOffset: Int,
            val similarity: Double,
        ) : DiffRow

        /** 新表有、旧表没有：新版本加的字段（或改名后的残影，与 Renamed 对照看）。 */
        data class Added(override val name: String, val offset: Int, val typeName: String) : DiffRow

        /** 旧表有、新表没有且无相似名：真消失（删字段/搬去父类）。 */
        data class Gone(override val name: String, val oldOffset: Int) : DiffRow
    }

    data class DiffResult(
        val kept: List<DiffRow.Kept>,
        val shifted: List<DiffRow.Shifted>,
        val renamed: List<DiffRow.Renamed>,
        val added: List<DiffRow.Added>,
        val gone: List<DiffRow.Gone>,
    ) {
        val total: Int get() = kept.size + shifted.size + renamed.size + added.size + gone.size

        /** 一句话账面（人话，给 AI 板用）。 */
        fun summary(): String = buildString {
            append("字段对账：保留 ${kept.size}、平移 ${shifted.size}、改名候选 ${renamed.size}、新增 ${added.size}、消失 ${gone.size}")
            val maxDelta = shifted.maxOfOrNull { it.delta }
            if (maxDelta != null) append("；最大平移 ${maxDelta} 字节")
        }
    }

    /**
     * 旧表对新表出差账。
     *
     * @param similarityFloor 改名判定阈值 0-1（默认 0.72：编辑距离相似度，
     *   经验值——名字前后缀变化如 RemainTurnNum->RemainTurnCount 命中，
     *   完全无关名字不误报）。阈值是候选门不是判决门。
     */
    fun diff(old: List<FieldCard>, new: List<FieldCard>, similarityFloor: Double = 0.72): DiffResult {
        // 静态字段偏移为负（IL2CPP 惯例：ThreadStatic/静态区索引），不参与实例布局对账——
        // 两张表都滤掉（文档声明与行为一致，调用方要查静态字段自己按名查）。
        val oldCards = old.filter { it.offset >= 0 }
        val newCards = new.filter { it.offset >= 0 }
        val oldByName = oldCards.associateBy { it.name }
        val newByName = newCards.associateBy { it.name }
        val kept = mutableListOf<DiffRow.Kept>()
        val shifted = mutableListOf<DiffRow.Shifted>()
        val renamed = mutableListOf<DiffRow.Renamed>()
        val added = mutableListOf<DiffRow.Added>()
        val gone = mutableListOf<DiffRow.Gone>()

        // 第一遍：同名字段直接对
        val matchedNew = mutableSetOf<String>()
        for (o in oldCards) {
            val n = newByName[o.name]
            if (n != null) {
                matchedNew += n.name
                if (n.offset == o.offset) {
                    kept += DiffRow.Kept(o.name, o.offset, o.typeName)
                } else {
                    shifted += DiffRow.Shifted(o.name, o.offset, n.offset, n.offset - o.offset, n.typeName)
                }
            }
        }
        // 第二遍：旧表剩下的，到新表未匹配的里找相似名
        val unmatchedNew = newCards.filter { it.name !in matchedNew }
        val consumedNew = mutableSetOf<String>()
        for (o in oldCards) {
            if (newByName.containsKey(o.name)) continue
            var best: Pair<FieldCard, Double>? = null
            for (n in unmatchedNew) {
                if (n.name in consumedNew) continue
                val sim = nameSimilarity(o.name, n.name)
                if (sim >= similarityFloor && (best == null || sim > best!!.second)) {
                    best = n to sim
                }
            }
            if (best != null) {
                val (n, sim) = best
                consumedNew += n.name
                renamed += DiffRow.Renamed(o.name, n.name, o.offset, n.offset, sim)
            } else {
                gone += DiffRow.Gone(o.name, o.offset)
            }
        }
        // 第三遍：新表还没人认领的 = 新增
        for (n in unmatchedNew) {
            if (n.name !in consumedNew) added += DiffRow.Added(n.name, n.offset, n.typeName)
        }
        return DiffResult(kept, shifted, renamed, added, gone)
    }

    /**
     * 消失字段的候选偏移穷举。
     *
     * 规则（外部仓实证 + IL2CPP 运行时布局）：
     *  - arm64 引用/long/double=8、int/float=4、short/char=2、byte/bool=1，自然对齐
     *  - 实例字段从 16（对象头）起排，静态字段单独区（负区不打扰实例候选）
     *  - 一次版本更新常见漂移=前插 1 个引用字段（+8）或重排头（±16）；
     *    默认格点步长 4（int/float 粒度），窗口 ±64 字节 -> 候选最多 33 个
     *  - 候选必须落在 [16, classSize) 且对齐到字段自身宽度——格点先按 4 出，
     *    调用方按 [typeName] 再筛 8 对齐的（引用/long）
     *
     * @param oldOffset 旧版本偏移
     * @param typeName 字段类型名（决定对齐要求）
     * @param classSize 新版本类大小（估算上限；0=未知，用 4096 兜底）
     * @param window 字节窗口（默认 64）
     * @param step 格点步长（默认 4；引用类型字段建议传 8）
     */
    fun candidates(oldOffset: Int, typeName: String, classSize: Int = 0, window: Int = 64, step: Int = 4): List<Int> {
        require(step in 1..16) { "step 必须在 1..16" }
        require(window in 4..256) { "window 必须在 4..256" }
        val alignment = alignmentOf(typeName)
        val actualStep = maxOf(step, alignment)
        val upper = if (classSize > 0) classSize else 4096
        val result = mutableListOf<Int>()
        var d = 0
        // 先向后（新版本前插字段=偏移增大，最常见），再向前（字段被删=偏移回缩）
        while (d < window) {
            val back = oldOffset + d
            if (back in 16 until upper && back % actualStep == 0) result += back
            if (d > 0) {
                val forth = oldOffset - d
                if (forth in 16 until upper && forth % actualStep == 0) result += forth
            }
            d += actualStep
        }
        return result
    }

    /** 类型的对齐字节数（IL2CPP arm64 自然对齐；未知类型按 4——int/float 是最常见档）。 */
    fun alignmentOf(typeName: String): Int {
        val t = typeName.substringBefore('<').substringAfterLast('.').trim()
        return when {
            t.isEmpty() -> 4
            t in LONG_TYPES -> 8
            t in FOUR_TYPES -> 4
            t in TWO_TYPES -> 2
            t in ONE_TYPES -> 1
            t == "Void" -> 1
            // 未知（含各游戏自定义结构体）：按引用档 8 兜底——宁可漏报候选不误报
            else -> 8
        }
    }

    private val LONG_TYPES = setOf("Int64", "UInt64", "Long", "ULong", "Double", "IntPtr", "UIntPtr", "Object", "String")
    private val FOUR_TYPES = setOf("Int32", "UInt32", "Int", "UInt", "Single", "Float", "Char32")
    private val TWO_TYPES = setOf("Int16", "UInt16", "Short", "UShort", "Char")
    private val ONE_TYPES = setOf("SByte", "Byte", "Boolean", "Bool")

    /**
     * 名字相似度（0-1）：编辑距离归一化。简单但够用——字段改名一般是
     * 前后缀微调（RemainTurnNum->RemainTurnCount），不是面目全非。
     */
    fun nameSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val la = a.length
        val lb = b.length
        if (la == 0 || lb == 0) return 0.0
        val max = maxOf(la, lb)
        var prev = IntArray(lb + 1) { it }
        var cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return 1.0 - prev[lb].toDouble() / max
    }

    /**
     * 解析观测桥 /fields/<class> 的响应为字段卡列表。
     * 响应形状（hlpatch enumerate_class_fields 实测）：
     *   {"total":N,"fields":[{"name":"X","offset":24,"class":"Y","type_enum":8,"type_name":"Int32"},...]}
     * 静态字段 offset 为负（IL2CPP 惯例：-1 或负区索引）——解析时如实保留，diff 时跳过。
     */
    fun parseFieldsResponse(body: String): List<FieldCard> {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("响应不是 JSON 对象")
        val arr = (obj["fields"] as? kotlinx.serialization.json.JsonArray)
            ?: throw IllegalArgumentException("响应没有 fields 数组")
        return arr.mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            val name = (f["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val offset = (f["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val typeName = (f["type_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            FieldCard(name, offset, typeName)
        }
    }

    /** 静态字段偏移为负（IL2CPP 惯例），实例对账时滤掉。 */
    fun List<FieldCard>.instanceOnly(): List<FieldCard> = filter { it.offset >= 0 }

    /** 差账转人话清单（AI 板/日志两用）。 */
    fun DiffResult.render(): String = buildString {
        appendLine(summary())
        if (shifted.isNotEmpty()) {
            appendLine("-- 平移明细 --")
            shifted.sortedBy { it.oldOffset }.forEach {
                val sign = if (it.delta >= 0) "+" else ""
                appendLine("  ${it.name}: ${it.oldOffset} -> ${it.newOffset} (${sign}${it.delta}) ${it.typeName}")
            }
        }
        if (renamed.isNotEmpty()) {
            appendLine("-- 改名候选（相似度） --")
            renamed.sortedByDescending { it.similarity }.forEach {
                appendLine("  ${it.name} -> ${it.newName}: ${it.oldOffset} -> ${it.newOffset} (${"%.2f".format(it.similarity)})")
            }
        }
        if (added.isNotEmpty()) {
            appendLine("-- 新增 --")
            added.sortedBy { it.offset }.forEach { appendLine("  ${it.name} @ ${it.offset} ${it.typeName}") }
        }
        if (gone.isNotEmpty()) {
            appendLine("-- 消失 --")
            gone.sortedBy { it.oldOffset }.forEach { appendLine("  ${it.name} @ ${it.oldOffset}") }
        }
    }
}
