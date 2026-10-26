package com.hualuo.repotool.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.state.ChatRuntime
import com.hualuo.repotool.ui.state.UsageRecord
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话用量条（对齐 Agora 现行版顶栏下那一条）：缓存命中 / 未缓存输入 / 输出 的累计真账，
 * 点开是逐请求明细。数据来自 provider 在流尾上报的 usage（引擎只搬运不估算），
 * 没上报的字段如实摆「—」；一条账都没有时整条不摆——不拿摆设冒充功能。
 *
 * 账认户头：只记本进程、当前会话，换会话/新开即清（明细里写明，不装成永久账）。
 * 原先顶栏右上的假 ctx 徽标（DemoCtx 演示字）随这刀撤除——ctx 这块从此只有真账。
 */
@Composable
fun ChatUsageStrip(chat: ChatRuntime) {
    val records = chat.usageLedger.records
    if (records.isEmpty()) return
    var showDetails by remember { mutableStateOf(false) }

    // 缓存两列只有在每条账都报了缓存明细时才合计（Agora 同款规矩）：
    // 只要有一条没报，合计就是编的，摆「—」。
    val cacheComplete = records.all { it.cachedTokens != null && it.uncachedTokens != null }
    val cached = if (cacheComplete) compactTokens(records.sumOf { it.cachedTokens ?: 0L }) else "—"
    val uncached = if (cacheComplete) compactTokens(records.sumOf { it.uncachedTokens ?: 0L }) else "—"
    val outputs = records.mapNotNull { it.outputTokens }
    val out = if (outputs.isEmpty()) "—" else compactTokens(outputs.sum())

    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable { showDetails = true }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "缓存命中 $cached · 未缓存输入 $uncached · 输出 $out",
            fontSize = 11.5.sp,
            color = SubInk,
            maxLines = 1,
        )
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("好") } },
            title = { Text("会话用量明细") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "当前会话、本进程内的累计上报用量；不是当前上下文大小。换会话即清。",
                        fontSize = 12.sp,
                        color = SubInk,
                    )
                    Spacer(Modifier.height(10.dp))
                    records.asReversed().forEach { r ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp)
                                .height(1.dp)
                                .background(Hairline),
                        )
                        UsageRow(r)
                    }
                }
            },
        )
    }
}

@Composable
private fun UsageRow(r: UsageRecord) {
    Text(
        "${hhmm(r.atMs)} · ${r.model}",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
    )
    Text(
        "输入 ${opt(r.inputTokens)} · 输出 ${opt(r.outputTokens)}",
        fontSize = 12.sp,
        color = SubInk,
    )
    Text(
        if (r.cachedTokens != null && r.uncachedTokens != null) {
            "缓存命中 ${compactTokens(r.cachedTokens ?: 0L)} · 未缓存 ${compactTokens(r.uncachedTokens ?: 0L)}"
        } else {
            "对方未提供缓存明细"
        },
        fontSize = 12.sp,
        color = SubInk,
    )
}

/** 999 -> 999，1200 -> 1.2K，2300000 -> 2.3M（Agora 同款写法）。 */
private fun compactTokens(v: Long): String = when {
    v >= 1_000_000L -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
    v >= 1_000L -> String.format(Locale.US, "%.1fK", v / 1_000.0)
    else -> v.toString()
}

/** 空字段的人话：没数就是「未提供」，不编 0。 */
private fun opt(v: Long?): String = v?.let(::compactTokens) ?: "未提供"

private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
