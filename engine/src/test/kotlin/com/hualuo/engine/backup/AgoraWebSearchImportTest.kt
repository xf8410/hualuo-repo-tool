package com.hualuo.engine.backup

import com.hualuo.engine.search.SearchProviders
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧备份里网页搜索那几格的兑换契约。
 *
 * 钉的四件事：
 *  - 提供商只认五家：认得的就兑，认不出的**不换**并在 notes 里点名（不许静默改用默认那家）；
 *  - 各家密钥按家分表兑；旧版密文（enc:v1: 开头，本机解不开）逐项报错并跳过，
 *    **不许静默置空**（docs/DECISIONS.md 的 D-10 第 4 条）；
 *  - 自托管实例地址只在真的要地址的那家才兑（别把 SearXNG 的地址塞给 Brave 那类键档服务）；
 *  - 旧包没带的那几格一律是 null：上层据此不写键，不许拿空串盖掉用户手填的值。
 */
class AgoraWebSearchImportTest {

    private fun zip(
        provider: String? = null,
        baseUrl: String? = null,
        keys: Map<String, String> = emptyMap(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("manifest.json", """{"agora_export_version":3,"categories":["settings","api_keys"]}""")
            val search = buildString {
                append("{")
                if (provider != null) append("\"webSearchProvider\":\"$provider\",")
                if (baseUrl != null) append("\"webSearchBaseUrl\":\"$baseUrl\",")
                append("\"webSearchEnabled\":true}")
            }
            put("settings.json", search)
            val keyJson = keys.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            put("api_keys.json", """{"apiKeys":[],"activeApiKeyIds":{},"webSearchApiKeys":{$keyJson}}""")
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = readAgoraBackup(ByteArrayInputStream(bytes))

    @Test
    fun knownProviderAndItsKeyAreRedeemed() {
        val plan = read(zip(provider = "serper", keys = mapOf("serper" to "sk-serper-1")))
        assertTrue(plan.recognized)
        assertEquals("serper", plan.webSearchProvider)
        assertEquals(mapOf("serper" to "sk-serper-1"), plan.webSearchApiKeys)
        assertTrue(plan.webSearchOn == true)
    }

    @Test
    fun keysOfSeveralProvidersStaySeparated() {
        val plan = read(
            zip(
                provider = "brave",
                keys = mapOf("brave" to "BSA-1", "tavily" to "tvly-2", "duckduckgo" to ""),
            ),
        )
        assertEquals("brave", plan.webSearchProvider)
        // 空串那把不算数（没配就是没配，不塞一个空钥匙进设置）
        assertEquals(mapOf("brave" to "BSA-1", "tavily" to "tvly-2"), plan.webSearchApiKeys)
    }

    @Test
    fun unknownProviderIsReportedAndNotSwitched() {
        val plan = read(zip(provider = "bing", keys = mapOf("bing" to "x")))
        assertNull("认不出的 id 不许替用户换家", plan.webSearchProvider)
        assertTrue(
            "要点名：${plan.notes}",
            plan.notes.any { it.contains("bing") && it.contains("网页搜索") },
        )
        assertTrue("不认的那家密钥也别导", plan.webSearchApiKeys.isEmpty())
    }

    @Test
    fun legacyEncryptedKeysAreNamedNotSilentlyBlanked() {
        val plan = read(
            zip(
                provider = "brave",
                keys = mapOf(
                    "brave" to AGORA_LEGACY_CIPHER_PREFIX + "ciphertext",
                    "serper" to "sk-plain",
                ),
            ),
        )
        assertEquals(mapOf("serper" to "sk-plain"), plan.webSearchApiKeys)
        assertTrue(
            "密文那把必须点名报错：${plan.notes}",
            plan.notes.any { it.contains("旧版加密") && it.contains("1") },
        )
    }

    @Test
    fun baseUrlOnlyLandsWhenThatProviderUsesOne() {
        val searx = read(zip(provider = "searxng", baseUrl = "https://searx.my.example/"))
        assertEquals("https://searx.my.example/", searx.webSearchBaseUrl)

        val brave = read(zip(provider = "brave", baseUrl = "https://searx.my.example/"))
        assertNull("键档服务不接实例地址", brave.webSearchBaseUrl)
    }

    @Test
    fun packageWithoutSearchSettingsLeavesEveryFieldEmpty() {
        val plan = read(zip())
        assertNull(plan.webSearchProvider)
        assertNull(plan.webSearchBaseUrl)
        assertTrue(plan.webSearchApiKeys.isEmpty())
        assertEquals(SearchProviders.DEFAULT_ID, "duckduckgo")
    }
}