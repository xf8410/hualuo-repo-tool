package com.hualuo.engine.language

/**
 * 扩展名长名别名表（2026-10-03 随语言高亮批次一起补）。
 *
 * 事故事实：批次 run 37095507508 五条里三条红，当场逮住
 * 「扩展名 .csharp 认错语言了：期望 C#，实际 纯文本」——
 * 短名 cs / js / ts / tsx 都在 [LangRegistry] 的表里，长名 csharp /
 * javascript / typescript 没登记，按长后缀命名的文件一律掉进纯文本兜底，
 * 等于没高亮。
 *
 * 为什么单独一张表而不是直接改 EXT_TABLE：那张表与关键词组在一个 19KB 文件里，
 * 改它要整文件重写，手抄风险太高（本轮就撞过一次工具侧的大文件写入失败）。
 * 这张表小、可单测、纯数据，改错了也只是这三条别名。
 *
 * 口径：查表前先 trim、转小写、去点号（与 LangRegistry.byExtension 同口径）。
 */
object LangExtAliases {

    private val alias: Map<String, String> = mapOf(
        "csharp" to "csharp",
        "javascript" to "javascript",
        "typescript" to "typescript",
        "cplusplus" to "cpp",
        "py" to "python",
    )

    /** 别名表登记的全部扩展名（给测试与设置页对账用）。 */
    fun all(): Map<String, String> = alias

    /**
     * 把扩展名归一到语言 id：命中别名给对应语言 id，没命中给 null
     * （null=不在别名表里，请照常走 [LangRegistry.byExtension]）。
     */
    fun resolveId(extRaw: String): String? = alias[extRaw.trim().lowercase().trimStart('.')]
}