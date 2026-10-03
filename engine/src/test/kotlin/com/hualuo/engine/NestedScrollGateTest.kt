package com.hualuo.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纵向滚动嵌套闸门（2026-10-03 真机连崩八次立的红线之一）。
 *
 * 事故事实：手机上 hualuo-crash-20261003-104733 / 104950 两份现场同一指纹——
 * `IllegalStateException: Vertically scrollable component was measured with an infinity
 * maximum height constraints`。根因不是玄学，是层级：工具页 ToolsScreen 整页
 * `Column(verticalScroll)`，里面的查看器卡 ViewerCard 又自带一层
 * `fillMaxSize().verticalScroll()`，卡里还坐着两个 `LazyColumn`——纵向滚动容器套
 * 纵向滚动容器，内层必然拿到无限高约束，Android 16 当场崩，一分钟八次。
 * 本闸门上线第一轮就又逮住第二个现场：观测页 ObserveScreen 事件流那条 LazyColumn。
 *
 * 本闸门的口径（机器判，不采信任何说词）：
 *   - 同一个 .kt 文件里不许同时出现「纵向滚动容器」与「纵向惰性列表」；
 *   - 惰性列表两种写法都算：`LazyColumn(...)` 与尾随 lambda 的 `LazyColumn { ... }`；
 *   - 注释与字符串里的字样不算（先剥掉再匹配，免得注释里提一嘴就误伤）；
 *   - 横向滚动、横向惰性列表不在管辖内（横向滚进纵向滚是合法的）。
 *
 * 配套纪律：detectorBitesOnKnownBadSamples 是变异自测——闸门若不会咬已知坏样本，
 * 它对全仓的「通过」就一文不值。首轮自测就咬出了自己（只认 `LazyColumn(`，
 * 不认尾随 lambda 的 `LazyColumn {`），这正是自测存在的意义。
 */
class NestedScrollGateTest {

    @Test
    fun noFileMixesVerticalScrollWithVerticalLazyList() {
        val root = File("..", "app/src/main/kotlin")
        assertTrue("找不到源码目录：$root —— 目录被挪了要报错，不许静默跳过", root.isDirectory)
        val problems = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            scanned++
            val code = stripCommentsAndStrings(f.readText())
            violations(code).forEach { problems.add("${f.path.substringAfter("kotlin/")} $it") }
        }
        assertTrue("只扫到 $scanned 个 .kt（少于 20 说明文件被删了或路径错了，这也是事故）", scanned >= 20)
        assertTrue(
            "纵向滚动嵌套（共 ${problems.size} 处）：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /** 变异自测：坏样本必须被咬住，好样本（含注释里提一嘴）必须放行。 */
    @Test
    fun detectorBitesOnKnownBadSamples() {
        // 尾随 lambda 写法（首轮自测漏的就是它）
        expectProblem("@Composable fun A() { Column(Modifier.verticalScroll(s)) { LazyColumn { } } }")
        // 带参写法
        expectProblem("@Composable fun B() { Column(Modifier.verticalScroll(s)) { LazyColumn(mod) { } } }")
        // 空格变体
        expectProblem("@Composable fun C() { Column(Modifier . verticalScroll (s)) { LazyColumn { } } }")
        // 网格与瀑布流也算纵向惰性列表
        expectProblem("@Composable fun D() { Column(Modifier.verticalScroll(s)) { LazyVerticalGrid { } } }")
        expectProblem("@Composable fun E() { Column(Modifier.verticalScroll(s)) { LazyVerticalStaggeredGrid { } } }")
        // 纵向滚动里只放普通列：合法
        expectClean("@Composable fun F() { Column(Modifier.verticalScroll(s)) { Text(\"x\") } }")
        // 只有惰性列表、页面本身不滚：合法（ChatScreen 就是这样）
        expectClean("@Composable fun G() { LazyColumn { items(list) { } } }")
        // 横向滚 + 横向惰性列表：合法
        expectClean("@Composable fun H() { Row(Modifier.horizontalScroll(s)) { LazyRow { } } }")
        // 名字相近但不是列表：合法
        expectClean("@Composable fun I() { val st = rememberLazyListState()\n LazyColumn(state = st) { } }")
        // 注释里提到不算违规（剥掉后再判）
        expectClean(
            """
            // 这里以前放过 LazyColumn { }，后来拿掉了
            /* 块注释里也提一句 verticalScroll( */
            @Composable fun J() { Column(Modifier.verticalScroll(s)) { Text("x") } }
            """.trimIndent(),
        )
    }

    private fun expectProblem(src: String) {
        val hits = violations(stripCommentsAndStrings(src))
        assertTrue("闸门漏判：应报纵向滚动嵌套，实际=$hits", hits.isNotEmpty())
    }

    private fun expectClean(src: String) {
        val hits = violations(stripCommentsAndStrings(src))
        assertTrue("闸门误伤合法写法：$hits", hits.isEmpty())
    }

    internal fun violations(code: String): List<String> {
        val out = mutableListOf<String>()
        val scroll = Regex("""\.verticalScroll\s*\(""").findAll(code).count()
        val lazyVertical = Regex("""\b(LazyColumn|LazyVerticalGrid|LazyVerticalStaggeredGrid)\s*[({]""")
            .findAll(code).count()
        if (scroll > 0 && lazyVertical > 0) {
            out.add(
                "同一文件里既有 verticalScroll( 又有纵向惰性列表（$lazyVertical 处）：" +
                    "纵向滚动容器被外层以无限高约束测量就会崩（2026-10-03 真机现场），" +
                    "整页只留一个纵向滚动容器，纵向列表改成分段",
            )
        }
        return out
    }

    /**
     * 剥掉注释与字符串字面量再匹配：注释里提一嘴 LazyColumn 不算代码，
     * 字符串里的 URL 也不该被当成滚动容器。原生三引号串单独一个模式。
     */
    internal fun stripCommentsAndStrings(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var mode = 'C'
        while (i < src.length) {
            val c = src[i]
            when (mode) {
                'L' -> {
                    if (c == '\n') {
                        mode = 'C'
                        sb.append(c)
                    } else {
                        sb.append(' ')
                    }
                    i++
                }
                'B' -> {
                    when {
                        src.startsWith("*/", i) -> {
                            mode = 'C'
                            sb.append("  ")
                            i += 2
                        }
                        else -> {
                            sb.append(if (c == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                'S' -> {
                    when {
                        c == '\\' -> {
                            sb.append("  ")
                            i += 2
                        }
                        c == '"' -> {
                            mode = 'C'
                            sb.append(' ')
                            i++
                        }
                        else -> {
                            sb.append(if (c == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                'R' -> {
                    when {
                        src.startsWith("\"\"\"", i) -> {
                            mode = 'C'
                            sb.append("   ")
                            i += 3
                        }
                        else -> {
                            sb.append(if (c == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                else -> {
                    when {
                        src.startsWith("//", i) -> {
                            mode = 'L'
                            sb.append("  ")
                            i += 2
                        }
                        src.startsWith("/*", i) -> {
                            mode = 'B'
                            sb.append("  ")
                            i += 2
                        }
                        src.startsWith("\"\"\"", i) -> {
                            mode = 'R'
                            sb.append("   ")
                            i += 3
                        }
                        c == '"' -> {
                            mode = 'S'
                            sb.append(' ')
                            i++
                        }
                        else -> {
                            sb.append(c)
                            i++
                        }
                    }
                }
            }
        }
        return sb.toString()
    }
}