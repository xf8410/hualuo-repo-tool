package com.hualuo.engine.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份包的账：写读往返一字不差、身份对不上就拒收、认不出的条目列名照报、
 * 危险 id 写不进包也进不了本地。
 */
class BackupArchiveTest {

    private fun roundTrip(
        settingsText: String,
        sessions: List<BackupSessionSource>,
        appVersion: String = "0.4.0 (4)",
    ): BackupReadResult {
        val bytes = ByteArrayOutputStream()
        writeBackup(bytes, settingsText, sessions, appVersion)
        val seen = LinkedHashMap<String, String>()
        return readBackup(ByteArrayInputStream(bytes.toByteArray())) { id, stream ->
            seen[id] = stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun roundTripKeepsEverythingVerbatim() {
        val result = roundTrip(
            settingsText = "# ui\nprovider.name=测试端\nprovider.base_url=https://gw.example.com",
            sessions = listOf(
                BackupSessionSource("s-1") { "user: 你好\nassistant: 好".byteInputStream() },
                BackupSessionSource("s-2.b_9") { "half line".byteInputStream() },
            ),
        )

        assertEquals(BACKUP_FORMAT, result.manifest?.format)
        assertEquals(BACKUP_VERSION, result.manifest?.version)
        assertEquals("0.4.0 (4)", result.manifest?.appVersion)
        assertEquals(2, result.manifest?.sessionCount)
        assertEquals("设置原文一字不差", "# ui\nprovider.name=测试端\nprovider.base_url=https://gw.example.com", result.settingsText)
        assertEquals(2, result.sessionsHandled)
        assertEquals("user: 你好\nassistant: 好", seen["s-1"])
        assertEquals("half line", seen["s-2.b_9"])
        assertTrue(result.skippedEntries.isEmpty())
    }

    @Test
    fun emptyBackupStillCarriesIdentity() {
        val result = roundTrip(settingsText = "", sessions = emptyList())
        assertEquals(BACKUP_FORMAT, result.manifest?.format)
        assertEquals("", result.settingsText)
        assertEquals(0, result.sessionsHandled)
    }

    @Test
    fun foreignEntriesAreListedNotSwallowed() {
        val bytes = ByteArrayOutputStream()
        writeBackup(bytes, "a=b", listOf(BackupSessionSource("s-1") { "x".byteInputStream() }), "v")
        // 手工塞进一个陌生 zip：没 manifest、条目名字五花八门
        val foreign = java.util.zip.ZipOutputStream(ByteArrayOutputStream().also { it }.let { java.io.ByteArrayOutputStream() })
        // 上面的写法绕，直接独立造：
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("something-else.txt"))
            zip.write("hello".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("sessions/..%2Fevil.jsonl"))
            zip.write("nope".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val seen = LinkedHashMap<String, String>()
        val result = readBackup(java.io.ByteArrayInputStream(out.toByteArray())) { id, stream ->
            seen[id] = stream.readBytes().toString(Charsets.UTF_8)
        }
        assertNull("没有 manifest 就不是本家的包", result.manifest)
        assertEquals(0, result.sessionsHandled)
        assertTrue("认不出的条目要列出名字", result.skippedEntries.contains("something-else.txt"))
        assertEquals("settings 也没有", null, result.settingsText)
    }

    @Test
    fun dangerousSessionIdsAreRefusedOnWrite() {
        val bytes = ByteArrayOutputStream()
        try {
            writeBackup(
                bytes,
                "a=b",
                listOf(BackupSessionSource("../evil") { "x".byteInputStream() }),
                "v",
            )
            fail("路径意外的 id 不许进包")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("不安全"))
        }
    }

    @Test
    fun unsafeSessionEntryOnDiskIsSkippedOnRead() {
        // 邻居的会话 id 形状不对（带斜杠）——读包时不许落成文件
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(MANIFEST_ENTRY))
            zip.write(
                ("{\"format\":\"$BACKUP_FORMAT\",\"version\":$BACKUP_VERSION,\"createdAt\":\"t\"," +
                    "\"sessionCount\":1,\"appVersion\":\"v\"}").toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("sessions/a/b.jsonl"))
            zip.write("nope".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val result = readBackup(java.io.ByteArrayInputStream(out.toByteArray())) { _, _ ->
            fail("不安全的 id 不该回调进来")
        }
        assertEquals(BACKUP_FORMAT, result.manifest?.format)
        assertEquals(0, result.sessionsHandled)
        assertTrue(result.skippedEntries.isNotEmpty())
    }

    @Test
    fun truncatedEntryStillAdvances() {
        // 回调故意只读一半：引擎要自己排干剩余字节，包里后面的条目照样读得到
        val bytes = ByteArrayOutputStream()
        val big = "x".repeat(200_000)
        writeBackup(
            bytes,
            "k=v",
            listOf(BackupSessionSource("big") { big.byteInputStream() }),
            "v",
        )
        val seen = LinkedHashMap<String, Int>()
        val result = readBackup(ByteArrayInputStream(bytes.toByteArray())) { id, stream ->
            val head = stream.readNBytes(16).toString(Charsets.UTF_8)
            seen[id] = head.length
        }
        assertEquals(1, result.sessionsHandled)
        assertEquals("回调只拿了 16 字节，引擎排干剩下的", 16, seen["big"])
        assertEquals("k=v", result.settingsText)
    }
}
