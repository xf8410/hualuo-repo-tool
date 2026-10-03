package com.hualuo.repotool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.engine.api.ModelRef
import com.hualuo.repotool.ui.model.IconKey
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.MODEL_UNPICKED
import com.hualuo.repotool.ui.state.ModelSettingsRuntime
import com.hualuo.repotool.ui.state.currentModelLabel
import com.hualuo.repotool.ui.state.modelDisplayName
import com.hualuo.repotool.ui.state.modelLabelWithProvider
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk

/**
 * 设置「模型」子页。版式照旧仓那张模型页改的（用户 10-03 拍板「模型功能按 Agora 的 UI 改」）：
 *
 *  1. **默认模型**一张卡：摆当前用的是哪个（名字 + 提供商），点开是一张选择框，
 *     逐条列已启用的模型，选中那条加粗——和旧仓那张「选择默认模型」对话框同款。
 *  2. **可用模型**：同步按钮单独一张卡（永远在最上面，和旧仓一致），
 *     底下按提供商分组，组头可展开收起（名字 + 条数 + 上下尖角），
 *     展开后逐条是 勾选框 + 显示名 + 别名输入（自加的模型另有删除）。
 *
 * 三条纪律：
 *  - 屏上显示名一律走 `modelDisplayName`（别名优先、剥掉 provider: 前缀），
 *    不许在这里直接摆整条 id —— 整条 id 会把卡片撑破（旧仓那排胶囊就是这么炸的）；
 *  - 没有可用模型时说人话，不摆空白；
 *  - 纵向滚动只有整页那一个（NestedScrollGateTest 红线），组内展开用普通列，不塞惰性列表。
 */
@Composable
fun ModelSettingsPanel(state: AppUiState) {
    val models = ModelSettingsRuntime.current()
    if (models == null) {
        // 空白是最难查的形态：以前这里直接 return，屏上一个字都没有。
        // 现在照实说一句原因（原因由应用层记，见 ModelSettingsRuntime.recordFailure）。
        Text(
            "模型清单还没挂上：${com.hualuo.repotool.ui.state.ModelSettingsRuntime.failureReason() ?: "还没初始化"}。重启应用一般就好；一直这样就是启动链出事了。",
            fontSize = 12.5.sp,
            color = ErrRed,
            modifier = Modifier.padding(6.dp),
        )
        return
    }
    val current = state.currentModel
    var pickerOpen by remember { mutableStateOf(false) }
    var aliasTarget by remember { mutableStateOf<String?>(null) }
    var customProviderId by remember(models.settings.providers) { mutableStateOf(models.settings.activeProviderId) }
    var customModelName by remember { mutableStateOf("") }
    var customAlias by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val caretUp = stringResource(IconKey.CaretUp.resId)
    val caretDown = stringResource(IconKey.CaretDown.resId)

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── 默认模型（旧仓第一张卡） ──
        SectionLabel("默认模型")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .clickable { if (models.selectedModels().isNotEmpty()) pickerOpen = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    currentModelLabel(state),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    providerLine(current, models.displayProviderName(ModelRef.parse(current).providerId)),
                    fontSize = 12.sp,
                    color = SubInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(ROW_CHEVRON, fontSize = 14.sp, color = com.hualuo.repotool.ui.theme.ChevGray)
        }
        Text(
            "聊天输入区那排胶囊用的就是它；发送时按这个 id 走对应提供商的地址与密钥。",
            fontSize = 11.5.sp,
            color = SubInk,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )

        // ── 可用模型：同步卡（旧仓永远在最上面那一条） ──
        SectionLabel("可用模型")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .clickable { models.refreshAll() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (models.busyProviderId == null) "从所有提供商同步" else "正在同步",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                )
                Text(
                    "把每家的模型清单拉回来；内置提供商要先在「提供商」页填好地址与密钥",
                    fontSize = 12.sp,
                    color = SubInk,
                )
            }
        }
        models.errors.forEach { (provider, error) ->
            Text("$provider：$error", fontSize = 12.sp, color = ErrRed, modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp))
        }

        val grouped = models.availableModels().groupBy { it.providerName }
        if (grouped.isEmpty()) {
            Text(
                "还没有模型可显示：先在「提供商」页配好一家并同步，或者在下面手动加一个。",
                fontSize = 12.5.sp,
                color = SubInk,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        grouped.forEach { (providerName, rows) ->
            val open = expanded[providerName] ?: false
            ProviderGroupHeader(
                providerName = providerName,
                count = rows.size,
                open = open,
                caret = if (open) caretUp else caretDown,
            ) { expanded[providerName] = !open }
            if (open) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 9.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBg),
                ) {
                    rows.forEach { model ->
                        ModelRow(
                            displayName = modelLabelWithProvider(model.id, model.alias, model.providerName),
                            rawName = modelDisplayName(model.id, model.alias),
                            custom = model.custom,
                            enabled = model.enabled,
                            isCurrent = model.id == current,
                            onToggle = { models.setModelEnabled(model.id, it) },
                            onPick = {
                                state.currentModel = model.id
                                state.toast("默认模型已切到 ${modelDisplayName(model.id, model.alias)}")
                            },
                            onRename = { aliasTarget = model.id },
                            onDelete = { models.deleteCustomModel(model.id)?.let(state::toast) },
                        )
                    }
                }
            }
        }

        // ── 手动加一个（旧仓同款：模型 id 手填，挂到选好的提供商名下） ──
        SectionLabel("手动添加模型")
        Text(
            "模型 id 手填，挂在下面选好的提供商名下；请求走这家的地址与密钥，同步不会洗掉它。",
            fontSize = 12.sp,
            color = SubInk,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        CustomModelProviderRow(models.settings.providers.map { it.id to models.displayProviderName(it.id) }, customProviderId) { customProviderId = it }
        SettingInput("模型 id", customModelName, "例如 gpt-4o-mini") { customModelName = it }
        SettingInput("别名，可空", customAlias, "留空用模型原名") { customAlias = it }
        ActionButton("加入这个模型") {
            val error = models.addCustomModel(customProviderId, customModelName, customAlias)
            if (error != null) {
                state.toast(error)
            } else {
                customModelName = ""
                customAlias = ""
                state.toast("自定义模型已加入并启用")
            }
        }
    }

    if (pickerOpen) {
        DefaultModelDialog(
            rows = models.selectedModels(),
            current = current,
            onPick = {
                state.currentModel = it
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    aliasTarget?.let { target ->
        ModelAliasDialog(
            initial = models.settings.aliases[target].orEmpty(),
            placeholder = modelDisplayName(target, null),
            onSave = {
                models.setAlias(target, it)
                aliasTarget = null
            },
            onDismiss = { aliasTarget = null },
        )
    }
}

/** 副标题那一句：没配提供商就说「尚未配置」，别拿空串凑一行。 */
private fun providerLine(modelId: String, providerDisplayName: String): String =
    if (providerDisplayName.isBlank()) "尚未配置提供商" else providerDisplayName

/** 组头：提供商名 + 条数 + 尖角，点一下展开收起（旧仓同款的可折叠分组）。 */
@Composable
private fun ProviderGroupHeader(providerName: String, count: Int, open: Boolean, caret: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, if (open) Accent else Hairline, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(providerName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.weight(1f))
        Text("$count 个", fontSize = 12.sp, color = SubInk)
        Text(caret, fontSize = 12.sp, color = SubInk, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 组里的一条模型：勾选框 + 显示名 + 别名行（自加的另有删除）。点一下名字即设为默认。 */
@Composable
private fun ModelRow(
    displayName: String,
    rawName: String,
    custom: Boolean,
    enabled: Boolean,
    isCurrent: Boolean,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Color(0xFFF0F5FF) else CardBg)
            .clickable(onClick = onPick)
            .padding(start = 4.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = enabled, onCheckedChange = onToggle, colors = CheckboxDefaults.colors(checkedColor = Accent))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                displayName + if (custom) "（自加）" else "",
                fontSize = 14.sp,
                color = Ink,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = rawName,
                    readOnly = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = SubInk),
                    modifier = Modifier.weight(1f),
                )
                Text("改名", fontSize = 11.sp, color = Accent, modifier = Modifier.clickable(onClick = onRename))
            }
            if (custom) {
                Text("删除", fontSize = 11.sp, color = ErrRed, modifier = Modifier.clickable(onClick = onDelete))
            }
        }
    }
}

/** 默认模型选择框（旧仓「选择默认模型」那张对话框的形状）。 */
@Composable
private fun DefaultModelDialog(
    rows: List<com.hualuo.repotool.ui.state.AvailableModel>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认模型", fontWeight = FontWeight.SemiBold) },
        text = {
            if (rows.isEmpty()) {
                Text("没有已启用的模型：去下面勾几个，或者先从提供商同步。", fontSize = 13.sp, color = SubInk)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { model ->
                        val name = modelLabelWithProvider(model.id, model.alias, model.providerName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(model.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = model.id == current, onClick = { onPick(model.id) })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    fontSize = 14.sp,
                                    fontWeight = if (model.id == current) FontWeight.SemiBold else FontWeight.Normal,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(model.providerName, fontSize = 12.sp, color = SubInk)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = Accent) } },
        containerColor = CardBg,
    )
}

/** 改名框（旧仓那张「重命名模型」：当前名 + 输入框 + 保存/取消）。 */
@Composable
private fun ModelAliasDialog(
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名模型", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("当前名：$placeholder", fontSize = 11.5.sp, color = SubInk)
                Spacer(Modifier.padding(top = 8.dp))
                if (draft.isEmpty()) Text(placeholder, fontSize = 13.sp, color = SubInk)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Accent) } },
        containerColor = CardBg,
    )
}

/** 行尾那个「点我」的小尖角：排版符（U+203A），不在源码禁用的图形号段里。 */
private const val ROW_CHEVRON = "›"

/** 占位引用，避免 MODEL_UNPICKED 未被引用时看不出这文件在管显示名（真用到的是 modelDisplayName）。 */
private val UNUSED_LABEL_HINT = MODEL_UNPICKED
