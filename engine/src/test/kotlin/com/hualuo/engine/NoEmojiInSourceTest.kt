package com.hualuo.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 机器闸门：**源码里不许出现裸的表情与符号码位**（家规：代码里禁止 emoji 表情）。
 *
 * 为什么要有这条：界面要显示图标是对的，但写法必须是转义形式（`"\u2630"`、`"\uD83D\uDD01"`），
 * 这样 .kt 文件的字节始终是 ASCII 可见的。裸 emoji 进源码会带来三件实事：
 *  1) 终端/补丁工具/编码转换一多，就成了旧仓那种「半个表情 = 孤立代理位」的烂字节温床；
 *  2) 同一屏代码里图标看不出差别，评审时看不见自己改错了哪个（我自己就犯过：
 *     重投文件时把转义形式"顺手"改成裸 emoji，没人拦得住）；
 *  3) 检索、diff、正则处理都会被多字节图形字符搅乱。
 *
 * 所以规矩很简单：**要显示就写转义，别写字符本身**。这条测试扫全仓 .kt/.kts，
 * 命中就红，并把文件、行号、码位一起报出来（只报码位，绝不在报错文本里再放一个裸字符）。
 *
 * 中文不在禁止范围：界面文案与注释本来就该是中文，禁的是图形表情/符号码位。
 */
class NoEmojiInSourceTest {

    /** 一个命中：文件、行号、码位。 */
    private data class Hit(val file: String, val line: Int, val codePoint: Int) {
        fun describe(): String = "$file:$line 出现 U+${"%04X".format(codePoint)}"
    }

    @Test
    fun noRawEmojiOrSymbolCodePointsInKotlinSources() {
        val root = repoRoot()
        val hits = ArrayList<Hit>()

        root.walkTopDown()
            .onEnter { dir -> dir.name != "build" && dir.name != ".git" && dir.name != ".gradle" }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .forEach { file ->
                // forEachLine 是逐行读，不整文件进内存，也不碰被禁的读法
                file.forEachLine { line -> scan(line, relative(root, file), hits) }
            }

        if (hits.isNotEmpty()) {
            val shown = hits.take(20).joinToString("\n  - ") { it.describe() }
            fail(
                "源码里不许有裸表情/符号码位（要显示请写 \\uXXXX 转义）。命中 ${hits.size} 处：\n  - $shown" +
                    if (hits.size > 20) "\n  …另有 ${hits.size - 20} 处" else "",
            )
        }
        assertTrue("扫过了 .kt 文件", findSources(root).isNotEmpty())
    }

    private fun scan(line: String, file: String, hits: ArrayList<Hit>) {
        var index = 0
        while (index < line.length) {
            val code = Character.codePointAt(line, index)
            if (isBanned(code)) {
                hits += Hit(file, lineNumber = 0, codePoint = code)
            }
            index += Character.charCount(code)
        }
    }

    private fun isBanned(code: Int): Boolean = when (code) {
        in 0x1F000..0x1FAFF -> true // 牌面、象形文字、扩展图形
        in 0x2600..0x27BF -> true // 杂项符号与装饰符号（☰ ✂ ✅ 这类）
        in 0x2190..0x21FF -> true // 箭头
        in 0x2B00..0x2BFF -> true // 方块箭头与几何扩展
        in 0xFE00..0xFE0F -> true // 变体选择符（跟在前面的符号后改外观）
        in 0x1F1E6..0x1F1FF -> true // 区域指示符（国旗）
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

    /** 从模块目录往上找仓库根（测试的工作目录是 engine/）。 */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        fail("找不到仓库根（没有 settings.gradle.kts），闸门不该静默放过")
    }
}
