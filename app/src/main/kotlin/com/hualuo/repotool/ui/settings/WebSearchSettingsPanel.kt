package com.hualuo.repotool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.engine.search.SearchProviderInfo
import com.hualuo.engine.search.SearchProviders
import com.hualuo.repotool.ui.components.SliderRow
import com.hualuo.repotool.ui.components.SwitchPill
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ChevGray
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk

/**
 * 设置「网页搜索」子页：开关、挑一家、那家的密钥、自托管实例地址、每次几条。
 *
 * 版式照旧仓那张设置页（一行「搜索提供商 ›」点开选择框，选中那家加粗），
 * 行为按本仓纪律收紧：
 *  - 要不要密钥框、要不要实例地址框，由事实表说话（[SearchProviderInfo.needsKey] /
 *    [SearchProviderInfo.usesBaseUrl]），不写死「全摆出来再让人自己看哪格有用」；
 *  - 缺密钥当场说红字（能不能搜一眼看见），不去等搜一次才报；
 *  - 换一家**不动**别家的密钥格（各家钥匙分别存，见 [com.hualuo.repotool.ui.state.UiKeys.webSearchKey]）。
 */
@Composable
fun WebSearchSettingsPanel(state: AppUiState) {
    val search = state.webSearch
    var providerDialog by remember { mutableStateOf(false) }
    val provider = search.provider

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .clickable { state.webSearchOn = !state.webSearchOn }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("让 AI 上网查资料", fontSize = 14.sp, color = Ink)
                Text(
                    "关掉后对话里的网页搜索与取网页两件工具从清单里消失",
                    fontSize = 12.sp,
                    color = SubInk,
                )
            }
            SwitchPill(state.webSearchOn) { state.webSearchOn = !state.webSearchOn }
        }

        if (!state.webSearchOn) return@Column

        SectionLabel("搜索提供商")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .clickable { providerDialog = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(provider.desc, fontSize = 12.sp, color = SubInk)
            }
            Text(ROW_CHEVRON, fontSize = 14.sp, color = ChevGray)
        }

        if (search.needsKey) {
            SectionLabel("${provider.name} 密钥")
            SettingInput(
                label = "API 密钥",
                value = search.apiKey(),
                placeholder = if (provider.id == SearchProviders.BRAVE) "Brave Search API 的订阅令牌" else "这家官网给的密钥",
                secret = true,
            ) { search.setApiKey(it) }
            if (search.apiKey().isEmpty()) {
                Text(
                    "${provider.name} 没密钥就一定搜不了：填了才会真的去发请求",
                    fontSize = 12.sp,
                    color = ErrRed,
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                )
            }
        }

        if (search.usesBaseUrl) {
            SectionLabel("自托管实例地址")
            SettingInput(
                label = "实例地址",
                value = search.baseUrl(),
                placeholder = SearchProviders.DEFAULT_SEARXNG_BASE,
            ) { search.setBaseUrl(it) }
            Text(
                "留空就走公共实例；公共实例经常限流，自建更稳。",
                fontSize = 12.sp,
                color = SubInk,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }

        SectionLabel("每次搜几条")
        SliderRow(
            label = "返回条数",
            min = SearchProviders.MIN_RESULTS.toFloat(),
            max = SearchProviders.MAX_RESULTS.toFloat(),
            value = search.numResults().toFloat(),
        ) { search.setNumResults(it.toInt()) }

        Text(
            search.statusLine(),
            fontSize = 12.sp,
            color = SubInk,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
        Text(
            "密钥只进请求头，不进报错与界面；工具页那一次搜索与对话里的网页搜索工具共用这一份配置。",
            fontSize = 12.sp,
            color = SubInk,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
    }

    if (providerDialog) {
        ProviderPickerDialog(
            current = provider,
            onPick = {
                search.setProvider(it.id)
                providerDialog = false
            },
            onDismiss = { providerDialog = false },
        )
    }
}

/** 选择框：五家逐条列出，选中那家加粗（旧仓那张对话框的形状，行为照抄）。 */
@Composable
private fun ProviderPickerDialog(
    current: SearchProviderInfo,
    onPick: (SearchProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择搜索提供商", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SearchProviders.ALL.forEach { info ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(info) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = info.id == current.id, onClick = { onPick(info) })
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                info.name,
                                fontSize = 14.sp,
                                fontWeight = if (info.id == current.id) FontWeight.SemiBold else FontWeight.Normal,
                                color = Ink,
                            )
                            Text(info.desc, fontSize = 12.sp, color = SubInk)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = Accent) } },
        containerColor = CardBg,
    )
}

/** 行尾那个「点我」的小尖角：排版符（U+203A），不在源码禁用的图形号段里。 */
private const val ROW_CHEVRON = "›"