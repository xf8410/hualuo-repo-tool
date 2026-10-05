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
import com.hualuo.repotool.ui.state.PrConfirmGate
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.Scrim
import com.hualuo.repotool.ui.theme.SubInk

/**
 * PR 确认卡（2026-10-05 全套刀）：模型提议建 PR / 合 PR 时摆出来等人点头。
 *
 * 卡上必须看全「要动什么」：建 PR 显示 head->base、标题、草稿与否；
 * 合 PR 显示 PR 号、被钉死的 head sha（对账用，分支再推提交也会被拒，防「确认的是 A
 * 合进去的却是 B」）、合并方式。红线与写卡同一条：**点头才动，拒绝/超时什么都不动**。
 */
@Composable
fun PrConfirmCard(gate: PrConfirmGate) {
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
            val isCreate = proposal.action == "create"
            Text(
                if (isCreate) "模型请求创建 PR" else "模型请求合并 PR",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Spacer(Modifier.height(10.dp))

            if (isCreate) {
                PrField("仓库", proposal.repo)
                PrField("分支", "${proposal.head} -> ${proposal.base}")
                PrField("标题", proposal.title)
                PrField("形态", if (proposal.draft) "草稿（先挂着，不合）" else "正式（可合）")
            } else {
                PrField("仓库", proposal.repo)
                PrField("PR 编号", "#${proposal.number}")
                PrField("合并方式", proposal.method)
                PrField("钉死的 head", proposal.expectedHeadSha.take(12) + "（分支又推了新提交会拒合，防改口）")
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
                ) { Text("不动", fontSize = 14.sp, color = Ink) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent)
                        .clickable { gate.approve(card.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isCreate) "确认创建" else "确认合并",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (isCreate) {
                    "点「不动」或 5 分钟不处理，这个 PR 一律不建。"
                } else {
                    "点「不动」或 5 分钟不处理，这次合并一律不发生；合错了要在 GitHub 上手回滚。"
                },
                fontSize = 11.5.sp,
                color = ErrRed.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun PrField(label: String, value: String) {
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
