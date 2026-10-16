package com.hualuo.repotool.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hualuo.repotool.ui.theme.ThumbBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 聊天图片的收件口与缩略件（2026-10-16 图片附件刀，对齐旧 Agora ImageProcessor 的契约）。
 *
 * 契约照原版三条：
 *  1. **收件即落地**：系统选择器给的 content URI 在读的那一刻克隆成应用私有文件
 *     （chat_images/img_*.jpg）——URI 授权跟进程走，不落地的话重开 App 图就没了；
 *  2. **降采样再压**：先 inJustDecodeBounds 读尺寸，按 1024 上限二分采样解码，
 *     JPEG q80 落盘——手机原图几 MB 直接进请求体是内存与流量双事故；
 *  3. **读不动如实跳过**：单张失败不拖整批，收件结果报「成了几张、跳过几张」。
 *
 * 缩略图（[ChatImageThumb]）走同一条降采样路（目标 192px 够 44-56dp 用），
 * 解码在 IO 线程（produceState），组合期不碰盘。
 */
object ChatImageInbox {

    /** 收件成品的长边上限（旧 Agora 同款 1024）。 */
    const val MAX_DIMENSION = 1024

    /** JPEG 压缩质量（旧 Agora 同款 80）。 */
    const val JPEG_QUALITY = 80

    data class ImportResult(val paths: List<String>, val skipped: Int)

    /** 批量收件：逐张 探测->解码->压盘；读不动的跳过并计数。调用方负责后台线程。 */
    fun import(context: Context, uris: List<Uri>, dir: File): ImportResult {
        dir.mkdirs()
        val paths = mutableListOf<String>()
        var skipped = 0
        for (uri in uris) {
            val path = importOne(context, uri, dir)
            if (path != null) paths.add(path) else skipped++
        }
        return ImportResult(paths, skipped)
    }

    /** 单张收件：任何一步不顺返回 null（调用方记跳过数）。 */
    private fun importOne(context: Context, uri: Uri, dir: File): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sampled = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION) }
            val bitmap = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, sampled) } ?: return null
            val out = File(dir, "img_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8) + ".jpg")
            val ok = out.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream) }
            bitmap.recycle()
            if (ok && out.isFile && out.length() > 0L) out.absolutePath else null
        } catch (_: Exception) {
            null
        }
    }

    /** 缩略解码（界面用）：同一条降采样路，目标边长小得多。读不动返回 null（摆占位）。 */
    fun decodeThumb(path: String, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sampled = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, targetPx) }
            BitmapFactory.decodeFile(path, sampled)
        } catch (_: Exception) {
            null
        }
    }

    /** 二分采样率：旧 Agora 同款写法——每次翻倍直到两边都进上限。 */
    private fun sampleFor(width: Int, height: Int, limit: Int): Int {
        var scale = 1
        while (width / scale / 2 >= limit && height / scale / 2 >= limit) scale *= 2
        return scale
    }
}

/**
 * 聊天图片缩略件：IO 线程降采样解码（produceState），读不出来摆一块底色占位
 * （不装作有图，也不炸组合）。输入区 44dp、消息卡 56dp 两种规格共用这一件。
 */
@Composable
fun ChatImageThumb(path: String, sizeDp: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ChatImageInbox.decodeThumb(path, 192) }
    }
    val sized = modifier
        .size(sizeDp.dp)
        .clip(RoundedCornerShape(10.dp))
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = path.substringAfterLast('/'),
            contentScale = ContentScale.Crop,
            modifier = sized,
        )
    } else {
        Box(modifier = sized.background(ThumbBg))
    }
}
