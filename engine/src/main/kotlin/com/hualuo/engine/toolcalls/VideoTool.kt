package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.io.DEFAULT_BUFFER_BYTES
import com.hualuo.engine.vision.VideoPlan
import com.hualuo.engine.vision.VisionExec
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 看视频工具族：读取导入时缓存的帧并返回文字。 */
object VideoTool {

    private const val MAX_FRAME_BYTES = 8L * 1024L * 1024L

    fun register(registry: ToolRegistry, manifestsDir: File, framesDir: File, visionSession: () -> ProviderSession?, transport: WireTransport = UrlConnTransport()) {
        registry.registerGated(
            ToolSpec("list_videos", "列出已导入的视频库（名字、时长、帧数）。", """{"type":"object","properties":{},"required":[]}"""),
            ToolHandler { listVideos(manifestsDir) },
        ) { visionSession() != null }
        registry.registerGated(
            ToolSpec("watch_video", "看一个已导入的视频，汇总成【画面流水】+【攻略要点】。", """{"type":"object","properties":{"name":{"type":"string"},"focus":{"type":"string"}},"required":["name"]}"""),
            ToolHandler { argumentsJson -> watch(manifestsDir, framesDir, visionSession(), transport, argumentsJson) },
        ) { visionSession() != null }
    }

    private fun listVideos(manifestsDir: File): String {
        val manifests = readManifests(manifestsDir)
        if (manifests.isEmpty()) return "视频库是空的。让用户先把录屏导入（工具页-视频库-导入）。"
        return "视频库共 ${manifests.size} 条：\n" + manifests.joinToString("\n") { "- ${it.name}（${it.durationMs / 1000}s，${it.frames.size} 帧）" }
    }

    fun readManifests(manifestsDir: File): List<Manifest> =
        manifestsDir.listFiles { f -> f.extension == "json" && f.name.endsWith(".manifest.json") }
            ?.sortedBy { it.name }?.mapNotNull { readManifest(it) }.orEmpty()

    private fun watch(manifestsDir: File, framesDir: File, session: ProviderSession?, transport: WireTransport, argumentsJson: String): String {
        if (session == null) return "看视频的眼睛模型没配上：去设置-看视频的眼睛里填一个带视觉的模型 id。"
        val args = argsOf(argumentsJson)
        val name = safeName((args["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
            ?: return "视频名不合法：只接受 list_videos 返回的单个名字，不接受路径、斜杠或空名字。"
        val focus = (args["focus"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(2_000)
        val manifest = File(manifestsDir, "$name.manifest.json").takeIf { it.isFile }?.let { readManifest(it) }
        if (manifest == null) return "没有叫「$name」的视频。"
        val notes = mutableListOf<String>()
        val batches = VideoPlan.batches(manifest.frameTimes)
        batches.forEachIndexed { bi, batchTimes ->
            val images = batchTimes.mapIndexedNotNull { _, time ->
                val index = manifest.frameTimes.indexOf(time)
                val file = safeFrameFile(framesDir, manifest.frames.getOrNull(index) ?: return@mapIndexedNotNull null)
                file?.let { frameBase64(it) }
            }.filterNotNull()
            if (images.isEmpty()) return@forEachIndexed
            when (val outcome = VisionExec.ask(session, transport, images, VideoPlan.describePrompt(bi, batches.size, batchTimes))) {
                is VisionExec.Outcome.Ok -> notes += outcome.text
                is VisionExec.Outcome.Failed -> return partial(manifest, notes, "第 ${bi + 1} 批没读出来：${outcome.reason}")
            }
        }
        if (notes.isEmpty()) return "帧缓存是空的（导入时抽帧失败，或账本路径不安全）。"
        return when (val summary = VisionExec.askText(session, transport, VideoPlan.summarizePrompt(notes.joinToString("\n\n"), focus.ifEmpty { null }))) {
            is VisionExec.Outcome.Ok -> "视频 ${manifest.name}（${manifest.durationMs / 1000}s，${manifest.frames.size} 帧）看完了：\n\n${summary.text}"
            is VisionExec.Outcome.Failed -> partial(manifest, notes, "汇总没成：${summary.reason}")
        }
    }

    private fun partial(manifest: Manifest, notes: List<String>, problem: String): String =
        "视频 ${manifest.name} 读了 ${notes.size} 批画面，$problem\n以下是各批原始描述：\n\n" + notes.joinToString("\n\n")

    private fun safeName(raw: String): String? {
        if (raw.isBlank() || raw == "." || raw == ".." || raw.any { it == '/' || it == '\\' || it.code == 0 }) return null
        return raw
    }

    private fun safeFrameFile(root: File, relative: String): File? {
        if (relative.isBlank() || relative.indexOf('\u0000') >= 0 || File(relative).isAbsolute) return null
        val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return null
        val file = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.toPath().startsWith(rootPath) && it.isFile }
    }

    private fun frameBase64(file: File): String? {
        if (file.length() <= 0L || file.length() > MAX_FRAME_BYTES) return null
        return try {
            val sink = GrowingSink()
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                }
            }
            Base64.getEncoder().encodeToString(sink.toBytes())
        } catch (_: Exception) { null }
    }

    private class GrowingSink(initial: Int = 256 * 1024) : java.io.OutputStream() {
        private var data = ByteArray(initial)
        private var len = 0
        override fun write(b: Int) { ensure(1); data[len++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, n: Int) { ensure(n); System.arraycopy(b, off, data, len, n); len += n }
        fun toBytes(): ByteArray = data.copyOf(len)
        private fun ensure(n: Int) { if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n)) }
    }

    data class Manifest(val name: String, val durationMs: Long, val frameTimes: List<Long>, val frames: List<String>)

    fun readManifest(file: File): Manifest? = try {
        val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
        val name = safeName((root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) ?: return null
        val duration = (root["durationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        val times = (root["frameTimes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.orEmpty()
        val frames = (root["frames"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val valid = frames.isNotEmpty() && frames.size == times.size && times.distinct().size == times.size && frames.none { it.isBlank() || File(it).isAbsolute || it.split('/', '\\').any { p -> p == ".." } }
        if (valid) Manifest(name, duration, times, frames) else null
    } catch (_: Exception) { null }

    private fun argsOf(argumentsJson: String): JsonObject = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())
}
