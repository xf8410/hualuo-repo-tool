package com.hualuo.repotool.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型屏上显示名的契约（纯 JVM，不碰安卓、不碰网）。
 *
 * 这条红线来自 2026-10-03 真机实报：**「对话模型按钮没有缩写，导致发送按键没办法用了」**。
 * 病根是输入区那排胶囊把整条模型 id（`openrouter:stealth/space-bunny-alpha`）摆进去，
 * 胶囊无限宽 → 后面的发送钮被挤出屏幕，点不到。收口规矩写在 ModelLabels.kt，这里钉住它：
 *
 *  1. 有别名就叫别名（人自己起的名字最要紧）；
 *  2. 没别名就叫模型原名，并剥掉 `provider:` 与 `models/` 前缀（那两段是路由用的，不是名字）；
 *  3. 带提供商时是 `名 (提供商)`，提供商为空就只写名；
 *  4. 超上限必须截断加省略号，且**截断后的长度不超过上限**——
 *     这是「发送钮留在屏上」的那一半：胶囊文字有上限，界面再给胶囊宽度上限，两条配套。
 */
class ModelLabelsTest {

    @Test
    fun aliasWinsOverTheRawId() {
        assertEquals("我的兔子", modelDisplayName("openrouter:stealth/space-bunny-alpha", "我的兔子"))
        assertEquals("去空白后的别名", modelDisplayName("openrouter:x/y", "  去空白后的别名  "))
    }

    @Test
    fun providerAndModelsPrefixesAreStripped() {
        assertEquals("stealth/space-bunny-alpha", modelDisplayName("openrouter:stealth/space-bunny-alpha", null))
        assertEquals("gpt-4o-mini", modelDisplayName("openai:gpt-4o-mini", null))
        assertEquals("gpt-4o-mini", modelDisplayName("models/gpt-4o-mini", null))
        assertEquals("没带前缀的裸名原样", modelDisplayName("qwen3.8-flash", null))
    }

    @Test
    fun emptyIdSaysUnpickedInsteadOfBlank() {
        assertEquals(MODEL_UNPICKED, modelDisplayName("", null))
        assertEquals(MODEL_UNPICKED, modelDisplayName("   ", "  "))
    }

    @Test
    fun providerIsAppendedOnlyWhenThereIsOne() {
        assertEquals(
            "stealth/space-bunny-alpha (Open Router)",
            modelLabelWithProvider("openrouter:stealth/space-bunny-alpha", null, "Open Router"),
        )
        assertEquals(
            "没有提供商就只写名",
            modelLabelWithProvider("openrouter:stealth/space-bunny-alpha", null, ""),
        )
        assertEquals(
            "别名已经够清楚了",
            modelLabelWithProvider("openrouter:stealth/space-bunny-alpha", "兔子", "Open Router"),
        )
    }

    @Test
    fun longNamesAreTruncatedSoTheSendButtonStaysOnScreen() {
        val full = modelChipText("openrouter:stealth/space-bunny-alpha", null, "Open Router", MODEL_CHIP_MAX_CHARS)
        assertTrue("截断后必须短于上限：$full（${full.length}）", full.length <= MODEL_CHIP_MAX_CHARS)
        assertTrue("该看出被截过：$full", full.endsWith("…"))
        assertEquals(
            "短名不该被动过",
            "gpt-4o-mini",
            abbreviateModelLabel("gpt-4o-mini", MODEL_CHIP_MAX_CHARS),
        )
    }

    @Test
    fun sillyMaxCharsDoNotProduceGarbage() {
        assertEquals("abc", abbreviateModelLabel("abc", 1))
        assertEquals("abc", abbreviateModelLabel("abc", 0))
        assertEquals("abc", abbreviateModelLabel("abc", -5))
    }

    @Test
    fun chipTextUsesTheTighterLimitThanRows() {
        assertTrue("胶囊上限必须比列表紧", MODEL_CHIP_MAX_CHARS < MODEL_ROW_MAX_CHARS)
        val chip = modelChipText("openrouter:stealth/space-bunny-alpha", null, "Open Router", MODEL_CHIP_MAX_CHARS)
        val row = modelChipText("openrouter:stealth/space-bunny-alpha", null, "Open Router", MODEL_ROW_MAX_CHARS)
        assertTrue("同一串名字，胶囊那句必须更短：'$chip' 对 '$row'", chip.length < row.length)
    }
}
