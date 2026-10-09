package com.hualuo.engine.observe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏移漂移试探器测试（IL2CPP 立项第一块件）。
 *
 * 覆盖四件事：
 *  1. 对齐规则穷举（用户口中的「8 或 16 或哪一位」）——arm64 自然对齐，
 *     候选=对齐步进序列，不是瞎扫 1-99；
 *  2. 旧表对账新表——保留/平移/新增/消失四态，改名候选按相似度；
 *  3. 观测桥 JSON 解析（/fields/<class> 真实形状）；
 *  4. 边界：静态字段负偏移滤除、空表、全消失。
 */
class OffsetProbeTest {

    private fun card(name: String, offset: Int, type: String = "System.Int32") =
        OffsetProbe.FieldCard(name, offset, type)

    // ---------- 对齐规则 ----------

    @Test
    fun `引用与long按8字节对齐穷举`() {
        // 旧偏移 40 消失：String 是引用=8 字节对齐，候选沿 8 格点先后后前（窗口 24）
        // 用户口中的「8 或 16」：一次引用字段插入推 8，对象头重排推 16（Il2CppDumper -8/-16 同款事实）
        val cands = OffsetProbe.candidates(oldOffset = 40, typeName = "System.String", window = 24)
        // 16=arm64 IL2CPP 对象头下限；窗口内先后后前：40,48,32,56,24（64 超窗）
        assertEquals(listOf(40, 48, 32, 56, 24), cands)
    }

    @Test
    fun `int按4字节对齐穷举`() {
        val cands = OffsetProbe.candidates(oldOffset = 12, typeName = "System.Int32", window = 12)
        // 12 本身 <16（对象头区）不进候选；4 字节格点在窗内且 >=16 的只有 16,20
        assertEquals(listOf(16, 20), cands)
    }

    @Test
    fun `short按2字节对齐`() {
        // 6 <16 且 2 字节格点 6,8,4,10 全在对象头区——空表（诚实，不塞凑数候选）
        assertTrue(OffsetProbe.candidates(6, "System.Int16", window = 6).isEmpty())
    }

    @Test
    fun `未知类型按引用档8兜底宁可少候选不误报`() {
        // 不认识的结构体按 8 对齐（最保守）：7 不落 8 格点 -> 空表，调用方换 step=1 重试
        assertTrue(OffsetProbe.candidates(7, "Whatever", window = 8).isEmpty())
    }

    @Test
    fun `非对齐旧偏移不在格点上时如实给空表`() {
        // 旧表偏移 13（int）：4 格点上 13 不落格——返回空表
        // 调用方应换 step=1 重试（本件不做静默修正：脏数据要暴露不要美化）
        assertTrue(OffsetProbe.candidates(13, "System.Int32", window = 4).isEmpty())
    }

    @Test
    fun `窗口与类大小裁剪候选`() {
        // classSize=20：候选必须落在 [16,20)
        val cands = OffsetProbe.candidates(oldOffset = 16, typeName = "System.Int32", classSize = 20, window = 64)
        assertEquals(listOf(16), cands)
    }

    // ---------- 对账差账 ----------

    @Test
    fun `名字没变偏移没变记保留`() {
        val d = OffsetProbe.diff(
            old = listOf(card("Turn", 16), card("Vitality", 20)),
            new = listOf(card("Turn", 16), card("Vitality", 20)),
        )
        assertEquals(2, d.kept.size)
        assertTrue(d.shifted.isEmpty() && d.added.isEmpty() && d.gone.isEmpty())
    }

    @Test
    fun `同名不同偏移记平移并给候选`() {
        val d = OffsetProbe.diff(
            old = listOf(card("Turn", 16), card("Vitality", 20)),
            new = listOf(card("Turn", 16), card("Vitality", 28)),  // 中间插了个 8 字节字段
        )
        assertEquals(1, d.kept.size)
        assertEquals(1, d.shifted.size)
        assertEquals(8, d.shifted.first().delta)
        assertEquals(28, d.shifted.first().newOffset)
    }

    @Test
    fun `新表多出的字段记新增`() {
        val d = OffsetProbe.diff(
            old = listOf(card("Turn", 16)),
            new = listOf(card("Turn", 16), card("NewField", 24, "System.Int64")),
        )
        assertEquals(1, d.added.size)
        assertEquals("NewField", d.added.first().name)
    }

    @Test
    fun `旧表有新表没有记消失并穷举候选`() {
        val d = OffsetProbe.diff(
            old = listOf(card("Turn", 16), card("OldGuy", 40, "System.String")),
            new = listOf(card("Turn", 16)),
        )
        assertEquals(1, d.gone.size)
        assertEquals("OldGuy", d.gone.first().name)
        // 消失字段的候选由调用方按需穷举（candidates 单独调用），差账只记事实
        assertTrue(OffsetProbe.candidates(40, "System.String", window = 16).contains(40))
    }

    @Test
    fun `改名按相似度给候选`() {
        // 旧 CheckPointPt -> 新 CheckpointPt（官方改名）：一处字符差异
        val d = OffsetProbe.diff(
            old = listOf(card("CheckPointPt", 32)),
            new = listOf(card("CheckpointPt", 32)),
        )
        assertEquals(1, d.renamed.size)
        assertEquals("CheckpointPt", d.renamed.first().newName)
        assertTrue(d.renamed.first().similarity > 0.8)
    }

    @Test
    fun `完全无关的名字不硬凑改名候选`() {
        val d = OffsetProbe.diff(
            old = listOf(card("CheckPointPt", 32)),
            new = listOf(card("TotallyDifferent", 32)),
        )
        // 相似度太低不给改名候选，两边各记消失/新增
        assertTrue(d.renamed.isEmpty())
        assertEquals(1, d.gone.size)
        assertEquals(1, d.added.size)
    }

    // ---------- 静态字段与边界 ----------

    @Test
    fun `静态字段负偏移不进对账`() {
        val d = OffsetProbe.diff(
            old = listOf(card("Turn", 16), card("Instance", -1)),
            new = listOf(card("Turn", 16), card("Instance", -1)),
        )
        // 两张表的 -1（IL2CPP 静态字段惯例）都滤掉，对账只看实例字段
        assertEquals(1, d.kept.size)
    }

    @Test
    fun `空表对空表不炸`() {
        val d = OffsetProbe.diff(old = emptyList(), new = emptyList())
        assertEquals(0, d.kept.size)
        assertTrue(d.summary().contains("0"))
    }

    // ---------- 观测桥 JSON 解析 ----------

    @Test
    fun `解析fields端点真实形状`() {
        val body = """
            {"total":3,"fields":[
              {"name":"Turn","offset":16,"class":"DataSet","type_enum":8,"type_name":"System.Int32"},
              {"name":"Vitality","offset":20,"class":"DataSet","type_enum":8,"type_name":"System.Int32"},
              {"name":"Note","offset":24,"class":"DataSet","type_enum":14,"type_name":"System.String"}
            ]}
        """.trimIndent()
        val cards = OffsetProbe.parseFieldsResponse(body)
        assertEquals(3, cards.size)
        assertEquals("Turn", cards[0].name)
        assertEquals(16, cards[0].offset)
        assertEquals("System.String", cards[2].typeName)
    }

    @Test
    fun `坏JSON明示报错不静默`() {
        val thrown = try {
            OffsetProbe.parseFieldsResponse("not json at all")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null && thrown.message!!.contains("JSON"))
    }

    // ---------- 人话渲染 ----------

    @Test
    fun `render出平移与消失明细`() {
        val d = OffsetProbe.diff(
            old = listOf(card("Turn", 16), card("OldGuy", 40, "System.String")),
            new = listOf(card("Turn", 24), card("NewGuy", 48, "System.Int64")),
        )
        val text = with(OffsetProbe) { d.render() }
        assertTrue(text.contains("Turn: 16 -> 24"))
        assertTrue(text.contains("OldGuy"))
        assertTrue(text.contains("NewGuy"))
        assertFalse(text.contains("delta"))
    }
}
