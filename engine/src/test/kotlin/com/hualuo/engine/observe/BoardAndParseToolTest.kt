package com.hualuo.engine.observe

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.toolcalls.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 冷启动流水 L2/L5 两个工具壳测试（全离线假桥）：
 *  - uma_metadata_parse：概要/按类查/人话报错；纯离线（桥缺席也可见）
 *  - uma_board_read：板不存在指路；singletons+read_mem 全链切值；单例后缀匹配
 */
class BoardAndParseToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 按路径分派的假桥：/singletons 给单例表，read_mem 给真实形状 hex 文本。 */
    private class PathBridge(val memBody: String) : WireTransport {
        val paths = mutableListOf<String>()
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            val path = request.url.substringAfter("18765")
            paths += path
            val body = when {
                path == "/singletons" ->
                    """{"total":1,"classes":[{"class":"Gallop.WorkDataManager","singleton":true,"instance":"0x7f1000"}]}"""
                path.startsWith("/il2cpp/read_mem") -> memBody
                else -> """{"error":"not_found","path":"$path"}"""
            }
            body.lines().forEach { sink.onLine(it) } // body 走 sink 流式吐（ObserveClient 按行组装）
            return WireResponse(200, null, body.length.toLong(), null)
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private fun registry(bridge: WireTransport?, cardsDir: File): ToolRegistry {
        val r = ToolRegistry()
        UmaTool.register(r, { bridge?.let { ObserveClient("http://127.0.0.1:18765", it) } }, ObserveState(), cardsDir)
        return r
    }

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
    )

    private fun u16(v: Int): ByteArray = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    /** 造 mini metadata.bin（与 MetadataParserTest 同构：31 段表+string+3 字段+1 类）。 */
    private fun buildMini(dir: File): File {
        val strings = "WorkDataManager\u0000Gallop\u0000Turn\u0000Vitality\u0000CheckPointPt\u0000"
        val strOff = 256L
        val fieldsOff = strOff + strings.length
        val tdOff = fieldsOff + 3 * 12
        val out = java.io.ByteArrayOutputStream()
        out.write(u32(0xFAB11BAFL))
        out.write(u32(31))
        for (sec in MetadataParser.SECTION_NAMES_V31) {
            val (off, size) = when (sec) {
                "string" -> strOff to strings.length.toLong()
                "fields" -> fieldsOff to 3L * 12
                "typeDefinitions" -> tdOff to 88L
                else -> 0L to 0L
            }
            out.write(u32(off)); out.write(u32(size))
        }
        var pad = out.size()
        while (pad < strOff) { out.write(0); pad++ }
        out.write(strings.toByteArray())
        for (i in 0 until 3) {
            out.write(u32(longArrayOf(23L, 28L, 37L)[i])) // nameIndex -> Turn@23/Vitality@28/CheckPointPt@37
            out.write(u32(0)); out.write(u32(0))
        }
        val rec = ByteArray(88)
        System.arraycopy(u32(0), 0, rec, 0, 4)      // nameIndex -> WorkDataManager@0
        System.arraycopy(u32(16), 0, rec, 4, 4)     // namespaceIndex -> Gallop@16..21
        System.arraycopy(u32(0), 0, rec, 32, 4)     // fieldStart
        System.arraycopy(u32(0), 0, rec, 36, 4)     // methodStart
        System.arraycopy(u16(5), 0, rec, 64, 2)     // method_count
        System.arraycopy(u16(3), 0, rec, 68, 2)     // field_count
        out.write(rec)
        val f = File(File(dir, "meta-686"), "metadata.bin")
        f.parentFile.mkdirs()
        f.writeBytes(out.toByteArray())
        return f
    }

    /** 造 read_mem 真实形状文本：turn=90 @0（int32）。 */
    private fun memDump(turn: Int): String {
        val raw = u32(turn.toLong() and 0xffffffffL) + ByteArray(28)
        val sb = StringBuilder("addr: 0x7f1000\nsize: 32\nbytes_read: 32\n\n")
        for (line in 0 until 2) {
            val row = StringBuilder("0x%08x:  ".format(line * 16))
            for (i in 0 until 16) {
                val idx = line * 16 + i
                row.append("%02x ".format(raw[idx]))
            }
            sb.append(row.toString().trimEnd()).append('\n')
        }
        return sb.toString()
    }

    // ---------- uma_metadata_parse ----------

    @Test
    fun `概要与按类查`() {
        val cards = tmp.newFolder()
        buildMini(cards)
        val r = registry(null, cards) // 纯离线：桥缺席也要能用
        val summary = r.execute("uma_metadata_parse", """{"game_version":"686"}""")
        assertTrue(summary.ok)
        assertTrue(summary.text.contains("31"))
        assertTrue(summary.text.contains("1 个类型"))
        val byClass = r.execute("uma_metadata_parse", """{"game_version":"686","class_name":"WorkDataManager"}""")
        assertTrue(byClass.ok)
        assertTrue(byClass.text.contains("Gallop.WorkDataManager"))
        assertTrue(byClass.text.contains("Turn"))
    }

    @Test
    fun `文件不存在人话指路`() {
        val r = registry(null, tmp.newFolder())
        val out = r.execute("uma_metadata_parse", """{"game_version":"999"}""")
        assertTrue(!out.ok)
        assertTrue(out.text.contains("uma_metadata_spool"))
    }

    // ---------- uma_board_read ----------

    @Test
    fun `板不存在人话指路`() {
        val r = registry(PathBridge(memDump(90)), tmp.newFolder())
        val out = r.execute("uma_board_read", """{"scenario":"ramen"}""")
        assertTrue(!out.ok)
        assertTrue(out.text.contains("剧本板还不存在"))
    }

    @Test
    fun `全链单例寻址切值`() {
        val cards = tmp.newFolder()
        val boards = File(cards, "boards").apply { mkdirs() }
        File(boards, "ramen.json").writeText(
            """{"scenario":"ramen","game_version":"686","entries":[
               {"class":"Gallop.WorkDataManager","singleton_hint":"WorkDataManager","fields":[
                 {"semantic":"turn","offset":0,"type_name":"int32","diff_state":"kept","verified":"final","source":"test"},
                 {"semantic":"gone","offset":4000,"type_name":"int32","diff_state":"disappeared","verified":"final","source":"test"}
               ]}
             ]}""",
        )
        val bridge = PathBridge(memDump(90))
        val r = registry(bridge, cards)
        val out = r.execute("uma_board_read", """{"scenario":"ramen"}""")
        assertTrue(out.text, out.ok)
        assertTrue(out.text.contains("turn = 90"))
        assertTrue(!out.text.contains("gone")) // disappeared 不上采集
        assertTrue(bridge.paths.contains("/singletons"))
        assertTrue(bridge.paths.any { it.startsWith("/il2cpp/read_mem?addr=0x7f1000") })
    }

    @Test
    fun `单例没加载如实跳过`() {
        val cards = tmp.newFolder()
        val boards = File(cards, "boards").apply { mkdirs() }
        File(boards, "ramen.json").writeText(
            """{"scenario":"ramen","game_version":"686","entries":[
               {"class":"Gallop.NoSuchManager","singleton_hint":"NoSuchManager","fields":[
                 {"semantic":"turn","offset":0,"type_name":"int32","diff_state":"kept","verified":"final","source":"test"}
               ]}
             ]}""",
        )
        val r = registry(PathBridge(memDump(90)), cards)
        val out = r.execute("uma_board_read", """{"scenario":"ramen"}""")
        assertTrue(out.ok) // 工具成功：如实报告 0 条读到
        assertTrue(out.text.contains("单例未找到"))
        assertTrue(out.text.contains("0 条读到"))
    }
}
