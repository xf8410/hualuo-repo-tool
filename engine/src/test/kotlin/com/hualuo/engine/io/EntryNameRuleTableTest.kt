package com.hualuo.engine.io

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 条目名判定对照表：把一批难缠的名字逐个跑一遍，两层结果（字符串判定 / 最终落点）都打到 CI 日志里。
 *
 * 为什么要这张表，而不是只靠断言：CI 实测有 4 条断言与逐行推理的结论相反，
 * 而 Gradle 的默认短格式只打"java.lang.AssertionError 在 某文件:某行"，消息一个字都看不见，
 * 于是"红了"回答不了"到底是闸漏了还是断言写错了"。这张表一次把全部判据摊开，
 * 属于本仓规矩二第 4、6 条的落地：失败必须自带原因，不许靠人再猜一轮。
 *
 * 本文件只做打印 + 两条兜底断言（表不许空、不许全是同一边）。
 * 下一轮把表里确认过的行改成正经断言，这个文件留着当回归对照，不删。
 */
class EntryNameRuleTableTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val names = listOf(
        "../逃出去的文件.bin",
        "/etc/hosts",
        "..\\..\\w.exe",
        "C:/Windows/x.bin",
        "C|/Windows/x.bin",
        "%2e%2e%2f坏东西.bin",
        "%252e%252e%252f坏东西.bin",
        "//服务器/共享/文件.bin",
        "\\\\服务器\\共享\\文件.bin",
        "..\n伪造一行日志",
        "x".repeat(300) + "/../坏.bin",
        "./子包//再下一层/文件.bin",
        "正常/文件.bin",
        "",
        "   ",
        "..",
        "a/../../b",
        "%2e%2e%2f%252e%252e%2f.bin",
        "带\t制表符.bin",
        "换%0a行.bin",
    )

    /** 让不可见字符在日志里现出原形。先转义反斜杠，否则 `\n` 会被读成两个字符。 */
    private fun visible(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    @Test
    fun `打印条目名判定对照表`() {
        val root: File = folder.newFolder("表根目录")
        println(
            "环境 file.encoding=" + System.getProperty("file.encoding") +
                " sun.jnu.encoding=" + System.getProperty("sun.jnu.encoding"),
        )
        println("根目录=" + root.path + " 存在=" + root.exists())
        var rejected = 0
        var accepted = 0
        names.forEachIndexed { index, raw ->
            val verdict = when (val ruling = ruleOutEntryName(raw)) {
                is NameRuling.Rejected -> {
                    rejected += 1
                    "拒绝：" + ruling.reason
                }
                is NameRuling.Accepted -> {
                    accepted += 1
                    "放行：段=" + ruling.segments.joinToString("|")
                }
            }
            val outcome = runCatching { normalizeEntryPath(root, raw).path }
            val failure = outcome.exceptionOrNull()
            val landed = if (failure == null) {
                "落点=" + visible(outcome.getOrDefault(""))
            } else {
                "落点异常=" + failure::class.simpleName
            }
            println(
                "%02d 名=[%s] 还原=[%s] %s %s".format(
                    index + 1,
                    visible(raw),
                    visible(percentDecodeName(raw).trim()),
                    verdict,
                    landed,
                ),
            )
        }
        println("合计：拒绝 $rejected 条，放行 $accepted 条，共 ${names.size} 条")
        assertTrue("对照表准备了 ${names.size} 行，少于 20 行说明名单被误删", names.size >= 20)
        assertTrue(
            "表里必须既有拒绝也有放行，全是同一边就说明闸或表坏了：拒绝=$rejected 放行=$accepted",
            rejected > 0 && accepted > 0,
        )
    }
}
