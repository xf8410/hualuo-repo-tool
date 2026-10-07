package com.hualuo.repotool.ui.reports

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ReportRunState
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.Bg
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.OkGreen
import com.hualuo.repotool.ui.theme.SubInk

/**
 * 报告页：三件套（01 数据分析 / 02 汇报文档 / 03 PPT 大纲）。
 *
 * 形态对齐用户给的参考截图（清言功能卡）：大编号水印 + 标题 + 输入框 + 发送钮 + 结果区。
 * 纪律：三件全是真 AI 生成（当前模型 + 专用系统指令），没配模型出声不装样子；
 * 结果区各自真渲染——分析画真折线图，文档按行解析标题/列表，PPT 逐页翻。
 */
@Composable
fun ReportsScreen(state: AppUiState) {
    val run = state.reports
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        ReportShell(number = "01", title = "数据分析", tagline = "丢一段数据，回你指标 + 图 + 结论") {
            InputRow(
                hint = "把数据或现象描述贴进来",
                value = run.analysisInput,
                onValue = { run.analysisInput = it },
                busy = run.analysisBusy,
                actionLabel = "分析",
            ) { run.runAnalysis() }
            run.analysisNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = if (run.analysis == null) ErrRed else SubInk)
            }
            run.analysis?.let { a ->
                Spacer(Modifier.height(10.dp))
                AnalysisBody(a)
            }
        }
        Spacer(Modifier.height(12.dp))
        ReportShell(number = "02", title = "汇报文档", tagline = "给主题和素材，回一份结构化初稿") {
            InputRow(
                hint = "写要汇报的主题与素材",
                value = run.docInput,
                onValue = { run.docInput = it },
                busy = run.docBusy,
                actionLabel = "生成",
            ) { run.runDocument() }
            run.docNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = if (run.document == null) ErrRed else SubInk)
            }
            run.document?.let { d ->
                Spacer(Modifier.height(10.dp))
                DocumentBody(d)
            }
        }
        Spacer(Modifier.height(12.dp))
        ReportShell(number = "03", title = "PPT 大纲", tagline = "给主题，回逐页卡片，可翻页") {
            InputRow(
                hint = "写演示文稿的主题",
                value = run.pptInput,
                onValue = { run.pptInput = it },
                busy = run.pptBusy,
                actionLabel = "生成",
            ) { run.runPpt() }
            run.pptNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = if (run.slides.isEmpty()) ErrRed else SubInk)
            }
            if (run.slides.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SlidesBody(run)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 功能卡外壳：大编号水印 + 标题 + 副题 + 内容槽（参考截图的 01/02/03 形态）。 */
@Composable
private fun ReportShell(number: String, title: String, tagline: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CardBg)
            .padding(16.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                number,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                color = Hairline,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            Column(Modifier.padding(top = 10.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(tagline, fontSize = 12.sp, color = SubInk)
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** 输入行：圆角输入框 + 发送钮（busy 时换文案并禁点）。 */
@Composable
private fun InputRow(
    hint: String,
    value: String,
    onValue: (String) -> Unit,
    busy: Boolean,
    actionLabel: String,
    action: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Bg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = SubInk)
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (busy) SubInk else Accent)
                .clickable(enabled = !busy) { action() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(if (busy) "跑着…" else actionLabel, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 数据分析结果体：指标行 + 真折线图（Canvas）+ 结论 + 建议。 */
@Composable
private fun AnalysisBody(a: ReportRunState.Analysis) {
    if (a.metrics.isNotEmpty()) {
        a.metrics.forEach { (label, value, trend) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                Text(value, fontSize = 13.sp, color = Ink)
                Spacer(Modifier.width(8.dp))
                Text(
                    trend,
                    fontSize = 12.sp,
                    color = when (trend) { "涨" -> OkGreen; "跌" -> ErrRed; else -> SubInk },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (a.points.size >= 2) {
        LineChart(points = a.points, name = a.seriesName)
        Spacer(Modifier.height(8.dp))
    }
    if (a.conclusion.isNotBlank()) {
        LabelBox("结论", a.conclusion, Ink)
    }
    if (a.advice.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        LabelBox("建议", a.advice, Accent)
    }
}

/** 小标签 + 内容盒（结论/建议共用）。 */
@Composable
private fun LabelBox(label: String, text: String, labelColor: Color) {
    Row {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(labelColor.copy(alpha = 0.1f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text(label, fontSize = 12.sp, color = labelColor, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
    }
}

/** 折线图：Canvas 真画（数据点全量映射，y 轴留头，零基线，峰值标红）。 */
@Composable
private fun LineChart(points: List<Pair<Double, Double>>, name: String) {
    Column {
        Text(name, fontSize = 12.sp, color = SubInk)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(Bg).padding(8.dp)) {
            val xs = points.map { it.first }
            val ys = points.map { it.second }
            val minX = xs.min(); val maxX = xs.max()
            val minY = ys.min(); val maxY = ys.max()
            val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1.0
            val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1.0
            val pad = 12f
            fun px(x: Double) = pad + ((x - minX) / spanX * (size.width - 2 * pad)).toFloat()
            fun py(y: Double) = size.height - pad - ((y - minY) / spanY * (size.height - 2 * pad)).toFloat()
            if (minY <= 0 && maxY >= 0) {
                drawLine(Hairline, Offset(pad, py(0.0)), Offset(size.width - pad, py(0.0)), strokeWidth = 2f)
            }
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
            }
            drawPath(path, Accent, style = Stroke(width = 4f, pathEffect = PathEffect.cornerPathEffect(8f)))
            points.forEach { (x, y) -> drawCircle(Accent, radius = 5f, center = Offset(px(x), py(y))) }
            val peak = points.maxBy { it.second }
            drawCircle(ErrRed, radius = 6f, center = Offset(px(peak.first), py(peak.second)))
        }
    }
}

/** 文档结果体：按行解析（# 大标题 / ## 节标题 / - 列表 / 其余正文）。 */
@Composable
private fun DocumentBody(d: ReportRunState.Document) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Bg)
            .padding(12.dp),
    ) {
        d.body.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> Text(line.removePrefix("## "), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                line.startsWith("# ") -> Text(line.removePrefix("# "), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                line.startsWith("- ") || line.startsWith("* ") -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text("·", fontSize = 13.sp, color = Accent)
                    Spacer(Modifier.width(6.dp))
                    Text(line.substring(2), fontSize = 13.sp, color = Ink)
                }
                line.isNotBlank() -> Text(line, fontSize = 13.sp, color = Ink, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

/** PPT 结果体：当前页卡片 + 翻页行（上一页/页码/下一页）。 */
@Composable
private fun SlidesBody(run: ReportRunState) {
    val slides = run.slides
    val idx = run.slideIndex.coerceIn(0, slides.size - 1)
    Text(run.pptTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Bg)
            .padding(14.dp),
    ) {
        Text("第 ${idx + 1} 页", fontSize = 11.sp, color = SubInk)
        Spacer(Modifier.height(4.dp))
        Text(slides[idx].heading, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        slides[idx].bullets.forEach { b ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("·", fontSize = 13.sp, color = Accent)
                Spacer(Modifier.width(6.dp))
                Text(b, fontSize = 13.sp, color = Ink)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        PageButton("上一页", enabled = idx > 0) { run.slideIndex = idx - 1 }
        Spacer(Modifier.width(8.dp))
        Text("${idx + 1} / ${slides.size}", fontSize = 13.sp, color = SubInk, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.width(8.dp))
        PageButton("下一页", enabled = idx < slides.size - 1) { run.slideIndex = idx + 1 }
    }
}

/** 翻页小钮：禁用时灰。 */
@Composable
private fun PageButton(label: String, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Accent else Hairline)
            .clickable(enabled = enabled) { action() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, fontSize = 13.sp, color = Color.White) }
}
