package com.hualuo.engine.settings

import com.hualuo.engine.api.ModelRef
import com.hualuo.engine.api.ProviderCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 用户自定义的 OpenAI 兼容提供商。 */
data class CustomProvider(val name: String, val baseUrl: String)

/** 一家提供商的当前设置；内置项没有 name 字段，名字由目录提供。 */
data class ProviderSettings(
    val id: String,
    val custom: Boolean,
    val baseUrl: String,
    val apiKey: String,
)

/** 模型设置的单份事实。值序列化进 settings 文件，未知字段不参与运行。 */
data class ModelSettings(
    val providers: List<ProviderSettings>,
    val activeProviderId: String,
    val availableModels: Map<String, List<String>>,
    val enabledModels: Set<String>,
    val aliases: Map<String, String>,
) {
    fun provider(id: String): ProviderSettings? = providers.firstOrNull { it.id == id }
}

/**
 * 模型设置 JSON 编解码。Agora 的多 DataStore 字段压成 settings 文件里的一个值，
 * 键名和语义保持一致：多提供商、激活钥匙、模型清单、启用集合与别名。
 */
object ModelSettingsCodec {
    const val KEY = "model.settings_json"
    const val FORMAT = 1
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun defaultSettings(): ModelSettings = ModelSettings(
        providers = ProviderCatalog.builtIns.map { ProviderSettings(it.id, false, "", "") },
        activeProviderId = "openai",
        availableModels = emptyMap(),
        enabledModels = emptySet(),
        aliases = emptyMap(),
    )

    fun encode(settings: ModelSettings): String = buildJsonObject {
        put("format", FORMAT)
        put("active_provider", settings.activeProviderId)
        putJsonArray("providers") {
            settings.providers.forEach { p ->
                addJsonObject {
                    put("id", p.id)
                    put("custom", p.custom)
                    put("base_url", p.baseUrl)
                    put("api_key", p.apiKey)
                }
            }
        }
        putJsonObject("available_models") {
            settings.availableModels.toSortedMap().forEach { (id, models) ->
                putJsonArray(id) { models.sorted().forEach { add(JsonPrimitive(it)) } }
            }
        }
        putJsonArray("enabled_models") {
            settings.enabledModels.sorted().forEach { add(JsonPrimitive(it)) }
        }
        putJsonObject("aliases") {
            settings.aliases.toSortedMap().forEach { (id, alias) -> put(id, alias) }
        }
    }.toString()

    fun decode(raw: String?): ModelSettings {
        if (raw.isNullOrBlank()) return defaultSettings()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return defaultSettings()
        val providers = mutableListOf<ProviderSettings>()
        val seen = mutableSetOf<String>()
        val providerArray = root["providers"] as? JsonArray ?: JsonArray(emptyList())
        providerArray.forEach { element ->
            val p = element as? JsonObject ?: return@forEach
            val id = p.primitive("id") ?: return@forEach
            if (!seen.add(id)) return@forEach
            providers += ProviderSettings(
                id = id,
                custom = p.primitive("custom")?.toBooleanStrictOrNull()
                    ?: (ProviderCatalog.byId(id) == null),
                baseUrl = p.primitive("base_url").orEmpty(),
                apiKey = p.primitive("api_key").orEmpty(),
            )
        }
        val defaults = defaultSettings()
        if (providers.isEmpty()) providers += defaults.providers
        val available = linkedMapOf<String, List<String>>()
        val availableObject = root["available_models"] as? JsonObject
        availableObject?.forEach { (id, value) ->
            val array = value as? JsonArray ?: return@forEach
            val models = array.mapNotNull { it.primitiveValue() }.distinct()
            if (models.isNotEmpty()) available[id] = models
        }
        val enabledArray = root["enabled_models"] as? JsonArray ?: JsonArray(emptyList())
        val enabled = enabledArray.mapNotNull { it.primitiveValue() }
            .filter { it.contains(":") }.toSet()
        val aliases = linkedMapOf<String, String>()
        val aliasObject = root["aliases"] as? JsonObject
        aliasObject?.forEach { (id, value) ->
            val alias = value.primitiveValue()
            if (!alias.isNullOrBlank()) aliases[id] = alias
        }
        val requestedActive = root.primitive("active_provider")
        val active = requestedActive?.takeIf { id -> providers.any { it.id == id } }
            ?: providers.first().id
        return ModelSettings(providers, active, available, enabled, aliases)
    }

    /** 把旧三键迁进新设置；只在新键不存在时调用，老用户原配置不丢。 */
    fun migrateLegacy(legacyName: String?, legacyBaseUrl: String?, legacyApiKey: String?): ModelSettings {
        val base = defaultSettings()
        if (legacyBaseUrl.isNullOrBlank()) return base
        val requested = legacyName.orEmpty().trim()
        val id = requested.takeIf { it.isNotEmpty() && ProviderCatalog.byId(it) == null } ?: "custom"
        return base.copy(
            providers = base.providers + ProviderSettings(
                id = id,
                custom = true,
                baseUrl = legacyBaseUrl.trim(),
                apiKey = legacyApiKey.orEmpty(),
            ),
            activeProviderId = id,
        )
    }

    fun displayModel(raw: String, settings: ModelSettings): String =
        settings.aliases[raw] ?: ModelRef.parse(raw).model

    fun providerForModel(raw: String, settings: ModelSettings): String? =
        ModelRef.parse(raw).providerId.takeIf { it.isNotBlank() }
            ?: settings.availableModels.entries.firstOrNull { (_, models) ->
                models.any { ModelRef.parse(it).model == raw }
            }?.key
}

private fun JsonObject.primitive(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement.primitiveValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
