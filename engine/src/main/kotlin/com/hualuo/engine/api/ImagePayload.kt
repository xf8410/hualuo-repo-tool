package com.hualuo.engine.api

import java.io.File
import java.io.FileInputStream
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 图片进请求体（对齐旧 Agora MessageConverter.encodeImageToBase64 的契约）：
 * user 消息的 content 从纯字符串升成 parts 数组——text 分件在前，每张图一个
 * image_url 分件（data:mime;base64,... 内联）。
 *
 * 纪律：
 *  - 图在收件时已降采样压成 JPEG（app 侧 ChatImageInbox，1024 上限 + q80），
 *    这层只做读+编码；仍设硬顶 [MAX_IMAGE_BYTES]：超顶/读不动如实返回 null
 *    （那张图不带、文本照发）——宁可少带一张图，不许一次读爆内存。
 *  - 纯 JVM（D-03）：java.util.Base64，不碰 android.util；读文件用定长缓冲
 *    循环——不引「整文件一把读」的那类写法（红线二词法闸盯着的活样本）。
 *  - 原生协议路径（Gemini/Anthropic/Ollama 走 ProviderClient）暂不带图：
 *    OPENAI_COMPAT 先行，其余各家留下一刀（本件不假装它们支持）。
 */
object ImagePayload {

    /** 单张图的硬顶（字节）。降采样后的 JPEG 正常几百 KB，8MB 拦的是「事故」。 */
    const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /** 读出 data URI；文件不存在/超顶/读不全都返回 null（调用方跳过这张图）。 */
    fun dataUriFor(path: String): String? {
        val f = File(path)
        val size = f.length()
        if (!f.isFile || size <= 0L || size > MAX_IMAGE_BYTES) return null
        val buf = ByteArray(size.toInt())
        try {
            FileInputStream(f).use { input ->
                var off = 0
                while (off < buf.size) {
                    val r = input.read(buf, off, buf.size - off)
                    if (r < 0) break
                    off += r
                }
                if (off != buf.size) return null
            }
        } catch (_: Exception) {
            return null
        }
        val mime = when (f.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(buf)
    }

    /** 一张图 = 一个 image_url 分件（OpenAI 兼容形状）。读不出来就是 null。 */
    fun partFor(path: String): JsonObject? = dataUriFor(path)?.let { uri ->
        buildJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") { put("url", uri) }
        }
    }
}
