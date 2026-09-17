package com.hualuo.engine.transfer

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投递引擎件的契约（上传走假缝隙，不碰真网）：配卷按文件边界、大件独占不劈开、
 * 卷重试耗尽出声、卷数封顶、manifest 全账（卷表+每文件 SHA-256）。
 */
class FileCourierClientTest {

    /** 假上传器：按需在头 N 次调用或指定路径上失败，其余照收。 */
    private class FakeUploader(
        private var failFirstN: Int = 0,
        private val failPaths: Set<String> = emptySet(),
    ) : VolumeUploader {
        val calls = ArrayList<Pair<String, ByteArray>>()
        val waits = ArrayList<Long>()

        override fun upload(path: String, bytes: ByteArray, message: String) {
            if (failPaths.contains(path)) {
                throw IOException("HTTP 502 蹦了")
            }
            if (failFirstN > 0) {
                failFirstN -= 1
                throw IOException("HTTP 500 假装网络抖")
            }
            calls.add(path to bytes)
        }
    }

    private fun file(name: String, size: Int): CourierFile {
        val content = ByteArray(size) { 'x' }
        return CourierFile(name, size.toLong()) { ByteArrayInputStream(content) }
    }

    private fun client(
        uploader: FakeUploader,
        volumeBytes: Int = 32 * 1024 * 1024,
        maxVolumes: Int = 20,
        maxRetries: Int = 2,
    ): FileCourierClient {
        val progress = ArrayList<Pair<Int, Int>>()
        return FileCourierClient(
            uploader, volumeBytes, maxVolumes, maxRetries,
            sleeper = { uploader.waits.add(it) },
            onProgress = { done, total -> progress.add(done to total) },
        )
    }

    private fun parse(captured: ByteArray): JsonObject =
        Json.parseToJsonElement(captured.toString(Charsets.UTF_8)) as JsonObject

    @Test
    fun emptyBatchFailsFast() {
        val uploader = FakeUploader()
        val outcome = FileCourierClient(uploader).deliver(emptyList(), "courier/t1/")
        assertTrue(outcome is CourierOutcome.Failed)
        assertEquals(0, uploader.calls.size)
    }

    @Test
    fun prefixMustEndWithSlash() {
        val outcome = FileCourierClient(FakeUploader()).deliver(listOf(file("a.bin", 10)), "courier/t1")
        assertTrue(outcome is CourierOutcome.Failed)
        assertTrue((outcome as CourierOutcome.Failed).reason.contains("斜杠"))
    }

    @Test
    fun smallBatchIsOneVolumePlusManifest() {
        val uploader = FakeUploader()
        val outcome = client(uploader).deliver(
            listOf(file("a.bin", 1024), file("b.bin", 1024), file("c.bin", 1024)),
            "courier/t2/",
        )
        assertTrue(outcome is CourierOutcome.Ok)
        val ok = outcome as CourierOutcome.Ok
        assertEquals(1, ok.volumeCount)
        assertEquals(3, ok.fileCount)
        assertEquals(3072L, ok.totalRawBytes)
        assertEquals("courier/t2/manifest.json", ok.manifestPath)
        assertEquals(2, uploader.calls.size)
        assertEquals("courier/t2/part_001.zip", uploader.calls[0].first)
        val manifest = parse(uploader.calls[1].second)
        assertEquals("hualuo-courier", (manifest["kind"] as JsonPrimitive).content)
        assertEquals(3, (manifest["fileCount"] as JsonPrimitive).content.toInt())
        val volumes = manifest["volumes"] as JsonArray
        assertEquals(1, volumes.size)
        val files = manifest["files"] as JsonArray
        assertEquals(64, ((files[0] as JsonObject)["sha256"] as JsonPrimitive).content.length)
    }

    @Test
    fun volumesSplitOnFileBoundaries() {
        val uploader = FakeUploader()
        val outcome = client(uploader, volumeBytes = 2048).deliver(
            listOf(file("a.bin", 1024), file("b.bin", 1024), file("c.bin", 1024)),
            "courier/t3/",
        )
        val ok = outcome as CourierOutcome.Ok
        assertEquals(2, ok.volumeCount)
        assertEquals("courier/t3/part_001.zip", uploader.calls[0].first)
        assertEquals("courier/t3/part_002.zip", uploader.calls[1].first)
    }

    @Test
    fun bigFileGetsItsOwnVolumeWithoutSplitting() {
        val uploader = FakeUploader()
        val outcome = client(uploader, volumeBytes = 1024).deliver(listOf(file("big.bin", 4096)), "courier/t4/")
        val ok = outcome as CourierOutcome.Ok
        assertEquals(1, ok.volumeCount)
        assertEquals(4096L, ok.totalRawBytes)
    }

    @Test
    fun volumeUploadRetriesThenSucceeds() {
        val uploader = FakeUploader(failFirstN = 1)
        val outcome = client(uploader, maxRetries = 2).deliver(listOf(file("a.bin", 512)), "courier/t5/")
        assertTrue(outcome is CourierOutcome.Ok)
        assertEquals(listOf(1000L, 2000L), uploader.waits)
        assertEquals(3, uploader.calls.size)
    }

    @Test
    fun retriesExhaustedFailsNamingTheVolume() {
        val uploader = FakeUploader(failPaths = setOf("courier/t6/part_001.zip"))
        val outcome = client(uploader, maxRetries = 1).deliver(listOf(file("a.bin", 512)), "courier/t6/")
        assertTrue(outcome is CourierOutcome.Failed)
        val reason = (outcome as CourierOutcome.Failed).reason
        assertTrue(reason.contains("part_001.zip"))
        assertTrue(reason.contains("连试 2 次"))
        assertEquals(listOf(1000L), uploader.waits)
    }

    @Test
    fun tooManyVolumesIsRejectedUpFront() {
        val uploader = FakeUploader()
        val files = (1..5).map { file("f$it.bin", 900) }
        val outcome = client(uploader, volumeBytes = 1024, maxVolumes = 3).deliver(files, "courier/t7/")
        assertTrue(outcome is CourierOutcome.Failed)
        assertTrue((outcome as CourierOutcome.Failed).reason.contains("超过上限"))
        assertEquals(0, uploader.calls.size)
    }

    @Test
    fun manifestFailureSaysDataVolumesAreSafe() {
        val uploader = FakeUploader(failPaths = setOf("courier/t8/manifest.json"))
        val outcome = client(uploader).deliver(listOf(file("a.bin", 512)), "courier/t8/")
        assertTrue(outcome is CourierOutcome.Failed)
        val reason = (outcome as CourierOutcome.Failed).reason
        assertTrue(reason.contains("全部传完"))
        assertTrue(reason.contains("manifest"))
    }

    @Test
    fun duplicateEntryNamesInsideOneVolumeDoNotCrash() {
        val uploader = FakeUploader()
        val outcome = client(uploader).deliver(listOf(file("a.bin", 512), file("a.bin", 512)), "courier/t9/")
        assertTrue(outcome is CourierOutcome.Ok)
        val volumes = (parse(uploader.calls[1].second)["volumes"] as JsonArray)
        val names = ((volumes[0] as JsonObject)["files"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("a.bin", "a.bin"), names)
    }
}
