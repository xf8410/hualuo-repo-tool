package com.hualuo.engine.observe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剧本板读取器测试（冷启动流水 L5 切分件）。
 * 造 mini 板 + mini 对象字节（真实 read_mem 形状的 hex 文本），验证：
 * 解板/必需字段如实报/按 offset+type 切值/disappeared 跳过/越界不炸/全链 hex->值。
 */
class BoardReaderTest {

    private fun boardJson(fields: String) = """
        {"scenario":"ramen","game_version":"692","entries":[
          {"class":"Gallop.WorkDataManager","singleton_hint":"WorkDataManager","fields":[$fields]}
        ]}
    """.trimIndent()

    private fun f(semantic: String, off: Int, t: String, diff: String = "kept", v: String = "final") =
        """{"semantic":"$semantic","offset":$off,"type_name":"$t","diff_state":"$diff","verified":"$v"}"""

    @Test
    fun `解板全字段`() {
        val b = BoardReader.parse(boardJson(f("turn", 16, "int32") + "," + f("vitality", 20, "int32")))
        assertEquals("ramen", b.scenario)
        assertEquals("692", b.gameVersion)
        assertEquals(1, b.entries.size)
        val e = b.entries[0]
        assertEquals("Gallop.WorkDataManager", e.className)
        assertEquals("WorkDataManager", e.singletonHint)
        assertEquals(2, e.fields.size)
        assertEquals("turn", e.fields[0].semantic)
        assertEquals(16, e.fields[0].offset)
    }

    @Test
    fun `缺必需字段如实报`() {
        val bad = """{"scenario":"ramen","entries":[]}"""
        val thrown = try { BoardReader.parse(bad); null } catch (e: IllegalArgumentException) { e }
        assertTrue(thrown!!.message!!.contains("game_version"))
        val bad2 = boardJson("""{"offset":16}""")
        val thrown2 = try { BoardReader.parse(bad2); null } catch (e: IllegalArgumentException) { e }
        assertTrue(thrown2!!.message!!.contains("semantic"))
    }

    @Test
    fun `singleton_hint 缺省用短名`() {
        val b = BoardReader.parse("""{"scenario":"x","game_version":"1","entries":[{"class":"A.B.C","fields":[]}]}""")
        assertEquals("C", b.entries[0].singletonHint)
    }

    @Test
    fun `切值 int float bool 指针`() {
        val buf = ByteArray(64)
        // turn=int32 57 @16
        buf[16] = 57.toByte()
        // vitality=float 2.5 @20 (0x40200000)
        val bits = java.lang.Float.floatToIntBits(2.5f)
        for (i in 0 until 4) buf[20 + i] = ((bits shr (8 * i)) and 0xff).toByte()
        // ok=bool true @32
        buf[32] = 1
        // ptr=int64 0x7f1000 @40
        val pv = 0x7f1000L
        for (i in 0 until 8) buf[40 + i] = ((pv shr (8 * i)) and 0xff).toByte()
        val b = BoardReader.parse(boardJson(f("turn", 16, "int32") + "," + f("vitality", 20, "System.Single") + "," + f("ok", 32, "System.Boolean") + "," + f("owner", 40, "SomeObject")))
        val vals = BoardReader.readEntry(b.entries[0], buf)
        assertEquals("57", vals["turn"])
        assertEquals("2.5000", vals["vitality"])
        assertEquals("true", vals["ok"])
        assertEquals("0x00000000007f1000", vals["owner"])
    }

    @Test
    fun `disappeared 跳过且不算尺寸`() {
        val b = BoardReader.parse(boardJson(f("gone", 4000, "int32", diff = "disappeared") + "," + f("turn", 16, "int32")))
        val e = b.entries[0]
        assertEquals(20, BoardReader.requiredBytes(e)) // max(8, 16+4)——disappeared 的 4000 不算
        assertTrue(!BoardReader.readEntry(e, ByteArray(64)).containsKey("gone"))
    }

    @Test
    fun `越界如实报不炸`() {
        val b = BoardReader.parse(boardJson(f("far", 1000, "int32")))
        val vals = BoardReader.readEntry(b.entries[0], ByteArray(64))
        assertEquals("out_of_range", vals["far"])
    }

    @Test
    fun `requiredBytes 封顶 65536`() {
        val b = BoardReader.parse(boardJson(f("far", 70000, "int64")))
        assertEquals(65536, BoardReader.requiredBytes(b.entries[0]))
    }

    @Test
    fun `全链 read_mem 文本到值`() {
        // 造 read_mem 真实形状的 hex 文本：int32 90 @0（turn）
        val raw = byteArrayOf(90.toByte(), 0, 0, 0) + ByteArray(28)
        val sb = StringBuilder("addr: 0x7f1000\nsize: 32\nbytes_read: 32\n\n")
        for (line in 0 until 2) {
            val row = StringBuilder("0x%08x:  ".format(line * 16))
            for (i in 0 until 16) {
                val idx = line * 16 + i
                if (idx < raw.size) row.append("%02x ".format(raw[idx])) else row.append("00 ")
            }
            sb.append(row.toString().trimEnd()).append('\n')
        }
        val bytes = MetadataSpool.parseHexDump(sb.toString())
        assertEquals(32, bytes!!.size)
        val b = BoardReader.parse(boardJson(f("turn", 0, "int32")))
        assertEquals("90", BoardReader.readEntry(b.entries[0], bytes)["turn"])
    }
}
