package com.hualuo.engine.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言批次测试（2026-10-03 重开 10-02 那五个红任务的落点）。
 *
 * 那批任务叫「功能 #6 C / #7 C++ / #8 C# / #9 JavaScript / #10 TypeScript ·
 * 语法高亮与查看器着色」，五个一起红，但它们的 workflow 文件跑完就被删了，
 * main 与当时的 sha 上都不在 —— 没法 rerun，也没法看它当年到底缺什么。
 * 与其考古，不如把那五件事写成机器判的测试，再用一个五条 matrix 的工作流
 * 当批次入口：`.github/workflows/feature-langs.yml`，一次 dispatch 五个 job，
 * 每个 job 只跑自己那一个语言（`--tests "*_<lang>Case"`），单跑不拖累别人。
 *
 * 每条测两件事，缺一不可：
 *   1. 扩展名落到正确的语言（不是「纯文本」兜底）——查看器认不出语言就等于没高亮；
 *   2. 该语言的字符串被染色——染色器对这门语言真的动了手，不是照抄 PLAIN。
 *
 * 测试方法名一律 ASCII（家规：反引号测试名带点会让编译挂过一次）。
 */
class LanguageHighlightMatrixTest {

    private fun assertMapped(ext: String, expectedName: String) {
        val lang = LangRegistry.byExtension(ext)
        assertEquals(
            "扩展名 .$ext 认错语言了：期望 $expectedName，实际 ${lang.name}（认不出就退纯文本兜底）",
            expectedName,
            lang.name,
        )
    }

    private fun assertStringColored(ext: String, code: String) {
        val lang = LangRegistry.byExtension(ext)
        val kinds = Highlight.highlight(code, lang).flatMap { line -> line.spans.map { it.kind } }
        assertTrue(".$ext 里这段代码一个字符串都没染（实际=$kinds）", kinds.contains(Highlight.Kind.STRING))
    }

    @Test
    fun _cCase() {
        assertMapped("c", "C")
        assertStringColored("c", "char *name = \"hualuo\";")
    }

    @Test
    fun _cppCase() {
        assertMapped("cpp", "C++")
        assertStringColored("cpp", "std::string s = \"hualuo\";")
    }

    @Test
    fun _csharpCase() {
        assertMapped("csharp", "C#")
        assertMapped("cs", "C#")
        assertStringColored("csharp", "string s = \"hualuo\";")
    }

    @Test
    fun _javascriptCase() {
        assertMapped("javascript", "JavaScript")
        assertMapped("js", "JavaScript")
        assertStringColored("javascript", "const s = \"hualuo\";")
    }

    @Test
    fun _typescriptCase() {
        assertMapped("typescript", "TypeScript")
        assertMapped("ts", "TypeScript")
        assertStringColored("typescript", "const s: string = \"hualuo\";")
    }
}