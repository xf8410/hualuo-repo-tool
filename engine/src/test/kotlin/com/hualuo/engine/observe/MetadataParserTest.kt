package com.hualuo.engine.observe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * metadata 离线解析器测试（IL2CPP 第四块件）。
 *
 * 自造 mini metadata.bin（魔数+版本 31+31 对段表+string 表+3 字段+1 类），
 * 验证：header 解析/类清单/字段名查询/坏魔数拒/不支持版本拒。
 * 真实文件 151MB，但解析器的内存纪律是按需 seek——小文件同路径全覆盖。
 */
class MetadataParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
    )

    private fun u16(v: Int): ByteArray = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    /** 造 mini metadata：一段 string 表 + 3 个字段 + 1 个类（Gallop.WorkDataManager）。 */
    private fun buildMini(): File {
        val strings = "WorkDataManager\u0000Gallop\u0000Turn\u0000Vitality\u0000CheckPointPt\u0000"
        val strOff = 256L
        val fieldsOff = strOff + strings.length
        val tdOff = fieldsOff + 3 * 12

        val out = java.io.ByteArrayOutputStream()
        // header：魔数 + 版本 31
        out.write(u32(0xFAB11BAFL))
        out.write(u32(31))
        // 31 对段表（按 MetadataParser.SECTION_NAMES_V31 顺序）
        for (sec in MetadataParser.SECTION_NAMES_V31) {
            val (off, size) = when (sec) {
                "string" -> strOff to strings.length.toLong()
                "fields" -> fieldsOff to 3L * 12
                "typeDefinitions" -> tdOff to 88L
                else -> 0L to 0L
            }
            out.write(u32(off))
            out.write(u32(size))
        }
        // string 表
        out.write(strings.toByteArray(Charsets.US_ASCII))
        // fields：Turn@23 Vitality@28 CheckPointPt@37（nameIndex, typeIndex, token）
        for (nameIdx in listOf(23L, 28L, 37L)) {
            out.write(u32(nameIdx))
            out.write(u32(8))
            out.write(u32(0))
        }
        // typeDef 88 字节：nameIndex@0=0 namespaceIndex@4=16 fieldStart@32=0
        // method_count@64=5 field_count@68=3 其余 0
        val rec = ByteArray(88)
        System.arraycopy(u32(0), 0, rec, 0, 4)        // nameIndex: WorkDataManager@0
        System.arraycopy(u32(16), 0, rec, 4, 4)       // namespaceIndex: Gallop@16
        System.arraycopy(u32(0), 0, rec, 32, 4)       // fieldStart
        System.arraycopy(u16(5), 0, rec, 64, 2)       // method_count
        System.arraycopy(u16(3), 0, rec, 68, 2)       // field_count
        out.write(rec)

        val f = tmp.newFile("metadata.bin")
        f.writeBytes(out.toByteArray())
        return f
    }

    @Test
    fun `header解析版本31与段表`() {
        val p = MetadataParser(buildMini())
        val secs = p.sections()
        assertEquals(31, secs.version)
        val (strOff, strSize) = secs.table.getValue("string")
        assertEquals(256L, strOff)
        assertTrue(strSize > 0)
        assertEquals(88L, secs.table.getValue("typeDefinitions")[1])
    }

    @Test
    fun `类清单流式迭代`() {
        val rows = mutableListOf<MetadataParser.TypeRow>()
        val parser = MetadataParser(buildMini())
        parser.forEachType { rows += it }
        assertEquals(1, rows.size)
        assertEquals("WorkDataManager", rows[0].name)
        assertEquals("Gallop", rows[0].namespace)
        assertEquals(3, rows[0].fieldCount)
        assertEquals(5, rows[0].methodCount)
        assertEquals(1, parser.typeCount())
    }

    @Test
    fun `按简名与全名查类字段`() {
        val p = MetadataParser(buildMini())
        val byShort = p.findClass("WorkDataManager")
        assertEquals(listOf("Turn", "Vitality", "CheckPointPt"), byShort!!.fieldNames)
        assertEquals("Gallop", byShort.namespace)
        val byFull = p.findClass("Gallop.WorkDataManager")
        assertEquals(3, byFull!!.fieldNames.size)
        assertNull(p.findClass("NoSuchClass"))
    }

    @Test
    fun `坏魔数如实拒`() {
        val f = buildMini()
        // 改魔数第一字节
        val raf = java.io.RandomAccessFile(f, "rw")
        raf.seek(0)
        raf.write(0x11)
        raf.close()
        val thrown = try {
            MetadataParser(f).sections(); null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null && thrown.message!!.contains("魔数"))
    }

    @Test
    fun `不支持版本如实拒并列清单`() {
        val f = buildMini()
        val raf = java.io.RandomAccessFile(f, "rw")
        raf.seek(4)
        raf.write(u32(27))
        raf.close()
        val thrown = try {
            MetadataParser(f).sections(); null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null && thrown.message!!.contains("27") && thrown.message!!.contains("31"))
    }
}
