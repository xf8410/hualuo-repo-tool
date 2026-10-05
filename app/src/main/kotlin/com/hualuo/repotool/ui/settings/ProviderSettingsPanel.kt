package com.hualuo.repotool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.engine.api.ProviderCatalog
import com.hualuo.repotool.ui.model.IconKey
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ModelSettingsRuntime
import com.hualuo.repotool.ui.state.ModelSettingsState
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.Bg
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ChevGray
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk

/**
 * 设置「提供商」子页，Agora 式两级结构（2026-10-05 用户拍板「模型和供应商以 Agora 为准」）：
 *
 *  1. **列表页**：每家一张卡（云图标 + 名称 + 地址 + 已配置/未配置 + 右箭头），
 *     点卡片进详情；列表底部一张「添加提供商」大卡（加号图标，Agora 同款）。
 *  2. **详情页**：这家的基础 URL 与 API 密钥直接可改（改完即存），
 *     「从这家拉模型」把模型清单拉回来；自定义的还有删除。
 *
 * 为什么从「全平铺一页」改两级：平铺时列表、添加表单、选中家的三个输入框全摞在
 * 一屏，新用户看不懂「先配哪家」（用户原话：先输入 key 再去模型选，这样才清晰）。
 * 两级之后每屏只说一件事，列表挑家、详情配钥匙。
 *
 * 功能一件没少：添加自定义、改地址密钥、拉模型、删自定义，全在（只是换了住处）。
 * 图标字形住 icons.xml（家规），这里只出现键名。
 */
@Composable
fun ProviderSettingsPanel(state: AppUiState) {
    val models = ModelSettingsRuntime.current() ?: return
    var detailId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    val current = detailId
    when {
        current != null -> ProviderDetail(state, models, current) { detailId = null }
        adding -> ProviderAddForm(state, models) { adding = false; detailId = it }
        else -> ProviderList(
            models,
            onOpen = {
                detailId = it
                // 点开谁就把「当前这家」切到谁：跟原平铺版点行选中的语义一致
                models.selectProvider(it)
            },
            onAdd = { adding = true },
        )
    }
}

// ---------- 列表页 ----------

@Composable
private fun ProviderList(
    models: ModelSettingsState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val builtin = models.settings.providers.filter { !it.custom }
        val custom = models.settings.providers.filter { it.custom }
        if (builtin.isNotEmpty()) {
            SectionLabel("内置提供商")
            builtin.forEach { provider ->
                ProviderCard(
                    name = models.displayProviderName(provider.id),
                    detail = provider.baseUrl.ifBlank { ProviderCatalog.byId(provider.id)?.defaultBaseUrl.orEmpty() },
                    configured = models.isConfigured(provider.id),
                    onOpen = { onOpen(provider.id) },
                )
            }
        }
        if (custom.isNotEmpty()) {
            SectionLabel("自定义提供商")
            custom.forEach { provider ->
                ProviderCard(
                    name = provider.id,
                    detail = provider.baseUrl,
                    configured = models.isConfigured(provider.id),
                    onOpen = { onOpen(provider.id) },
                )
            }
        }
        if (builtin.isEmpty() && custom.isEmpty()) {
            Text(
                "一家提供商都没有：先在下面添加一家，再去「模型」页选模型。",
                fontSize = 12.5.sp,
                color = SubInk,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }

        // 添加提供商大卡（Agora 截图同款：加号 + 一句话，占满整行）
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Bg)
                .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                .clickable(onClick = onAdd)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFEAF1FF)),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(IconKey.Plus.resId), fontSize = 17.sp, color = Ink) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("添加提供商", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text("名字 + 地址 + 密钥，加完直接进详情页", fontSize = 12.sp, color = SubInk)
            }
        }
        Text(
            "配好一家后去「模型」页同步清单：先有钥匙，才拉得动模型（这个顺序不是多此一举，是让你看得清每家配没配）。",
            fontSize = 11.5.sp,
            color = SubInk,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

/** 列表里的一家：云图标 + 名称 + 地址 + 状态 + 右箭头（Agora 截图的行形状）。 */
@Composable
private fun ProviderCard(name: String, detail: String, configured: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFEAF1FF)),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(IconKey.SettingsProvider.resId), fontSize = 16.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (detail.isBlank()) "还没填地址" else detail,
                fontSize = 11.5.sp,
                color = SubInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(if (configured) "已配置" else "未配置", fontSize = 11.sp, color = if (configured) Accent else SubInk)
        Text(stringResource(IconKey.Chevron.resId), fontSize = 14.sp, color = ChevGray, modifier = Modifier.padding(start = 6.dp))
    }
}

// ---------- 添加表单 ----------

@Composable
private fun ProviderAddForm(
    state: AppUiState,
    models: ModelSettingsState,
    onDone: (String?) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newBase by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("添加自定义提供商")
        Text(
            "起个名字、填上 OpenAI 兼容端点的地址和密钥（本地端点密钥可留空）。",
            fontSize = 12.sp,
            color = SubInk,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        SettingInput("名称", newName, "例如：bai2 网关") { newName = it }
        SettingInput("base URL", newBase, "https://example.com/v1") { newBase = it }
        SettingInput("API 密钥", newKey, "可以留空", secret = true) { newKey = it }
        Row {
            ActionButton("添加提供商") {
                val error = models.addCustomProvider(newName, newBase, newKey)
                if (error != null) {
                    state.toast(error)
                } else {
                    val id = newName.trim()
                    newName = ""; newBase = ""; newKey = ""
                    state.toast("自定义提供商已加入")
                    onDone(id)
                }
            }
            Spacer(Modifier.width(8.dp))
            ActionButton("返回列表") { onDone(null) }
        }
    }
}

// ---------- 详情页 ----------

@Composable
private fun ProviderDetail(
    state: AppUiState,
    models: ModelSettingsState,
    providerId: String,
    onBack: () -> Unit,
) {
    val provider = models.settings.provider(providerId)
    if (provider == null) {
        // 上一屏还在时这家被删了（详情页删完自己）：直接回列表，不摆死屏
        onBack()
        return
    }
    val definition = ProviderCatalog.byId(providerId)
    var baseDraft by remember(providerId) { mutableStateOf(provider.baseUrl) }
    var keyDraft by remember(providerId) { mutableStateOf(provider.apiKey) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 头部：返回列表钮 + 这家的名字 + 状态（Agora 详情页顶部同款信息）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(IconKey.Back.resId), fontSize = 15.sp, color = Accent)
            Spacer(Modifier.width(8.dp))
            Text("提供商列表", fontSize = 13.sp, color = Accent)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                models.displayProviderName(providerId),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (models.isConfigured(providerId)) "已配置" else "未配置", fontSize = 11.sp, color = if (models.isConfigured(providerId)) Accent else SubInk)
        }
        Text(
            if (definition == null) "OpenAI 兼容端点" else "协议：${definition.protocol.name}",
            fontSize = 12.sp,
            color = SubInk,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )

        SettingInput("base URL", baseDraft, definition?.defaultBaseUrl.orEmpty()) { baseDraft = it; models.configureProvider(providerId, it, keyDraft) }
        SettingInput("API 密钥", keyDraft, if (definition?.keyRequired == true) "必填" else "本地端点可以留空", secret = true) { keyDraft = it; models.configureProvider(providerId, baseDraft, it) }
        Text(
            "改动即时保存（不另设保存按钮）；密钥只存本机，只进请求头。",
            fontSize = 11.5.sp,
            color = SubInk,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )

        SectionLabel("模型清单")
        ActionButton(if (models.busyProviderId == providerId) "正在拉模型" else "从这家拉模型") { models.refreshProvider(providerId) }
        models.errors[providerId]?.let {
            Text(it, fontSize = 12.sp, color = ErrRed, modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp))
        }
        Text(
            "拉回来之后去「模型」页勾选启用；「从所有提供商同步」也会把这家带上。",
            fontSize = 11.5.sp,
            color = SubInk,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )

        if (provider.custom) {
            SectionLabel("危险操作")
            ActionButton("删除这家自定义提供商") {
                models.deleteCustomProvider(providerId)?.let(state::toast)
            }
        }
    }
}
