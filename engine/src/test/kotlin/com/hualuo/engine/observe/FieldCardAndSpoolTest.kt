package com.hualuo.engine.observe

import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.LineSink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 字段卡仓 + metadata 分片搬运测试（IL2CPP 立项第二/三块件）。
 *
 * 覆盖：
 *  1. FieldCardStore：存/取/原子性、坏 JSON 如实报、路径穿越拒绝；
 *  2. MetadataSpool：read_mem hex dump 解析（真实形状）、分片落盘、断点续传、
 *     完成判定——全程离线假桥（不碰真游戏）；
 *  3. spool 的核心承诺：151MB 全量不在内存里攒（本测试用小体积验证协议，
 *     内存纪律靠「每片 append 后不持引用」的代码结构保证）。
 */
class FieldCardAndSpoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun card(n: String, off: Int, t: String = "System.Int32") = OffsetProbe.FieldCard(n, off, t)

    // ---------- FieldCardStore ----------

    @Test
    fun `存取闭环同版本同类`() {
        val store = FieldCardStore(tmp.newFolder())
        store.save(
            FieldCardStore.Card(
                gameVersion = "686", className = "WorkDataManager",
                fields = listOf(card("Turn", 16), card("Vitality", 20)),
                capturedAtMs = 123L,
            ),
        )
        val loaded = store.load("686", "WorkDataManager")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.fields.size)
        assertEquals("Turn", loaded.fields[0].name)
        assertEquals(123L, loaded.capturedAtMs)
    }

    @Test
    fun `不同版本各存各的互不覆盖`() {
        val store = FieldCardStore(tmp.newFolder())
        store.save(FieldCardStore.Card("676", "C", listOf(card("A", 16)), 1L))
        store.save(FieldCardStore.Card("686", "C", listOf(card("A", 24)), 2L))
        assertEquals(16, store.load("676", "C")!!.fields[0].offset)
        assertEquals(24, store.load("686", "C")!!.fields[0].offset)
    }

    @Test
    fun `坏卡如实报错不静默`() {
        val root = tmp.newFolder()
        val dir = File(root, "686")
        dir.mkdirs()
        File(dir, "C.json").writeText("{broken json")
        val thrown = try {
            FieldCardStore(root).load("686", "C"); null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null && thrown.message!!.contains("C"))
    }

    @Test
    fun `路径分隔符类名直接拒`() {
        val store = FieldCardStore(tmp.newFolder())
        val thrown = try {
            store.save(FieldCardStore.Card("686", "../escape", listOf(), 1L)); null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null)
    }

    @Test
    fun `没有的卡给null`() {
        assertNull(FieldCardStore(tmp.newFolder()).load("999", "Nope"))
    }

    // ---------- MetadataSpool ----------

    /** 假桥：read_mem 按地址切窗口回 hex dump（SO 同款格式）。 */
    private class FakeBridge(val total: Int) : com.hualuo.engine.api.WireTransport {
        val seen = mutableListOf<String>()
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            val path = request.url.substringAfter("18765")
            seen += path
            val addr = Regex("addr=0x([0-9a-f]+)").find(path)!!.groupValues[1].toLong(16)
            val size = Regex("size=(\\d+)").find(path)!!.groupValues[1].toInt()
            val bytes = ByteArray(size) { i -> ((addr.toInt() + i) and 0xff).toByte() }
            val body = StringBuilder("addr: 0x").append(addr.toString(16)).append("\n")
                .append("size: ").append(size).append("\n")
                .append("bytes_read: ").append(size).append("\n\n")
            for (off in bytes.indices step 16) {
                val line = bytes.slice(off until minOf(off + 16, bytes.size))
                val hex = line.joinToString(" ") { String.format("%02x", it) }
                val ascii = line.joinToString("") { if (it in 0x20..0x7e) it.toChar().toString() else "." }
                body.append(String.format("0x%08x:  %-48s %s%n", off, hex, ascii))
            }
            val payload = body.toString()
            sink.onLine(payload)
            return WireResponse(200, null, payload.length.toLong(), null)
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    @Test
    fun `hexdump解析吃真实形状`() {
        val spool = MetadataSpool(tmp.newFolder(), ObserveClient("http://127.0.0.1:18765", FakeBridge(16)))
        val dump = "addr: 0x7f001000\nsize: 4\nbytes_read: 4\n\n" +
            "0x00000000:  af 1b b1 fa                           ....\n"
        val bytes = MetadataSpool.parseHexDump(dump)
        assertEquals(4, bytes!!.size)
        assertEquals(0xaf.toByte(), bytes[0])
        assertEquals(0xfa.toByte(), bytes[3])
    }

    @Test
    fun `hexdump满行后ASCII区hex状词不误吃`() {
        // 16 字节吃满后，ASCII 区 "ab cd"（恰是 2 位 hex 形）不许进数据——
        // 对象内存 ASCII 随机，这个碰撞真会发生（BoardReader 读板就靠它）
        val dump = "addr: 0x7f001000\nsize: 16\nbytes_read: 16\n\n" +
            "0x00000000:  5a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  ab cd\n"
        val bytes = MetadataSpool.parseHexDump(dump)
        assertEquals(16, bytes!!.size)
        assertEquals(0x5a.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[15])
    }

    @Test
    fun `分片搬运与断点续传`() {
        val fake = FakeBridge(160)
        val spoolDir = tmp.newFolder()
        val spool = MetadataSpool(spoolDir, ObserveClient("http://127.0.0.1:18765", fake))
        // 16 字节/片，先搬 5 片（80 字节）
        val r1 = spool.spool("0x1000", 160, chunkSize = 16, maxChunks = 5)
        assertEquals(5, r1.chunksDone)
        assertEquals(80L, r1.bytesDone)
        assertEquals(false, r1.finished)
        // 续传：再 5 片拿完
        val r2 = spool.spool("0x1000", 160, chunkSize = 16, maxChunks = 5)
        assertEquals(160L, r2.bytesDone)
        assertEquals(true, r2.finished)
        // 进度账可查、文件大小对
        assertEquals(160L, spool.progress()!!.doneBytes)
        val bin = File(spoolDir, MetadataSpool.BIN_NAME)
        assertEquals(160L, bin.length())
        // 内容按地址递增（FakeBridge 语义），验证顺序没乱
        val head = bin.readBytes()
        assertEquals(((0x1000 + 0) and 0xff).toByte(), head[0])
        assertEquals(((0x1000 + 159) and 0xff).toByte(), head[159])
    }

    @Test
    fun `addr参数不合法直接拒`() {
        val spool = MetadataSpool(tmp.newFolder(), ObserveClient("http://127.0.0.1:18765", FakeBridge(16)))
        val thrown = try {
            spool.spool("zzz", 160, chunkSize = 16); null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null)
    }
}
