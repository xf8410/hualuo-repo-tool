package com.hualuo.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * 解压安全闸的行为测试。
 *
 * 测试名全中文且**不带点号**（点号在 Kotlin 反引号方法名里非法，M0 就是这么把编译搞挂的），
 * 这样 CI 日志里能直接读到"哪一条拦住了什么"。每条都对应旧 Agora 真出过的一类事故。
 * 上限一律注入小数值，避免为了测试真造几个 G 的文件。
 *
 * 断言总数刻意停在 30（CI 红线之一：单文件断言数 ≤30）。再加就得拆文件，不要塞进这一份。
 */
class ExtractGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun root(): File = folder.newFolder("包根目录")

    /** 断言"必须抛错"，并把消息原文取回来继续验内容——消息含糊等于没做对。 */
    private fun reject(block: () -> Unit): String =
        runCatching(block).exceptionOrNull()?.message ?: error("本该抛错却安静通过了")

    @Test
    fun `条目名想跳出目标目录必须被拒绝`() {
        val message = reject { normalizeEntryPath(root(), "../逃出去的文件.bin") }
        assertTrue("消息要说清为什么拦：$message", message.contains(".."))
    }

    @Test
    fun `绝对路径条目名必须被拒绝`() {
        val message = reject { normalizeEntryPath(root(), "/etc/hosts") }
        assertTrue("要说是绝对路径：$message", message.contains("绝对路径"))
    }

    @Test
    fun `反斜杠伪装和盘符写法也拦得住`() {
        assertTrue(reject { normalizeEntryPath(root(), "..\\..\\w.exe") }.contains(".."))
        assertTrue(reject { normalizeEntryPath(root(), "C:/Windows/x.bin") }.contains("盘符"))
        assertTrue(reject { normalizeEntryPath(root(), "C|/Windows/x.bin") }.contains("盘符"))
    }

    @Test
    fun `百分号编码藏住的穿越拦得住且双层编码也拦得住`() {
        val single = reject { normalizeEntryPath(root(), "%2e%2e%2f坏东西.bin") }
        assertTrue("单层还原后必须认出越界：$single", single.contains(".."))
        val double = reject { normalizeEntryPath(root(), "%252e%252e%252f坏东西.bin") }
        assertTrue("双层还原后必须认出越界：$double", double.contains(".."))
    }

    @Test
    fun `中文条目名被百分号编码时能还原`() {
        assertEquals("中文 说明.txt", percentDecodeName("%E4%B8%AD%E6%96%87%20%E8%AF%B4%E6%98%8E.txt"))
        // 文件名里的加号是字面量，不能被当成空格（那是表单规则的锅）
        assertEquals("a+b.bin", percentDecodeName("a+b.bin"))
        // 半截百分号不许抛，保留原样，由后面的校验决定去留
        assertEquals("坏%zz名", percentDecodeName("坏%zz名"))
    }

    @Test
    fun `正常子目录能落在根目录里面`() {
        val root = root()
        val dest = normalizeEntryPath(root, "./子包//再下一层/文件.bin")
        assertEquals("文件.bin", dest.name)
        assertTrue("落点必须还在根目录里：${dest.path}", dest.path.startsWith(root.path))
        assertEquals("根下面应该是三层名字", 3, segmentsUnderRoot(dest, root))
    }

    @Test
    fun `反斜杠开头与网络共享路径都算绝对路径`() {
        assertTrue(reject { normalizeEntryPath(root(), "\\\\服务器\\共享\\文件.bin") }.contains("绝对路径"))
        val unc = reject { normalizeEntryPath(root(), "//服务器/共享/文件.bin") }
        assertTrue("UNC 要专门说清：$unc", unc.contains("网络共享"))
    }

    @Test
    fun `中间层是符号链接时也要拦住`() {
        val root = root()
        val outside = folder.newFolder("外面的目录")
        val link = File(root, "链接目录")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            .onFailure { return } // 文件系统不支持符号链接就跳过，不算失败也不算验过
        val message = reject { normalizeEntryPath(root, "链接目录/写出去的文件.bin") }
        assertTrue("符号链接越界必须被 canonical 复核拦住：$message", message.contains("外面"))
    }

    @Test
    fun `异常消息里的原始名会被脱敏`() {
        val withNewline = reject { normalizeEntryPath(root(), "..\n伪造一行日志") }
        assertFalse("原始名里的换行不许进消息，否则能伪造日志行：$withNewline", withNewline.contains('\n'))
        val tooLong = reject { normalizeEntryPath(root(), "x".repeat(300) + "/../坏.bin") }
        assertTrue("超长名要截断：$tooLong", tooLong.contains("等截"))
        assertTrue("整条消息不许被名字撑爆", tooLong.length < 400)
    }

    @Test
    fun `条目数超限时报错消息说得清`() {
        val guard = ExtractGuard(ExtractLimits(maxEntries = 2))
        val root = root()
        guard.beginEntry(root, "一.bin", 10L).also { guard.accept(5L); guard.endEntry(10L) }
        guard.beginEntry(root, "二.bin", 10L).also { guard.accept(5L); guard.endEntry(10L) }
        val message = reject { guard.beginEntry(root, "三.bin", 10L) }
        assertTrue("要写明是条目数问题：$message", message.contains("条目数已达上限"))
        assertEquals(2, guard.entries)
    }

    @Test
    fun `单条目体积超限当场断`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 10L, maxTotalBytes = 100L))
        guard.beginEntry(root(), "胖条目.bin", 5L)
        guard.accept(6L)
        val message = reject { guard.accept(5L) }
        assertTrue("要写明是单条目体积：$message", message.contains("单条目解压后已达 11 字节"))
    }

    @Test
    fun `整包总量超限当场断`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 10L, maxTotalBytes = 12L))
        val root = root()
        guard.beginEntry(root, "第一条.bin", 5L).also { guard.accept(10L); guard.endEntry(5L) }
        guard.beginEntry(root, "第二条.bin", 5L)
        val message = reject { guard.accept(3L) }
        assertTrue("要写明是总量：$message", message.contains("整包解压后已达 13 字节"))
        assertEquals("越限时账已经记上了，调用方据此清理已写出的部分", 13L, guard.decompressedTotal)
    }

    @Test
    fun `压缩比超限当场断`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 100L, maxTotalBytes = 100L, maxRatio = 2.0))
        guard.beginEntry(root(), "炸弹.bin", 10L)
        guard.accept(30L)
        val message = reject { guard.endEntry(10L) }
        assertTrue("要给出倍数和两边字节数：$message", message.contains("压缩比 3.0 超过上限 2.0"))
    }

    @Test
    fun `零字节条目不会被误判成炸弹`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 10L, maxTotalBytes = 10L, maxRatio = 2.0))
        guard.beginEntry(root(), "空目录条目/", 0L)
        guard.accept(0L)
        guard.endEntry(0L) // 目录条目压缩前后都是零，不该抛
        assertEquals(0L, guard.decompressedTotal)
    }

    @Test
    fun `上限写零或负数当场就被拦住`() {
        assertTrue(reject { ExtractLimits(maxEntries = 0) }.contains("条目数上限必须大于零"))
        assertTrue(reject { ExtractLimits(maxEntryBytes = -1L) }.contains("单条目上限必须大于零"))
        assertTrue(reject { ExtractLimits(maxRatio = 0.5) }.contains("压缩比上限必须大于一"))
        assertTrue(
            reject { ExtractLimits(maxEntryBytes = 100L, maxTotalBytes = 50L) }
                .contains("不能小于单条目上限"),
        )
    }

    @Test
    fun `记账字节数为负要报参数错`() {
        val guard = ExtractGuard()
        guard.beginEntry(root(), "正常.bin", 5L)
        assertTrue(reject { guard.accept(-1L) }.contains("字节数不能为负"))
    }

    /** 数一下落点在根目录之下有几层名字（斜杠分隔）。 */
    private fun segmentsUnderRoot(dest: File, root: File): Int =
        dest.absolutePath.removePrefix(root.absolutePath)
            .trim(File.separatorChar)
            .split(File.separatorChar)
            .count { it.isNotEmpty() }
}
