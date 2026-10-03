package com.hualuo.engine.language

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 长扩展名（csharp / javascript / typescript）走 [LangExtAliases] 别名表，
 * 与查看器 [ViewerUiState.resolveLangName] 同一口径：先查别名表，没命中再走
 * [LangRegistry.byExtension]。wiringIsActuallyPluggedIn 那条测试盯着接线不许掉，
 * 免得「测试绿了但界面还是纯文本」。
 *
 * 测试方法名一律 ASCII（家规：反引号测试名带点会让编译挂过一次）。
 */
class LanguageHighlightMatrixTest {

    /** 与 ViewerUiState.resolveLangName 同口径的解析（别名表优先，再走注册表）。 */
    private fun langFor(ext: String): LangRegistry.Lang {
        val aliasId = LangExtAliases.resolveId(ext)
        if (aliasId != null) {
            LangRegistry.allLangs().firstOrNull { it.id == aliasId }?.let { return it }
        }
        return LangRegistry.byExtension(ext)
    }

    private fun assertMapped(ext: String, expectedName: String) {
        val lang = langFor(ext)
        assertEquals(
            "扩展名 .$ext 认错语言了：期望 $expectedName，实际 ${lang.name}（认不出就退纯文本兜底）",
            expectedName,
            lang.name,
        )
    }

    private fun assertStringColored(ext: String, code: String) {
        val lang = langFor(ext)
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
        // 短名本来就在注册表里，长名靠别名表（批次 run 37095507508 红的就是长名）
        assertMapped("cs", "C#")
        assertMapped("csharp", "C#")
        assertStringColored("csharp", "string s = \"hualuo\";")
    }

    @Test
    fun _javascriptCase() {
        assertMapped("js", "JavaScript")
        assertMapped("javascript", "JavaScript")
        assertStringColored("javascript", "const s = \"hualuo\";")
    }

    @Test
    fun _typescriptCase() {
        assertMapped("ts", "TypeScript")
        assertMapped("typescript", "TypeScript")
        assertStringColored("typescript", "const s: string = \"hualuo\";")
    }

    @Test
    fun aliasTableCoversTheThreeLongNames() {
        assertEquals("csharp", LangExtAliases.resolveId("csharp"))
        assertEquals("javascript", LangExtAliases.resolveId("javascript"))
        assertEquals("typescript", LangExtAliases.resolveId("typescript"))
        // 大小写与点号前缀都吃（与注册表同口径）
        assertEquals("csharp", LangExtAliases.resolveId(".CSharp"))
        assertEquals("typescript", LangExtAliases.resolveId("  TS  "))
        // 别名指向的语言 id 必须真的存在，否则等于把纯文本换了个名字
        LangExtAliases.all().forEach { (ext, id) ->
            assertNotNull("别名 .$ext 指向的语言 id $id 在注册表里不存在", LangRegistry.allLangs().firstOrNull { it.id == id })
        }
    }

    /** 接线不许掉：查看器取语言那一处必须真的查别名表，否则界面还是纯文本。 */
    @Test
    fun wiringIsActuallyPluggedIn() {
        val src = File("..", "app/src/main/kotlin/com/hualuo/repotool/ui/state/ViewerUiState.kt")
        assertTrue("找不到查看器状态文件：$src", src.isFile)
        val text = src.readText()
        assertTrue(
            "ViewerUiState 里没有 LangExtAliases.resolveId( —— 别名表建了却没接线",
            text.contains("LangExtAliases.resolveId("),
        )
    }
}