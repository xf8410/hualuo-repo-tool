package com.hualuo.repotool.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.state.SandboxGate
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.Scrim
import com.hualuo.repotool.ui.theme.SubInk
import androidx.compose.foundation.border

/**
 * 沙盒确认卡（终端页实装刀）：run_command / install / remove 三件执行前的人闸。
 * 与 GitHubActionCard 同层同规矩——命令全文明文可核对，点头才跑。
 */
@Composable
fun SandboxCard(gate: SandboxGate) {
    val card = gate.pending.value ?: return
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
            Text("模型请求（沙盒）：${proposal.action}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text("要执行的全文（核对用）：", fontSize = 12.sp, color = SubInk)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .background(Color(0xFFFAFBFC))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    proposal.preview,
                    fontSize = 12.sp,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
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
                        .width(10.dp)
                        .height(38.dp),
                ) {}
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink)
                        .clickable { gate.approve(card.id) },
                    contentAlignment = Alignment.Center,
                ) { Text("允许执行", fontSize = 14.sp, color = Color.White) }
            }
        }
    }
}
