package com.hualuo.repotool.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hualuo.engine.vision.VideoPlan
import com.hualuo.repotool.ui.state.AppUiState
import java.io.File

/** 录屏导入：只把原视频当抽帧输入，成功后由界面层清理原文件。 */
object VideoImporter {

    fun copyIn(context: Context, uri: Uri, inbox: File, displayName: String?): File? {
        val safeName = cleanName(displayName)
        var dest = File(inbox, safeName)
        if (dest.exists()) {
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ".mp4"
            dest = File(inbox, "$base-${System.currentTimeMillis()}$ext")
        }
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source -> dest.outputStream().use { target -> com.hualuo.engine.io.streamingCopy(source, target) } }
            if (dest.isFile && dest.length() > 0L) dest else { dest.delete(); null }
        } catch (_: Exception) { dest.delete(); null }
    }

    fun import(state: AppUiState, videoFile: File, framesRoot: File, inbox: File): File? {
        if (!videoFile.isFile || videoFile.length() == 0L) return null
        val retriever = android.media.MediaMetadataRetriever()
        var frameDir: File? = null
        var success = false
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return null
            val times = VideoPlan.frameTimes(durationMs)
            val base = videoFile.name
            val dir = File(framesRoot, base).apply { mkdirs() }
            frameDir = dir
            val relTimes = mutableListOf<Long>()
            val rels = mutableListOf<String>()
            times.forEachIndexed { i, tMs ->
                val frame = retriever.getFrameAtTime(tMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    var scaled: Bitmap? = null
                    try {
                        val bitmap = scaleDown(frame)
                        scaled = bitmap
                        val rel = "$base/f$i.jpg"
                        val sink = ByteSink()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, sink)
                        File(dir, "f$i.jpg").writeBytes(sink.toBytes())
                        relTimes += tMs
                        rels += rel
                    } finally {
                        if (scaled != null && scaled !== frame && !scaled.isRecycled) scaled.recycle()
                        if (!frame.isRecycled) frame.recycle()
                    }
                }
                state.video.setVideoImporting(true, "抽帧 ${i + 1}/${times.size}")
            }
            if (rels.isEmpty()) return null
            val manifest = File(inbox, "${videoFile.name}.manifest.json")
            manifest.writeText("""{"name":${jsonQuote(videoFile.name)},"durationMs":$durationMs,"frameTimes":${relTimes.joinToString(",", "[", "]")},"frames":${rels.joinToString(",", "[", "]") { jsonQuote(it) }}}""")
            success = true
            return manifest
        } catch (_: Exception) { return null }
        finally {
            runCatching { retriever.release() }
            if (!success) runCatching { frameDir?.deleteRecursively() }
        }
    }

    private fun scaleDown(src: Bitmap, maxWidthPx: Int = 1024): Bitmap {
        if (src.width <= maxWidthPx) return src
        return Bitmap.createScaledBitmap(src, maxWidthPx, (src.height.toLong() * maxWidthPx / src.width).toInt().coerceAtLeast(1), true)
    }

    private fun cleanName(raw: String?): String {
        val source = raw?.trim().orEmpty().ifBlank { "clip" }
        val cleaned = buildString { source.forEach { ch -> if (ch.code < 0x20 || ch.code == 0x7f || ch in "/\\:*?\"<>|") append('_') else append(ch) } }.trim().ifBlank { "clip" }.take(180)
        return if (cleaned == "." || cleaned == "..") "clip" else cleaned
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { ch -> when (ch) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\b' -> append("\\b"); '\u000C' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch) } }
        append('"')
    }

    private class ByteSink(initial: Int = 512 * 1024) : java.io.OutputStream() {
        private var data = ByteArray(initial)
        private var len = 0
        override fun write(b: Int) { ensure(1); data[len++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, n: Int) { ensure(n); System.arraycopy(b, off, data, len, n); len += n }
        fun toBytes(): ByteArray = data.copyOf(len)
        private fun ensure(n: Int) { if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n)) }
    }
}

fun queryDisplayName(context: Context, uri: Uri): String? = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
