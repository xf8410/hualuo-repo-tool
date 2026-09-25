package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.vision.VideoUrlPolicy
import com.hualuo.engine.vision.VisionExec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * analyze_video_url：主模型拿到视频 URL 后调用，服务端直接读取视频。
 * 手机不下载、不抽帧、不保存视频；协议不支持时明确拒绝，不偷偷降级。
 */
object VideoUrlTool {

    fun register(
        registry: ToolRegistry,
        sessionProvider: () -> com.hualuo.engine.api.ProviderSession?,
        transportFactory: () -> WireTransport = { UrlConnTransport() },
    ) {
        val spec = ToolSpec(
            name = "analyze_video_url",
            description = "分析一个视频链接的内容（服务端原生视频理解，不占本机存储）。" +
                "参数：url（视频链接）+ goal（想从视频里了解什么，可选）。" +
                "支持 YouTube 链接与直接视频文件 URL（.mp4/.webm/.mov/.m4v/.mkv 等）；" +
                "普通网页、平台分享页和 HLS 当前不支持。当前聊天模型是 Gemini 系才能执行。",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"视频链接（YouTube 或直接视频文件 URL）"},"goal":{"type":"string","description":"想从视频里总结出什么，可留空"}},"required":["url"]}""",
        )
        registry.registerGated(
            spec,
            ToolHandler { argumentsJson ->
                val session = sessionProvider()
                    ?: return@ToolHandler errorResult("no_session", "没有可用的模型会话")
                val args = argsOf(argumentsJson)
                val url = (args["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) return@ToolHandler errorResult("no_url", "缺少视频 URL")
                val goal = (args["goal"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(2_000)
                when (val verdict = VideoUrlPolicy.check(url)) {
                    is VideoUrlPolicy.Verdict.Rejected -> return@ToolHandler errorResult("url_rejected", verdict.reason)
                    is VideoUrlPolicy.Verdict.Allowed -> Unit
                }
                if (session.protocol != ProviderProtocol.GEMINI) {
                    return@ToolHandler errorResult(
                        "protocol_unsupported",
                        "当前模型协议 ${session.protocol} 不支持服务端视频输入；把聊天模型切到 Gemini 系再试。本工具不偷偷降级成本地下载抽帧。",
                    )
                }
                val instruction = buildString {
                    append("看完这个视频，整理成：【画面流水】按时间讲清发生了什么；")
                    append("【关键文字/数值】原样保留视频里出现的选项、数值、提示文字；")
                    if (goal.isNotBlank()) append("用户的关注点：$goal。")
                    append("看不清或听不清的地方标注「不清」，不许猜。")
                }
                when (val outcome = VisionExec.askVideoUrl(session, transportFactory(), url, instruction)) {
                    is VisionExec.Outcome.Ok -> buildJsonObject {
                        put("type", "analyze_video_url")
                        put("status", "ok")
                        put("url", url)
                        put("summary", outcome.text)
                    }.toString()
                    is VisionExec.Outcome.Failed -> errorResult("analysis_failed", outcome.reason)
                }
            },
            visibleIf = { sessionProvider() != null },
        )
    }

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("type", "analyze_video_url")
        put("error", code)
        put("message", message)
    }.toString()

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())
}
