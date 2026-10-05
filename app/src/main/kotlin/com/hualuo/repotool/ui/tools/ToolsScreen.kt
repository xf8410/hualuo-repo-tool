package com.hualuo.repotool.ui.tools

import com.hualuo.repotool.ui.viewer.ViewerCard
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.components.CardTitle
import com.hualuo.repotool.ui.components.Dot
import com.hualuo.repotool.ui.components.HCard
import com.hualuo.repotool.ui.model.Tone
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ToolCenterState
import com.hualuo.repotool.ui.state.ToolRegistryRuntime
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.Bg
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk
import com.hualuo.repotool.ui.theme.WarnAmber

/**
 * 工具页（v13 #p-tool）。
 *
 * **网页搜索已接真电（0.7.0，2026-10-03 补齐五家提供商）**：用哪家、密钥、实例地址、条数
 * 都在设置「网页搜索」里，卡片标题现报当前那家（换一家立刻看得见）；输入、按钮、结果列表
 * 都是真数据，失败出声不冒充（引擎件说「没有搜到结果」就是没有，屏上照实摆失败理由，不装成功）。
 * 点一条结果用系统浏览器打开（设备上没有能接的 App 就出声，不静默）。
 * **真账卡（2026-10-05 修摆设刀）**：底下的工具清单卡不再读演示数据——直接问
 * [ToolRegistryRuntime] 里装配好的真实注册表，报「注册了几件、此刻开着几件、
 * 关着哪几件」。任何族接进来多出几行、闸门没接哪件就不在名单里，全在这张卡上现形——
 * 治的就是「界面上写着能调、实际没有」的老病。
 */
@Composable
fun ToolsScreen(state: AppUiState) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        VideoUnderstandingCard(state)

        Spacer(Modifier.height(10.dp))

        ViewerCard(state)

        Spacer(Modifier.height(10.dp))

        ApkCheckCard(state)

        Spacer(Modifier.height(10.dp))

        HCard {
            CardTitle("网页搜索（当前：${state.webSearch.providerLabel()}）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (state.searchQuery.isEmpty()) {
                        Text("要搜什么…", fontSize = 13.sp, color = SubInk)
                    }
                    BasicTextField(
                        value = state.searchQuery,
                        onValueChange = { state.searchQuery = it },
                        textStyle = TextStyle(fontSize = 13.sp, color = Ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (state.searchBusy) SubInk else Accent)
                        .clickable(enabled = !state.searchBusy) { state.runWebSearch() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (state.searchBusy) "搜着…" else "搜一下",
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            state.searchNote?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(note, fontSize = 12.sp, color = if (state.searchResults.isEmpty()) WarnAmber else SubInk)
            }
            state.searchResults.forEach { result ->
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                            }.onFailure {
                                state.toast("打不开这个链接（设备上没有能接的浏览器？）")
                            }
                        },
                ) {
                    Text(result.title, fontSize = 13.5.sp, color = Accent, fontWeight = FontWeight.Medium)
                    Text(result.url, fontSize = 10.5.sp, color = SubInk)
                    if (result.snippet.isNotEmpty()) {
                        Text(result.snippet, fontSize = 12.sp, color = Ink)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "换一家搜索、去设置「网页搜索」",
                fontSize = 11.5.sp,
                color = SubInk,
                modifier = Modifier.clickable { state.openSettings("websearch") },
            )
        }

        Spacer(Modifier.height(10.dp))

        ToolLedgerCard()
    }
}

/**
 * 真账卡（2026-10-05 修摆设刀）：这件应用**真实注册**的工具一件不落列出来，
 * 直接问 [ToolRegistryRuntime]（装配口出口时登记的那张表），不维护第二份清单——
 * 拆族数对照表迟早漂移，漂了又是「界面上写着能调、实际没有」。
 *
 * 每件一行：名字 + 状态点。绿 = 模型此刻能调；黄 = 注册了但设置开关关着
 * （关掉不是坏了，去设置打开）；红 = 开关读取失败（gateNote 原样出声，不吞）。
 * 没装配（注册表为空）时照实说「还没装配任何工具」，不摆演示清单顶数。
 */
@Composable
private fun ToolLedgerCard() {
    val registry = ToolRegistryRuntime.current()
    val groups = ToolCenterState.grouped(registry)

    HCard {
        CardTitle("工具真账：${ToolCenterState.summary(registry)}")
        if (groups.isEmpty()) {
            Text("还没装配任何工具", fontSize = 13.sp, color = SubInk)
            return@HCard
        }
        val broken = ToolCenterState.gateIssues(registry)
        if (broken.isNotEmpty()) {
            Text(
                "有 ${broken.size} 件工具的开关读取出错：${broken.joinToString("、") { it.name }}",
                fontSize = 12.sp,
                color = WarnAmber,
            )
            Spacer(Modifier.height(6.dp))
        }
        groups.forEach { (family, rows) ->
            Spacer(Modifier.height(4.dp))
            Text(family, fontSize = 12.5.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tone = when {
                        row.gateNote != null -> Tone.Err
                        row.visible -> Tone.Ok
                        else -> Tone.Warn
                    }
                    Dot(tone)
                    Spacer(Modifier.width(8.dp))
                    Text(row.name, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            row.gateNote != null -> "开关读取出错"
                            row.visible && row.gated -> "开着（受设置开关控制）"
                            row.visible -> "可调"
                            row.gated -> "开关关着"
                            else -> "不可见"
                        },
                        fontSize = 11.sp,
                        color = SubInk,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "写仓库、建/合 PR、建分支、删分支、建 issue、评论、关 PR 都要过确认卡——模型提议，人点头才动。",
            fontSize = 11.5.sp,
            color = SubInk,
        )
    }
}
