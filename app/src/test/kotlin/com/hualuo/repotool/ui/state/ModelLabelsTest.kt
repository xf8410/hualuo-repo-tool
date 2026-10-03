package com.hualuo.repotool.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型屏上显示名的契约（纯 JVM，不碰安卓、不碰网）。
 *
 * 这条红线来自 2026-10-03 真机实报：**「对话模型按钮没有缩写，导致发送按键没办法用了」**。
 * 病根是输入区那排胶囊把整条模型 id（`openrouter:stealth/space-bunny-alpha`）摆进去，
 * 胶囊无限宽，后面的发送钮被挤出屏幕，点不到。收口规矩写在 ModelLabels.kt，这里钉住它：
 *
 *  1. 有别名就叫别名（人自己起的名字最要紧）；
 *  2. 没别名就叫模型原名，并剥掉 `provider:` 与 `models/` 前缀（那两段是路由用的，不是名字）；
 *  3. 带提供商时是 `名 (提供商)`，提供商为空就只写名；
 *  4. 超上限必须截断加省略号，且**截断后的长度不超过上限**——
 *     这是「发送钮留在屏上」的那一半：胶囊文字有上限，界面再给胶囊宽度上限，两条配套。
 *
 * 断言一律三参（消息在前，JUnit4 的参数顺序是 message, expected, actual），
 * 红了能直接看出是哪一条口径不对。
 */
class ModelLabelsTest {

    @Test
    fun aliasWinsOverTheRawId() {
        assertEquals("别名优先", "我的兔子", modelDisplayName("openrouter:stealth/space-bunny-alpha", "我的兔子"))
        assertEquals("别名去空白", "去空白后的别名", modelDisplayName("openrouter:x/y", "  去空白后的别名  "))
    }

    @Test
    fun providerAndModelsPrefixesAreStripped() {
        assertEquals("剥 provider 前缀", "stealth/space-bunny-alpha", modelDisplayName("openrouter:stealth/space-bunny-alpha", null))
        assertEquals("剥 provider 前缀", "gpt-4o-mini", modelDisplayName("openai:gpt-4o-mini", null))
        assertEquals("剥 models 前缀", "gpt-4o-mini", modelDisplayName("models/gpt-4o-mini", null))
        assertEquals("裸名原样", "qwen3.8-flash", modelDisplayName("qwen3.8-flash", null))
    }

    @Test
    fun emptyIdSaysUnpickedInsteadOfBlank() {
        assertEquals("空串不许摆空白", MODEL_UNPICKED, modelDisplayName("", null))
        assertEquals("全空白同上", MODEL_UNPICKED, modelDisplayName("   ", "  "))
    }

    @Test
    fun providerIsAppendedOnlyWhenThereIsOne() {
        assertEquals(
            "带提供商",
            "stealth/space-bunny-alpha (Open Router)",
            modelLabelWithProvider("openrouter:stealth/space-bunny-alpha", null, "Open Router"),
        )
        assertEquals(
            "提供商为空就只写名",
            "stealth/space-bunny-alpha",
            modelLabelWithProvider("openrouter:stealth/space-bunny-alpha", null, ""),
        )
        assertEquals(
            "别名已经够清楚",
            "兔子 (Open Router)",
            modelLabelWithProvider("openrouter:stealth/space-bunny-alpha", "兔子", "Open Router"),
        )
    }

    @Test
    fun longNamesAreTruncatedSoTheSendButtonStaysOnScreen() {
        val full = modelChipText("openrouter:stealth/space-bunny-alpha", null, "Open Router", MODEL_CHIP_MAX_CHARS)
        assertTrue("截断后必须短于上限：$full", full.length <= MODEL_CHIP_MAX_CHARS)
        assertTrue("该看出被截过：$full", full.endsWith("…"))
    }

    @Test
    fun shortNamesAreLeftAlone() {
        assertEquals("短名不该被动过", "gpt-4o-mini", abbreviateModelLabel("gpt-4o-mini", MODEL_CHIP_MAX_CHARS))
        assertEquals("刚好等于上限也不动", "123456789012345678", abbreviateModelLabel("123456789012345678", 18))
    }

    @Test
    fun sillyMaxCharsDoNotProduceGarbage() {
        assertEquals("上限 1 不做切片", "abc", abbreviateModelLabel("abc", 1))
        assertEquals("上限 0 不做切片", "abc", abbreviateModelLabel("abc", 0))
        assertEquals("负上限不做切片", "abc", abbreviateModelLabel("abc", -5))
    }

    @Test
    fun chipTextUsesTheTighterLimitThanRows() {
        val chip = modelChipText("openrouter:stealth/space-bunny-alpha", null, "Open Router", MODEL_CHIP_MAX_CHARS)
        val row = modelChipText("openrouter:stealth/space-bunny-alpha", null, "Open Router", MODEL_ROW_MAX_CHARS)
        assertTrue("胶囊上限必须比列表紧", MODEL_CHIP_MAX_CHARS < MODEL_ROW_MAX_CHARS)
        assertTrue("同一串名字，胶囊那句必须更短：'$chip' 对 '$row'", chip.length < row.length)
    }
}
