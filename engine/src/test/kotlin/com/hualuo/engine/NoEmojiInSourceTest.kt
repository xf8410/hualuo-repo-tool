package com.hualuo.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 机器闸门：**源码里不许出现裸的表情与符号码位**（家规：代码里禁止 emoji 表情）。
 *
 * 界面要显示图标是对的，但写法必须是转义形式（源码里写反斜杠 + u + 四位十六进制，
 * 例如 `\\u2630`、`\\uD83D\\uDD01`），这样 .kt 文件的字节始终是 ASCII 可见的。
 * 裸表情进源码会带来三件实事：
 *  1) 终端、补丁工具、编码转换一多，就成了旧仓那种「半个表情 = 孤立代理位」烂字节的温床；
 *  2) 一屏里几个图标长得几乎一样，评审时看不见自己改错了哪个（我自己就犯过：
 *     重投文件时把转义形式"顺手"改成裸字符，没人拦得住）；
 *  3) 检索、diff、正则处理都会被多字节图形字符搅乱。
 *
 * 规矩就一句：**要显示就写转义，别写字符本身**。这条扫全仓 .kt/.kts，命中即红，
 * 报出文件、行号、码位（报错文本里只写码位，绝不再放一个裸字符）。
 *
 * 中文不在禁止范围：界面文案与注释本来就该是中文，禁的是图形表情与符号码位。
 */
class NoEmojiInSourceTest {

    /** 一处命中：文件、行号、码位。 */
    private data class Hit(val file: String, val line: Int, val codePoint: Int) {
        fun describe(): String = "$file:$line 出现 U+${"%04X".format(codePoint)}"
    }

    @Test
    fun noRawEmojiOrSymbolCodePointsInKotlinSources() {
        val root = repoRoot()
        val sources = findSources(root)
        val hits = ArrayList<Hit>()

        for (file in sources) {
            val relative = relative(root, file)
            var lineNo = 0
            // forEachLine 逐行读：不整文件进内存，也不碰被禁的读法
            file.forEachLine { line ->
                lineNo += 1
                scan(line, relative, lineNo, hits)
            }
        }

        if (hits.isNotEmpty()) {
            val shown = hits.take(20).joinToString("\n  - ") { it.describe() }
            val more = if (hits.size > 20) "\n  ...另有 ${hits.size - 20} 处" else ""
            fail("源码里不许有裸表情/符号码位（要显示请写反斜杠 u 转义）。命中 ${hits.size} 处：\n  - $shown$more")
        }
        assertTrue("闸门必须真扫到东西（扫到 0 个文件就是自己在装样子）", sources.isNotEmpty())
    }

    @Test
    fun bannedRangesCoverTheRealIconBlocks() {
        // 钉住判定表本身：这几段是表情与符号真正住的地方，谁删一段都得有理由
        assertTrue(isBanned(0x1F600)) // 笑脸类
        assertTrue(isBanned(0x1F501)) // 循环箭头类
        assertTrue(isBanned(0x2705)) // 白勾
        assertTrue(isBanned(0x2630)) // 三横（顶栏那个按钮）
        assertTrue(isBanned(0x2192)) // 右箭头
        assertTrue(isBanned(0x2B06)) // 粗上箭头
        assertTrue(isBanned(0xFE0F)) // 变体选择符
        assertTrue(isBanned(0x200D)) // 零宽连接符
        // 反例：中文、中文标点、ASCII 必须放过，否则闸门就是捣乱
        assertFalse(isBanned('中'.code))
        assertFalse(isBanned('。'.code))
        assertFalse(isBanned('《'.code))
        assertFalse(isBanned('A'.code))
        assertFalse(isBanned(' '.code))
    }

    @Test
    fun scannerReportsTheLineItActuallyFoundItOn() {
        // 拿一段带命中的文本喂扫描器，确认行号不是写死的 0（上一版就是这么撒谎的）
        val hits = ArrayList<Hit>()
        scan("干净的一行", "Sample.kt", 1, hits)
        scan("另一行也干净", "Sample.kt", 2, hits)
        scan("这一行有符号 ${ARROW_TEST_STRING}", "Sample.kt", 3, hits)

        assertTrue("该扫到东西：$hits", hits.isNotEmpty())
        assertEqualsLine(3, hits.first().line)
    }

    private fun assertEqualsLine(expected: Int, actual: Int) {
        if (expected != actual) fail("行号报错必须真实：期望 $expected 实得 $actual")
    }

    private fun scan(line: String, file: String, lineNo: Int, hits: ArrayList<Hit>) {
        var index = 0
        while (index < line.length) {
            val code = Character.codePointAt(line, index)
            if (isBanned(code)) hits += Hit(file, lineNo, code)
            index += Character.charCount(code)
        }
    }

    private fun isBanned(code: Int): Boolean = when (code) {
        in 0x1F000..0x1FAFF -> true // 牌面、象形文字、图形扩展
        in 0x1F1E6..0x1F1FF -> true // 区域指示符（国旗）
        in 0x2600..0x27BF -> true // 杂项符号与装饰符号
        in 0x2190..0x21FF -> true // 箭头
        in 0x2B00..0x2BFF -> true // 方块箭头与几何扩展
        in 0xFE00..0xFE0F -> true // 变体选择符
        code == 0x200D -> true // 零宽连接符：表情组合用它
        code == 0x20E3 -> true // 组合用键帽
        else -> false
    }

    private fun findSources(root: File): List<File> = root.walkTopDown()
        .onEnter { dir -> dir.name != "build" && dir.name != ".git" && dir.name != ".gradle" }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
        .toList()

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')

    /** 从模块目录往上找仓库根（测试的工作目录是 engine/）。找不到就抛，不许静默放过。 */
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

    companion object {
        /**
         * 扫描器自测用的样本：用码位构造，源码里不留裸字符 ——
         * 否则这条测试自己就会被上面那个全仓扫描判红。
         */
        private val ARROW_TEST_STRING: String = String(Character.toChars(0x2192))
    }
}
