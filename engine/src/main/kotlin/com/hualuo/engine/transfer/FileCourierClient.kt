package com.hualuo.engine.transfer

import com.hualuo.engine.io.streamingCopy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一份待投递文件：名字与现场开流（不提前读进内存），sizeBytes 用于配卷（未知给 0，按独占卷处理）。 */
data class CourierFile(val name: String, val sizeBytes: Long, val open: () -> InputStream)

/** 上传一个卷的缝隙：路径已含前缀，内容为整卷字节。抛异常 = 这卷没传上，由 client 统一重试。 */
fun interface VolumeUploader {
    fun upload(path: String, bytes: ByteArray, message: String)
}

/** 投递收场：成功带账（卷数/文件数/原始字节），失败给一句能行动的话。绝不静默丢卷。 */
sealed class CourierOutcome {
    data class Ok(
        val manifestPath: String,
        val volumeCount: Int,
        val fileCount: Int,
        val totalRawBytes: Long,
    ) : CourierOutcome()

    data class Failed(val reason: String) : CourierOutcome()
}

/**
 * 文件投递（courier）引擎件：把一批文件按**文件边界**打成 zip 卷，逐卷投到私有仓，
 * 收尾写一份 manifest.json（每卷内容、每文件 SHA-256、总账）——收方按账还原与校验。
 *
 * 对齐旧仓验证过的形状：默认卷配额 32MB，单文件**不劈开**（超配额独占一卷，zip 后
 * 可能略超 32MB，是压缩率带来的偏差，诚实记录在 manifest 里，不假装精确）。
 *
 * 设计要点：
 *  - 上传动作经 [VolumeUploader] 缝隙注入：纯 JVM 测试喂假 uploader，绝不碰真网；
 *    真网实现见 [defaultUploader]（GitHub contents API，base64，一次一卷）；
 *  - 每卷失败自动重试 [maxRetries] 次（退避由 [sleeper] 注入），耗尽即整体失败并报清
 *    卡在哪一卷——不静默丢卷，也不假报成功；
 *  - 进度回调 [onProgress]（已完成卷数, 总卷数），接线层拿它画进度行（0.6.0 同款）；
 *  - 卷数封顶 [maxVolumes]：防止把仓库当无限盘打爆，超了直接拒绝出声。
 */
class FileCourierClient(
    private val uploader: VolumeUploader,
    private val volumeBytes: Int = DEFAULT_VOLUME_BYTES,
    private val maxVolumes: Int = DEFAULT_MAX_VOLUMES,
    private val maxRetries: Int = 2,
    private val sleeper: (Long) -> Unit = {},
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
) {

    /** 全量投递。路径形如 courier/20260917-093000/（前缀由调用方给，本类不抢时间戳的活）。 */
    fun deliver(files: List<CourierFile>, targetPrefix: String): CourierOutcome {
        if (files.isEmpty()) return CourierOutcome.Failed("没有要投递的文件")
        if (!targetPrefix.endsWith('/')) {
            return CourierOutcome.Failed("目标前缀要以斜杠结尾（如 courier/20260917-093000/）：$targetPrefix")
        }
        val plans = planVolumes(files)
        if (plans.size > maxVolumes) {
            return CourierOutcome.Failed("这批要打成 ${plans.size} 卷，超过上限 $maxVolumes：分批投或先清掉大件")
        }
        var totalRaw = 0L
        files.forEach { totalRaw += it.sizeBytes.coerceAtLeast(0L) }
        val volumeEntries = ArrayList<JsonObject>(plans.size)
        plans.forEachIndexed { index, plan ->
            val volumeName = "part_" + "%03d".format(index + 1) + ".zip"
            val volumePath = targetPrefix + volumeName
            val packed = packVolume(plan, volumeName)
            val attempts = maxRetries + 1
            var lastError: String? = null
            var sent = false
            repeat(attempts) { attempt ->
                if (attempt > 0) sleeper(1_000L * attempt)
                try {
                    uploader.upload(volumePath, packed, "courier: $volumeName（${plan.files.size} 个文件，投递卷）")
                    sent = true
                    lastError = null
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "（无消息）"}"
                }
                if (sent) return@repeat
            }
            if (!sent) {
                return CourierOutcome.Failed("卷 $volumeName 连试 $attempts 次都没传上（$lastError）：网络或令牌出问题了，之前已传的卷都在仓里，稍后整批重投即可")
            }
            volumeEntries += buildJsonObject {
                put("name", volumeName)
                put("bytes", packed.size)
                put("fileCount", plan.files.size)
                put("files", buildJsonArray { plan.files.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.file.name)) } })
            }
            onProgress(index + 1, plans.size)
        }
        val manifest = buildJsonObject {
            put("kind", "hualuo-courier")
            put("createdAt", Instant.now().toString())
            put("fileCount", files.size)
            put("totalRawBytes", totalRaw)
            put("volumes", buildJsonArray { volumeEntries.forEach { add(it) } })
            put("files", buildJsonArray {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("sizeBytes", f.sizeBytes)
                        put("sha256", sha256Of(f))
                    })
                }
            })
        }.toString()
        val manifestPath = targetPrefix + "manifest.json"
        try {
            uploader.upload(manifestPath, manifest.toByteArray(StandardCharsets.UTF_8), "courier: manifest.json（${files.size} 个文件的还原账）")
        } catch (e: Exception) {
            return CourierOutcome.Failed(
                "数据卷 ${plans.size} 个全部传完，只有 manifest 没传上（${e.message ?: "出错"}）：把同一批重投一遍即可，卷会原样覆盖",
            )
        }
        return CourierOutcome.Ok(manifestPath, plans.size, files.size, totalRaw)
    }

    /** 配卷：按原始字节配额切；装不进当前卷就从新卷开始，单文件永不劈开。 */
    private fun planVolumes(files: List<CourierFile>): List<VolumePlan> {
        val plans = ArrayList<VolumePlan>()
        var current = VolumePlan()
        files.forEach { file ->
            val size = file.sizeBytes.coerceAtLeast(0L)
            if (current.rawBytes > 0L && current.rawBytes + size > volumeBytes) {
                plans.add(current)
                current = VolumePlan()
            }
            current.files += PlannedFile(file, size)
            current.rawBytes += size
            if (size >= volumeBytes) {
                // 大件独占一卷：塞了它这卷就封口，下一个文件开新卷
                plans.add(current)
                current = VolumePlan()
            }
        }
        if (current.files.isNotEmpty()) plans.add(current)
        return plans
    }

    /** 打一个 zip 卷：条目名用原文件名（重名加序号），内容流式拷贝。 */
    private fun packVolume(plan: VolumePlan, volumeName: String): ByteArray {
        val buffer = ByteArrayOutputStream(if (plan.rawBytes in 1..MAX_PREALLOC_BYTES) plan.rawBytes.toInt() else 64 * 1024)
        ZipOutputStream(buffer).use { zip ->
            val seen = HashSet<String>()
            plan.files.forEachIndexed { index, planned ->
                var entryName = planned.file.name
                if (!seen.add(entryName)) {
                    entryName = "%03d-".format(index + 1) + entryName
                    seen.add(entryName)
                }
                zip.putNextEntry(ZipEntry(entryName))
                planned.file.open().use { streamingCopy(it, zip) }
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun sha256Of(file: CourierFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.open().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class VolumePlan {
        val files = ArrayList<PlannedFile>()
        var rawBytes = 0L
    }

    private class PlannedFile(val file: CourierFile, val sizeBytes: Long)

    companion object {
        /** 旧仓验证过的卷配额：32MB。 */
        const val DEFAULT_VOLUME_BYTES = 32 * 1024 * 1024

        /** 卷数上限：20 卷 × 32MB = 640MB 一批，够用也兜得住。 */
        const val DEFAULT_MAX_VOLUMES = 20

        /** 预分配上限：卷配额之内才按大小预分配缓冲，大件独占卷走默认 64KB 增量。 */
        private const val MAX_PREALLOC_BYTES = 128 * 1024 * 1024

        /**
         * 真网投递：GitHub contents API（PUT /repos/{owner}/{repo}/contents/{path}），
         * base64 一次一卷，落 [branch] 分支。令牌只进请求头，不进 URL 不进日志。
         * 卷与 manifest 都会覆盖同名旧文件（重投 = 原地修复，家规：不留墓碑也不怕重投）。
         */
        fun defaultUploader(owner: String, repo: String, branch: String, token: String): VolumeUploader =
            VolumeUploader { path, bytes, message ->
                val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
                val payload = buildJsonObject {
                    put("message", message)
                    put("branch", branch)
                    put("content", Base64.getEncoder().encodeToString(bytes))
                }.toString()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(payload.toByteArray(StandardCharsets.UTF_8).size)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Content-Type", "application/json")
                try {
                    conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val body = conn.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8)?.take(200) ?: ""
                        throw IOException2("HTTP $code $body")
                    }
                } finally {
                    conn.disconnect()
                }
            }

        /** 私有小名：避免与调用方的 java.io.IOException import 混读。 */
        private class IOException2(message: String) : RuntimeException(message)

        /** 给接线层的小工具：单文件直投（bytes 在手的小件），包成 CourierFile。 */
        fun singleFile(name: String, bytes: ByteArray): CourierFile =
            CourierFile(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}
