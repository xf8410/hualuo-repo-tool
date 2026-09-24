package com.hualuo.repotool.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.components.SheetScaffold
import com.hualuo.repotool.ui.data.DemoModels
import com.hualuo.repotool.ui.data.DemoTasks
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.SubInk

@Composable
fun SheetsLayer(state: AppUiState) {
    when {
        state.modelSheetOpen -> SheetScaffold("切换模型", onDismiss = { state.modelSheetOpen = false }) {
            DemoModels.forEach { model ->
                Text(model.name, fontSize = 14.sp, color = SubInk, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).clickable { state.currentModel = model.name; state.modelSheetOpen = false }.padding(12.dp))
            }
        }
        state.toolSheetOpen -> SheetScaffold("本回合工具", onDismiss = { state.toolSheetOpen = false }) { Text("工具开关在设置里保存", color = SubInk) }
        state.taskSheetKey != null -> SheetScaffold(state.taskSheetKey ?: "", onDismiss = { state.taskSheetKey = null }) { Text(DemoTasks.firstOrNull { it.name == state.taskSheetKey }?.note.orEmpty(), color = SubInk) }
    }
}
