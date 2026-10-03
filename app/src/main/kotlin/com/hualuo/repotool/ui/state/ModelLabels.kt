package com.hualuo.repotool.ui.state

import com.hualuo.engine.api.ModelRef

/**
 * 模型在屏上叫什么名字（纯函数，界面层与纯 JVM 测试共用这一份）。
 *
 * 为什么单独成件：模型 id 长这样 `openrouter:stealth/space-bunny-alpha`，
 * 直接摆进输入区那排胶囊会把整行撑爆——2026-10-03 机主实报「对话模型按钮没有缩写，
 * 导致发送按键没办法用了」：发送钮被挤出屏幕，点不到。收口规矩写在这里，
 * 界面只负责画，规矩不许散到各个调用点。
 *
 * 三条口径（照旧仓那排胶囊的形状：别名优先，其次模型原名，再缀提供商）：
 *  1. 有别名就叫别名（人自己起的名字最要紧）；
 *  2. 没别名就叫模型原名，**剥掉 provider: 前缀**与 models/ 前缀（那两段是路由用的，不是名字）；
 *  3. 还要缩：超过上限就截断加省略号，界面上再做一次 ellipsis，双保险。
 */

/** 一个模型都没选时屏上摆这句（不许摆空白格，空白会被当成没这回事）。 */
const val MODEL_UNPICKED = "未选择"

/** 输入区那排胶囊里的上限（Agora 同款：胶囊宽上限 160dp 上下，名字放不下就缩）。 */
const val MODEL_CHIP_MAX_CHARS = 18

/** 切换列表里的上限（列表比胶囊宽，少缩一点）。 */
const val MODEL_ROW_MAX_CHARS = 30

/** 屏上显示名：别名优先，其次模型原名（剥掉 provider: 与 models/ 前缀），再退回整条 id。 */
fun modelDisplayName(modelId: String, alias: String?): String {
    val cleanAlias = alias?.trim().orEmpty()
    if (cleanAlias.isNotEmpty()) return cleanAlias
    val clean = modelId.trim()
    if (clean.isEmpty()) return MODEL_UNPICKED
    val parsed = ModelRef.parse(clean)
    return parsed.model.ifBlank { clean }
}

/**
 * 带提供商的一句：`名 (提供商)`。提供商为空就只写名（自加模型挂的提供商名可能是空的，
 * 那时硬凑一个括号只会更难读）。
 */
fun modelLabelWithProvider(modelId: String, alias: String?, providerName: String?): String {
    val name = modelDisplayName(modelId, alias)
    val provider = providerName?.trim().orEmpty()
    return if (provider.isEmpty()) name else "$name ($provider)"
}

/** 截断加省略号。上限小于等于 1 时原样返回（不做负数切片那种蠢事）。 */
fun abbreviateModelLabel(label: String, maxChars: Int): String {
    val clean = label.trim()
    if (maxChars <= 1 || clean.length <= maxChars) return clean
    return clean.take(maxChars - 1) + "…"
}

/** 胶囊与列表直接用的一句：带提供商 + 按上限截断。 */
fun modelChipText(modelId: String, alias: String?, providerName: String?, maxChars: Int): String =
    abbreviateModelLabel(modelLabelWithProvider(modelId, alias, providerName), maxChars)
