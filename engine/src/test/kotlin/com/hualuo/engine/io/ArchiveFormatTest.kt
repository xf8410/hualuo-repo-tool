package com.hualuo.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只认文件头这套规矩的行为测试。
 *
 * 关键点：这些字节数组都是手写的**真魔数**，不是从某个包管理处抄的一段含糊注释；
 * 而"没有后缀""后缀撒谎"两种情况才是旧 Agora 真正翻车的地方，所以各有一条专门测试。
 */
class ArchiveFormatTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun tarHead(): ByteArray {
        val head = ByteArray(512)
        val magic = intArrayOf(0x75, 0x73, 0x74, 0x61, 0x72) // ustar
        for (i in magic.indices) head[257 + i] = magic[i].toByte()
        return head
    }

    @Test
    fun `压缩包头被认出来且动作是逐条目过安全闸`() {
        val probe = probeArchiveFormat(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        assertEquals(ArchiveFormat.ZIP, probe.format)
        assertEquals(2, probe.matchedBytes)
        assertTrue("结论必须给下一步动作：${probe.conclusion}", probe.conclusion.contains("ExtractGuard"))
        assertTrue(probe.format.actionable)
    }

    @Test
    fun `gzip头被认出来且说明还要再探一层`() {
        val probe = probeArchiveFormat(bytes(0x1F, 0x8B, 0x08, 0x00))
        assertEquals(ArchiveFormat.GZIP, probe.format)
        assertTrue("复合包要提示再探：${probe.conclusion}", probe.conclusion.contains("再探一次"))
    }

    @Test
    fun `bz2与xz与zstd与七z四种头互不混淆`() {
        assertEquals(ArchiveFormat.BZIP2, probeArchiveFormat(bytes(0x42, 0x5A, 0x68, 0x39)).format)
        assertEquals(
            ArchiveFormat.XZ,
            probeArchiveFormat(bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)).format,
        )
        assertEquals(
            ArchiveFormat.ZSTD,
            probeArchiveFormat(bytes(0x28, 0xB5, 0x2F, 0xFD, 0x00)).format,
        )
        assertEquals(
            ArchiveFormat.SEVEN_Z,
            probeArchiveFormat(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)).format,
        )
    }

    @Test
    fun `四种不内置解压的格式都要在结论里明说不能硬上`() {
        for (format in listOf(
                ArchiveFormat.BZIP2,
                ArchiveFormat.XZ,
                ArchiveFormat.ZSTD,
                ArchiveFormat.SEVEN_Z,
                ArchiveFormat.RAR4,
                ArchiveFormat.RAR5,
            ),
        ) {
            assertFalse("$format 不该被标成可以直接解", format.actionable)
            assertTrue("$format 的结论要能读：${format.nextStep}", format.nextStep.isNotEmpty())
        }
    }

    @Test
    fun `rar老版头与新版头的区别只在第六个字节之后`() {
        assertEquals(ArchiveFormat.RAR4, probeArchiveFormat(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x32)).format)
        assertEquals(ArchiveFormat.RAR5, probeArchiveFormat(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)).format)
    }

    @Test
    fun `tar头在偏移二百五十七处也能认出来`() {
        val probe = probeArchiveFormat(tarHead())
        assertEquals(ArchiveFormat.TAR, probe.format)
        assertEquals(5, probe.matchedBytes)
        assertTrue(probe.format.actionable)
    }

    @Test
    fun `字节不够长时不会被误判成tar`() {
        // 只给 300 字节，装得下偏移 257 的 ustar，但故意把魔数写歪一格
        val head = ByteArray(300)
        head[258] = 0x75
        assertEquals(ArchiveFormat.UNKNOWN, probeArchiveFormat(head).format)
    }

    @Test
    fun `扩展名撒谎时以文件头为准并报告不一致`() {
        val lying = probeArchiveFormatNamed("说是压缩包其实不是.zip", bytes(0x1F, 0x8B, 0x08, 0x00))
        assertEquals(ArchiveFormat.GZIP, lying.format)
        assertEquals(ArchiveFormat.ZIP, lying.nameSays)
        assertTrue("不一致必须被说出来：${lying.conclusion}", lying.disagreesWithExtension)
        assertTrue(lying.conclusion.contains("以文件头为准"))
    }

    @Test
    fun `扩展名与文件头一致时不报警`() {
        val honest = probeArchiveFormatNamed("正常的.jar", bytes(0x50, 0x4B, 0x03, 0x04))
        assertEquals(ArchiveFormat.ZIP, honest.format)
        assertEquals(ArchiveFormat.ZIP, honest.nameSays)
        assertFalse(honest.disagreesWithExtension)
    }

    @Test
    fun `没有后缀也能认出格式`() {
        val noExtension = probeArchiveFormatNamed("全球应用元数据", bytes(0x50, 0x4B, 0x05, 0x06))
        assertEquals(ArchiveFormat.ZIP, noExtension.format)
        assertNull("没后缀时不该硬编一个来源", noExtension.nameSays)
        assertFalse(noExtension.disagreesWithExtension)
        assertTrue("要说明扩展名说明不了格式：${noExtension.conclusion}", noExtension.conclusion.contains("说明不了"))
    }

    @Test
    fun `安装包与依赖包这类后缀本质上都是压缩包`() {
        for (name in listOf("应用.APK", "库.aar", "插件.JAR", "书.epub")) {
            assertEquals("$name 应当被当成压缩包对待", ArchiveFormat.ZIP, formatFromExtension(name))
        }
    }

    @Test
    fun `认不出来的字节就说不出来且不给出可执行动作`() {
        val probe = probeArchiveFormat(bytes(0x00, 0x01, 0x02, 0x03, 0x7F, 0x80))
        assertEquals(ArchiveFormat.UNKNOWN, probe.format)
        assertFalse(probe.format.actionable)
        assertEquals(0, probe.matchedBytes)
        assertTrue("要把头部字节报出来方便判断：${probe.conclusion}", probe.conclusion.contains("7F 80"))
    }

    @Test
    fun `空字节数组不崩且结论是认不出来`() {
        assertEquals(ArchiveFormat.UNKNOWN, probeArchiveFormat(ByteArray(0)).format)
        assertEquals(ArchiveFormat.UNKNOWN, probeArchiveFormatNamed(null, ByteArray(0)).format)
        assertEquals(ArchiveFormat.UNKNOWN, probeArchiveFormat(ByteArray(0), offset = 4).format)
    }

    @Test
    fun `带偏移探测能从复合流中间接着认`() {
        // 模拟"脱掉一层之后剩下的字节"：前面塞四字节垃圾，从偏移 4 处认出 gzip 头
        val head = bytes(0x00, 0x11, 0x22, 0x33, 0x1F, 0x8B, 0x08, 0x00)
        val probe = probeArchiveFormat(head, offset = 4)
        assertEquals(ArchiveFormat.GZIP, probe.format)
        assertEquals(head.size, probe.matchedBytes + head.size) // matchedBytes 只算命中长度
    }
}
