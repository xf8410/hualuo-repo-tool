package com.hualuo.repotool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.state.GitHubActionGate
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.Scrim
import com.hualuo.repotool.ui.theme.SubInk

/**
 * GitHub 动作确认卡（2026-10-05 全套刀）：建分支 / 删分支 / 建 issue / 评论 issue /
 * 关 PR / 评论 PR 六件共用一张键值对卡——模型把「要动什么」逐行摆上来，人点头才执行。
 *
 * 为什么共用一张卡：六件的本质都是「改仓库状态的一条记录」，字段不同（分支名、
 * PR 号、标题、正文），形状相同（键值对）。分成六张卡就是六份布局六份坑，
 * 共用一张 = 加键值对就完事。有正文的动作（评论、issue 说明）把开头一段摆上来核对，
 * 与写卡同一条「看得见原文」的规矩。
 */
@Composable
fun GitHubActionCard(gate: GitHubActionGate) {
    val card = gate.pending ?: return
    val proposal = card.proposal
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
                .padding(18.dp),
        ) {
            Text("模型请求：${proposal.action}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(10.dp))

            ActField("仓库", proposal.repo)
            proposal.fields.forEach { (label, value) ->
                ActField(label, value)
            }
            if (proposal.bodyPreview.isNotEmpty()) {
                ActField("正文", "${proposal.bodyChars} 字符")
                Spacer(Modifier.height(4.dp))
                Text("正文开头（核对用）：", fontSize = 12.sp, color = SubInk)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                        .background(Color(0xFFFAFBFC))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        proposal.bodyPreview,
                        fontSize = 12.sp,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF3F4F6))
                        .clickable { gate.reject(card.id) },
                    contentAlignment = Alignment.Center,
                ) { Text("不执行", fontSize = 14.sp, color = Ink) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent)
                        .clickable { gate.approve(card.id) },
                    contentAlignment = Alignment.Center,
                ) { Text("确认执行", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "点「不执行」或 5 分钟不处理，这次操作一律不发生。",
                fontSize = 11.5.sp,
                color = ErrRed.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun ActField(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, fontSize = 12.5.sp, color = SubInk, modifier = Modifier.width(64.dp))
        Text(
            value,
            fontSize = 12.5.sp,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
