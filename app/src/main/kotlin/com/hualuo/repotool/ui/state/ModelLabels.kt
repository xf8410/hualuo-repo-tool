package com.hualuo.repotool.ui.state

import com.hualuo.engine.api.ModelRef

/**
 * 模型在屏上叫什么名字。纯函数收口 + 一处现场读设置，界面层只管画。
 *
 * 为什么单独成件：模型 id 长这样 `openrouter:stealth/space-bunny-alpha`。
 * 以前输入区那排胶囊直接把它整条塞进去，于是胶囊吃掉整行宽度，
 * 发送钮被挤出屏幕点不到 —— 2026-10-03 机主实报「对话模型按钮没有缩写，
 * 导致发送按键没办法用了」。收口规矩写在这里，调用点不许各写一套。
 *
 * 三条口径（照旧仓那排胶囊的形状：别名优先，其次模型原名，再缀提供商）：
 *  1. 有别名就叫别名（人自己起的名字最要紧）；
 *  2. 没别名就叫模型原名，**剥掉 provider: 前缀**与 models/ 前缀（那两段是路由用的，不是名字）；
 *  3. 还要缩：超过上限就截断加省略号，界面上再做一次 ellipsis，双保险。
 */

/** 一个模型都没选时屏上摆这句，不许摆空白格（空白会被当成没这回事）。 */
const val MODEL_UNPICKED = "未选择"

/** 输入区那排胶囊里的上限（旧仓同款：胶囊宽有上限，名字放不下就缩）。 */
const val MODEL_CHIP_MAX_CHARS = 18

/** 切换列表与设置卡片里的上限（比胶囊宽，少缩一点）。 */
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
 * 带提供商的一句：`名 (提供商)`。提供商为空就只写名
 * （自加模型挂的提供商名可能取不到，硬凑一个括号只会更难读）。
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

/** 别名与提供商名从模型设置里现场取；设置句柄没挂上就两个都给空（不许因此崩，界面照常画）。 */
private fun aliasAndProvider(modelId: String): Pair<String?, String?> {
    val models = ModelSettingsRuntime.current() ?: return null to null
    val alias = models.settings.aliases[modelId]
    val providerId = ModelRef.parse(modelId).providerId
    val providerName = if (providerId.isBlank()) null else models.displayProviderName(providerId)
    return alias to providerName
}

/**
 * 当前模型在设置卡片与切换列表里的那一句（不缩，留给有横向空间的地方）。
 *
 * 「已启用清单里没有它」这件事只在**清单非空**时才改口成「未选择」：
 * 清单空着的时候说「未选择」是撒谎（屏上明明摆着一个 id，而且发送照它走），
 * 那种场合照实摆名字，让「去勾模型」的话由设置页自己说。
 */
fun currentModelLabel(state: AppUiState): String {
    val (alias, provider) = aliasAndProvider(state.currentModel)
    if (isUnpicked(state)) return MODEL_UNPICKED
    return modelLabelWithProvider(state.currentModel, alias, provider)
}

/** 当前模型在输入区胶囊上的那一句：按 [MODEL_CHIP_MAX_CHARS] 截断，胶囊宽度因此有上限。 */
fun currentModelChipText(state: AppUiState): String {
    val (alias, provider) = aliasAndProvider(state.currentModel)
    if (isUnpicked(state)) return MODEL_UNPICKED
    return modelChipText(state.currentModel, alias, provider, MODEL_CHIP_MAX_CHARS)
}

/** 清单非空、而当前这个不在里面 = 摆着一个用不了的模型名，那才是真话。 */
private fun isUnpicked(state: AppUiState): Boolean {
    val models = ModelSettingsRuntime.current() ?: return false
    val enabled = models.selectedModels()
    return enabled.isNotEmpty() && enabled.none { it.id == state.currentModel }
}
