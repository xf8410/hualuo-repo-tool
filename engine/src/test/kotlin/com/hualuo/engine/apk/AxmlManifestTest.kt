package com.hualuo.engine.apk

import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream

/** AXML 解析测试：手拼最小 manifest 字节（串池 UTF-8 + StartTag 三属性），全离线。 */
class AxmlManifestTest {

    /** 拼最小 AXML：StringPool(可 UTF-8/UTF-16) + manifest StartTag（package/versionCode/versionName）。 */
    private fun buildAxml(utf8: Boolean = true): ByteArray {
        val strings = listOf(
            "manifest", "package", "versionCode", "versionName",
            "http://schemas.android.com/apk/res/android", "com.example.test", "1.2.3",
        )
        // 串池数据区（UTF-8：变长前缀两段+数据+\0；UTF-16：u16 字符数+UTF-16LE+\0\0）
        val dataBuf = java.io.ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        if (utf8) {
            for (s in strings) {
                require(s.length < 128) { "测试串用短串（变长前缀 <0x80 各 1 字节）" }
                offsets += dataBuf.size()
                dataBuf.write(s.length)     // u16len（变长，短串 1 字节）
                dataBuf.write(s.length)     // u8len（变长，短串 1 字节）
                dataBuf.write(s.toByteArray(Charsets.UTF_8))
                dataBuf.write(0)
            }
        } else {
            for (s in strings) {
                offsets += dataBuf.size()
                dataBuf.write(byteArrayOf((s.length and 0xFF).toByte(), (s.length shr 8).toByte())) // u16 字符数
                for (ch in s) { dataBuf.write(ch.code and 0xFF); dataBuf.write(ch.code shr 8) }     // UTF-16LE
                dataBuf.write(0); dataBuf.write(0)                                                   // \0\0
            }
        }
        val data = dataBuf.toByteArray()
        val headerSize = 28
        val offsetsSize = strings.size * 4
        val poolSize = headerSize + offsetsSize + data.size
        val pool = java.io.ByteArrayOutputStream()
        val le16 = { v: Int -> byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()) }
        val le32 = { v: Int ->
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        }
        pool.write(le16(0x0001)); pool.write(le16(headerSize)); pool.write(le32(poolSize))
        pool.write(le32(strings.size)); pool.write(le32(0)); pool.write(le32(if (utf8) 0x100 else 0))
        pool.write(le32(headerSize + offsetsSize)); pool.write(le32(0))
        offsets.forEach { pool.write(le32(it)) }
        pool.write(data)

        // manifest StartTag：attr = ns,name,rawValue + typedValue{size=8,res0=0,type,data}
        val manifestIdx = 0
        val androidNsIdx = 4
        fun attr(nameIdx: Int, rawIdx: Int, type: Int, data: Int, hasAndroidNs: Boolean = true): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            o.write(le32(if (hasAndroidNs) androidNsIdx else -1))
            o.write(le32(nameIdx))
            o.write(le32(rawIdx))
            o.write(le16(8))            // typed value size（2 字节，attr 总长 20）
            o.write(0)                  // res0
            o.write(type)               // type（1 字节）
            o.write(le32(data))
            return o.toByteArray()
        }
        val attrs = listOf(
            attr(1, 5, 0x03, 5, hasAndroidNs = false), // package raw="com.example.test"（无 ns）
            attr(2, -1, 0x10, 26),      // android:versionCode INT_DEC=26
            attr(3, -1, 0x03, 6),       // android:versionName "1.2.3"
        )
        val attrExtSize = 20
        val chunkSize = 16 + attrExtSize + attrs.sumOf { it.size }
        val tag = java.io.ByteArrayOutputStream()
        tag.write(le16(0x0102)); tag.write(le16(16)); tag.write(le32(chunkSize))
        tag.write(le32(0)); tag.write(le32(-1))                    // line, comment
        tag.write(le32(-1)); tag.write(le32(manifestIdx))          // ns, name
        tag.write(le16(20)); tag.write(le16(20)); tag.write(le16(attrs.size)) // attrStart/Size/Count
        tag.write(le16(0)); tag.write(le16(0))                     // id/style index
        tag.write(le16(0))                                          // 对齐到 attrStart=20（attrExt 18B + 2B）
        attrs.forEach { tag.write(it) }

        val xmlHeader = java.io.ByteArrayOutputStream()
        xmlHeader.write(le16(0x0003)); xmlHeader.write(le16(8)); xmlHeader.write(le32(8 + pool.size() + tag.size()))
        return xmlHeader.toByteArray() + pool.toByteArray() + tag.toByteArray()
    }

    @Test
    fun `手拼 AXML 抽出包名与版本`() {
        val info = AxmlManifest.parse(ByteArrayInputStream(buildAxml()))
        assertEquals("com.example.test", info.packageName)
        assertEquals("1.2.3", info.versionName)
        assertEquals(26L, info.versionCode)
        assertEquals(7, info.stringCount)
    }

    @Test
    fun `UTF16 串池路径同样抽出包名与版本`() {
        // readUtf16 分支：flags 不带 0x100，串数据 u16 字符数+UTF-16LE
        val info = AxmlManifest.parse(ByteArrayInputStream(buildAxml(utf8 = false)))
        assertEquals("com.example.test", info.packageName)
        assertEquals("1.2.3", info.versionName)
        assertEquals(26L, info.versionCode)
        assertEquals(7, info.stringCount)
    }

    @Test
    fun `截断与越界串池不炸如实返回空`() {
        val le16 = { v: Int -> byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()) }
        val le32 = { v: Int ->
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        }
        // 1) 整体太短：连 chunk 头都凑不齐
        run {
            val info = AxmlManifest.parse(ByteArrayInputStream(ByteArray(4)))
            assertEquals(null, info.packageName)
            assertEquals(0, info.stringCount)
        }
        // 2) 串池 offsets 指到数据区外（越界）：parseStringPool 应 break 不抛
        run {
            val pool = java.io.ByteArrayOutputStream()
            pool.write(le16(0x0001)); pool.write(le16(28))
            pool.write(le32(44)) // 本块总长（28+16）
            pool.write(le32(4))  // stringCount=4（真数据只有 0 条）
            pool.write(le32(0)); pool.write(le32(0)); pool.write(le32(28)); pool.write(le32(0))
            repeat(4) { pool.write(le32(0xFFFF)) } // 全部指到 64KB 外
            val info = AxmlManifest.parse(ByteArrayInputStream(pool.toByteArray()))
            // 源码语义：dataOff 越界由 readUtf16 内部 guard 返回空串，条目仍计入（4 条空串）
            assertEquals(4, info.stringCount)
            assertEquals(null, info.packageName) // 空串池抽不出包名
        }
    }
}
