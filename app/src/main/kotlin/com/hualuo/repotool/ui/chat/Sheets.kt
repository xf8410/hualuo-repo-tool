package com.hualuo.repotool.ui.chat

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.components.SheetScaffold
import com.hualuo.repotool.ui.data.DemoTasks
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ModelSettingsRuntime
import com.hualuo.repotool.ui.state.modelLabelWithProvider
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk

/**
 * 输入区上方那三块互斥弹层：切换模型 / 本回合工具 / 任务详情。
 *
 * **切换模型按旧仓那张下拉改的**（用户 2026-10-03 拍板「对话这里的切换模型也照着 Agora 改 UI」）：
 *  - 每条摆 `显示名 (提供商)`（显示名走 [modelLabelWithProvider]：别名优先、剥掉 provider: 前缀），
 *    选中那条加粗、染主色、左侧亮一个点，一眼看得见现在用的是谁；
 *  - 显示名单行省略，长模型名不撑破弹层；
 *  - 一条也没启用时说清去哪儿开，不摆空白。
 *
 * 纵向滚动只有这一处（弹层本身就是滚动容器），不塞惰性列表——NestedScrollGateTest 红线。
 */
@Composable
fun SheetsLayer(state: AppUiState) {
    when {
        state.modelSheetOpen -> SheetScaffold("切换模型", onDismiss = { state.modelSheetOpen = false }) {
            val rows = ModelSettingsRuntime.current()?.selectedModels().orEmpty()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rows.isEmpty()) {
                    Text(
                        "还没有已启用模型：去设置里的「模型」页勾几个，或先从提供商同步一次。",
                        fontSize = 13.sp,
                        color = SubInk,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                rows.forEach { model ->
                    val picked = model.id == state.currentModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (picked) Color(0xFFF0F5FF) else CardBg)
                            .border(1.dp, if (picked) Accent else Hairline, RoundedCornerShape(12.dp))
                            .clickable {
                                state.currentModel = model.id
                                state.modelSheetOpen = false
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(8.dp)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(if (picked) Accent else Hairline),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                modelLabelWithProvider(model.id, model.alias, model.providerName),
                                fontSize = 14.sp,
                                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (picked) Accent else Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(model.providerName, fontSize = 11.5.sp, color = SubInk, maxLines = 1)
                        }
                        if (picked) Text("在用", fontSize = 11.sp, color = Accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        state.toolSheetOpen -> SheetScaffold("本回合工具", onDismiss = { state.toolSheetOpen = false }) { Text("工具开关在设置里保存", color = SubInk) }
        state.taskSheetKey != null -> SheetScaffold(state.taskSheetKey ?: "", onDismiss = { state.taskSheetKey = null }) { Text(DemoTasks.firstOrNull { it.name == state.taskSheetKey }?.note.orEmpty(), color = SubInk) }
    }
}
