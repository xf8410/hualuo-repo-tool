package com.hualuo.engine

import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 机器闸门：**源码（.kt/.kts）里不许出现表情与符号，转义写法同样不许**。
 *
 * 家规（用户 2026-09-15 明确）：从来没人允许把表情放进代码；写成「反斜杠 u 加四位十六进制」
 * 也不行 —— 那还是同一个字符，只是看不见，而且给以后留坑：改坏一位就是编译错误，
 * 或者运行时变成一个孤立代理位（半个表情）。
 *
 * 所以这里判两件事：
 *  1) 裸码位：源码字节里直接出现被禁号段的字符；
 *  2) 转义文本：源码里出现「反斜杠 u + 四位十六进制」，且解出来的码位落在被禁号段。
 *
 * 需要显示图形字符怎么办：**进资源文件**（`app/src/main/res/values/*.xml`，UTF-8 是它的本行），
 * 代码里只放键名。本文件自己也不许写下那个写法，否则被自己判红 —— 这是故意的，
 * 所有样本都在运行时按码位拼出来。
 *
 * 中文与中文标点不在禁止范围：界面文案和注释本来就该是中文。
 */
class NoEmojiInSourceTest {

    /** 一处命中：文件、行号、码位，以及它是裸字符还是转义写法。 */
    private data class Hit(val file: String, val line: Int, val codePoint: Int, val form: String) {
        fun describe(): String = "$file:$line ${form}码位 U+${"%04X".format(codePoint)}"
    }

    @Test
    fun kotlinSourcesContainNoEmojiEvenAsEscapes() {
        val root = repoRoot()
        val sources = findSources(root)
        val hits = ArrayList<Hit>()

        for (file in sources) {
            val relative = relative(root, file)
            var lineNo = 0
            // forEachLine 逐行读：不整文件进内存，也不碰被禁的读法
            file.forEachLine { line ->
                lineNo += 1
                scanLine(line, relative, lineNo, hits)
            }
        }

        if (hits.isNotEmpty()) {
            val shown = hits.take(40).joinToString("\n  - ") { it.describe() }
            val more = if (hits.size > 40) "\n  ...另有 ${hits.size - 40} 处" else ""
            fail(
                "源码里不许有表情与符号，转义写法也不行（要显示就放资源文件）。命中 ${hits.size} 处：\n" +
                    "  - $shown$more",
            )
        }
        assertTrue("闸门必须真扫到东西（扫到 0 个文件就是自己在装样子）", sources.isNotEmpty())
    }

    /** 一行里既查裸码位，也查转义写法。 */
    private fun scanLine(line: String, file: String, lineNo: Int, hits: ArrayList<Hit>) {
        var index = 0
        while (index < line.length) {
            val code = Character.codePointAt(line, index)
            if (isBannedCodePoint(code)) hits += Hit(file, lineNo, code, "裸")
            index += Character.charCount(code)
        }
        val matcher = ESCAPE.matcher(line)
        while (matcher.find()) {
            val decoded = Integer.parseInt(matcher.group(1), 16)
            if (isBannedCodePoint(decoded)) hits += Hit(file, lineNo, decoded, "转义")
        }
    }

    private fun isBannedCodePoint(code: Int): Boolean = when (code) {
        in 0x1F000..0x1FAFF -> true // 牌面、象形文字、图形扩展（含区域指示符）
        in 0x2600..0x27BF -> true // 杂项符号与装饰符号
        in 0x2190..0x21FF -> true // 箭头
        in 0x2B00..0x2BFF -> true // 方块箭头与几何扩展
        in 0xFE00..0xFE0F -> true // 变体选择符
        code == 0x200D -> true // 零宽连接符：表情组合用它
        code == 0x20E3 -> true // 组合用键帽
        else -> false
    }

    @Test
    fun escapeFormIsRejectedJustLikeTheRawCharacter() {
        // 这条是用户这次纠正的核心：转义写法必须和裸字符一起判红
        val escaped = ArrayList<Hit>()
        scanLine("val icon = \"" + ESCAPED_DD01 + "\"", "Sample.kt", 7, escaped)
        assertTrue("转义写法该被抓到：$escaped", escaped.any { it.form == "转义" })
        assertTrue("抓到的是循环箭头那个码位：$escaped", escaped.any { it.codePoint == 0x1F501 || it.codePoint == 0xDD01 })
        assertEquals(7, escaped.first().line)

        val raw = ArrayList<Hit>()
        scanLine("图标 " + CYCLE_ARROW + " 在这里", "Sample.kt", 3, raw)
        assertTrue("裸字符也该被抓到：$raw", raw.any { it.form == "裸" })
    }

    @Test
    fun chineseAndAsciiStayAllowed() {
        // 反例：中文、中文标点、ASCII 必须放过，否则闸门就是捣乱
        val hits = ArrayList<Hit>()
        scanLine("网关掐了自动重发一次（默认关），temp=0.7", "Sample.kt", 1, hits)
        scanLine("上限 1 MiB；键名 ui.retry_costly_on_gateway", "Sample.kt", 2, hits)
        assertTrue("中文与 ASCII 不该命中：$hits", hits.isEmpty())
        assertFalse(isBannedCodePoint('中'.code))
        assertFalse(isBannedCodePoint('。'.code))
        assertFalse(isBannedCodePoint('《'.code))
    }

    @Test
    fun bannedRangesCoverTheRealIconBlocks() {
        // 钉住判定表：这些号段就是图形字符住的地方，谁删一段都得有理由
        for (code in listOf(0x1F600, 0x1F501, 0x2705, 0x2630, 0x2192, 0x2B06, 0xFE0F, 0x200D)) {
            assertTrue("该禁：U+${"%04X".format(code)}", isBannedCodePoint(code))
        }
    }

    private fun findSources(root: File): List<File> = root.walkTopDown()
        .onEnter { dir -> dir.name != "build" && dir.name != ".git" && dir.name != ".gradle" }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
        .toList()

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')

    /** 从模块目录往上找仓库根（测试工作目录是 engine/）。找不到就抛，不许静默放行。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        var steps = 0
        while (dir != null && steps < 5) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            steps += 1
        }
        fail("找不到仓库根（没有 settings.gradle.kts），闸门不许静默放行")
    }

    private companion object {
        /** 反斜杠一个。拼出来用，源码里不留任何被禁写法的字面量。 */
        const val BACKSLASH: String = "\\"

        /** 字母 u。与被禁写法拆成两段，源码自身才不会被自己判红。 */
        const val LETTER_U: String = "u"

        /** 正则：字面反斜杠 + u + 四位十六进制（正则里两个反斜杠才表示一个字面反斜杠）。 */
        val ESCAPE: Pattern = Pattern.compile(BACKSLASH + BACKSLASH + LETTER_U + "([0-9a-fA-F]{4})")

        /** 样本：运行时才是「反斜杠 u DD01」这段文本，源码里没有它。 */
        val ESCAPED_DD01: String = BACKSLASH + LETTER_U + "D83D" + BACKSLASH + LETTER_U + "DD01"

        /** 样本：运行时才是那个循环箭头字符，源码里只有码位数字。 */
        val CYCLE_ARROW: String = String(Character.toChars(0x1F501))
    }
}
