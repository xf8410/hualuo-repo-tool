package com.hualuo.repotool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.Bg
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ErrRed
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk

/** 代理卡：类型/地址/端口/认证/绕行真存盘；保存当场 installProxy（AI 请求与 GitHub API 出网口）。 */
@Composable
internal fun ProxyCardField(state: AppUiState) {
    var type by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.text(com.hualuo.repotool.ui.state.UiKeys.PROXY_TYPE).ifBlank { "none" }) }
    var host by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.text(com.hualuo.repotool.ui.state.UiKeys.PROXY_HOST)) }
    var port by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.text(com.hualuo.repotool.ui.state.UiKeys.PROXY_PORT).ifBlank { "7890" }) }
    var user by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.text(com.hualuo.repotool.ui.state.UiKeys.PROXY_USER)) }
    var pass by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.text(com.hualuo.repotool.ui.state.UiKeys.PROXY_PASS)) }
    var bypass by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.text(com.hualuo.repotool.ui.state.UiKeys.PROXY_BYPASS)) }
    var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Text("代理（AI 请求与 GitHub API；游戏观测桥 localhost 不经此口）", fontSize = 13.sp, color = SubInk)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("none" to "无", "http" to "HTTP", "socks" to "SOCKS").forEach { (key, label) ->
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (type == key) Color.White else Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (type == key) Accent else Bg)
                        .clickable { type = key }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        if (type != "none") {
            Spacer(Modifier.height(6.dp))
            Text("地址", fontSize = 12.sp, color = SubInk)
            androidx.compose.foundation.text.BasicTextField(
                value = host, onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
            )
            Spacer(Modifier.height(4.dp))
            Text("端口", fontSize = 12.sp, color = SubInk)
            androidx.compose.foundation.text.BasicTextField(
                value = port, onValueChange = { port = it },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
            )
            Spacer(Modifier.height(4.dp))
            Text("用户名（可选；SOCKS 与 HTTP 407 认证）", fontSize = 12.sp, color = SubInk)
            androidx.compose.foundation.text.BasicTextField(
                value = user, onValueChange = { user = it },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
            )
            Spacer(Modifier.height(4.dp))
            Text("密码（可选）", fontSize = 12.sp, color = SubInk)
            androidx.compose.foundation.text.BasicTextField(
                value = pass, onValueChange = { pass = it },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(4.dp))
            Text("绕行清单（可选；逗号分隔：精确主机 / *.后缀 / IPv4 CIDR）", fontSize = 12.sp, color = SubInk)
            androidx.compose.foundation.text.BasicTextField(
                value = bypass, onValueChange = { bypass = it },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "保存并生效",
            fontSize = 13.sp, color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                if (type == "none") {
                    com.hualuo.engine.api.TransportProxy.clear()
                    state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_TYPE, "none")
                    note = "已清代理：直连"
                } else {
                    val p = port.trim().toIntOrNull()
                    val bypassList = bypass.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
                    val ok = p != null && com.hualuo.engine.api.TransportProxy.installProxy(
                        type, host, p, bypassList,
                        username = user.trim(), password = pass,
                    )
                    if (ok) {
                        state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_TYPE, type)
                        state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_HOST, host.trim())
                        state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_PORT, port.trim())
                        state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_USER, user.trim())
                        state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_PASS, pass)
                        state.setText(com.hualuo.repotool.ui.state.UiKeys.PROXY_BYPASS, bypass)
                        val authNote = if (user.isBlank()) "" else " +认证"
                        val bypassNote = if (bypassList.isEmpty()) "" else " 绕行${bypassList.size}条"
                        note = "已生效：$type ${host.trim()}:$port$authNote$bypassNote（AI 与 GitHub API 走代理）"
                    } else {
                        note = "没生效：地址或端口不合法（端口要 1-65535）"
                    }
                }
            }.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        note?.let { n ->
            Spacer(Modifier.height(4.dp))
            Text(n, fontSize = 12.sp, color = if (n.startsWith("没")) ErrRed else SubInk)
        }
        Spacer(Modifier.height(4.dp))
        Text("只影响 AI 请求与 GitHub API；观测桥（18765）是本机回环不经代理，游戏加速器不受影响。", fontSize = 11.sp, color = SubInk)
    }
}
