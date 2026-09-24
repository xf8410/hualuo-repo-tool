package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.api.ModelRef
import com.hualuo.engine.api.ProviderCatalog
import com.hualuo.engine.api.ProviderClient
import com.hualuo.engine.api.ProviderProfile
import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.settings.ModelSettings
import com.hualuo.engine.settings.ModelSettingsCodec
import com.hualuo.engine.settings.ProviderSettings

/** 供模型页和聊天模型弹层共同消费的一行真模型。 */
data class AvailableModel(
    val id: String,
    val providerId: String,
    val providerName: String,
    val modelName: String,
    val alias: String?,
    val enabled: Boolean,
)

/**
 * Agora 多提供商模型设置在 Hualuo 的活状态。
 *
 * 设置文件是唯一事实；这里只维护可观察快照。所有写操作都经 UiPersistence，
 * 不同步直接碰盘。同步模型时每家独立报成功或失败，一家坏的不清掉别家的真清单。
 */
class ModelSettingsState(
    private val persist: UiPersistence,
    private val transportFactory: () -> WireTransport = ::UrlConnTransport,
    private val worker: (Thread) -> Unit = { it.start() },
    private val changed: () -> Unit = {},
) {
    var settings: ModelSettings by mutableStateOf(loadInitial())
        private set
    var busyProviderId: String? by mutableStateOf(null)
        private set
    var errors: Map<String, String> by mutableStateOf(emptyMap())
        private set

    val activeProvider: ProviderSettings?
        get() = settings.provider(settings.activeProviderId)

    fun selectProvider(id: String) {
        if (settings.provider(id) == null) return
        update { it.copy(activeProviderId = id) }
    }

    fun configureProvider(id: String, baseUrl: String, apiKey: String): Boolean {
        if (settings.provider(id) == null) return false
        update {
            it.copy(providers = it.providers.map { item ->
                if (item.id == id) item.copy(baseUrl = baseUrl.trim(), apiKey = apiKey) else item
            })
        }
        return true
    }

    fun addCustomProvider(name: String, baseUrl: String, apiKey: String): String? {
        val id = name.trim()
        if (id.isEmpty()) return "提供商名称不能为空"
        if (id.contains(':')) return "提供商名称不能含冒号，冒号留给模型分隔"
        if (ProviderCatalog.byId(id) != null || settings.provider(id) != null) return "已经有同名的提供商"
        if (baseUrl.isBlank()) return "base URL 不能为空"
        update {
            it.copy(
                providers = it.providers + ProviderSettings(id, true, baseUrl.trim(), apiKey),
                activeProviderId = id,
            )
        }
        return null
    }

    fun deleteCustomProvider(id: String): String? {
        val provider = settings.provider(id) ?: return "这家提供商已经不在了"
        if (!provider.custom) return "内置提供商不能删，只能清空地址和密钥"
        val fallback = settings.providers.firstOrNull { it.id != id }?.id.orEmpty()
        update {
            it.copy(
                providers = it.providers.filterNot { item -> item.id == id },
                activeProviderId = if (it.activeProviderId == id) fallback else it.activeProviderId,
                availableModels = it.availableModels - id,
                enabledModels = it.enabledModels.filterNot { model ->
                    ModelRef.parse(model).providerId == id
                }.toSet(),
                aliases = it.aliases.filterKeys { ModelRef.parse(it).providerId != id },
            )
        }
        return null
    }

    fun setModelEnabled(modelId: String, enabled: Boolean) {
        update { current ->
            val next = if (enabled) current.enabledModels + modelId else current.enabledModels - modelId
            current.copy(enabledModels = next)
        }
    }

    fun setAlias(modelId: String, alias: String) {
        update { current ->
            val next = current.aliases.toMutableMap()
            if (alias.isBlank()) next.remove(modelId) else next[modelId] = alias.trim()
            current.copy(aliases = next)
        }
    }

    fun availableModels(): List<AvailableModel> = buildList {
        settings.availableModels.toSortedMap().forEach { (providerId, ids) ->
            val providerName = displayProviderName(providerId)
            ids.sorted().forEach { id ->
                val parsed = ModelRef.parse(id)
                add(
                    AvailableModel(
                        id = id,
                        providerId = providerId,
                        providerName = providerName,
                        modelName = parsed.model,
                        alias = settings.aliases[id],
                        enabled = id in settings.enabledModels,
                    ),
                )
            }
        }
    }.sortedWith(compareBy({ it.providerName }, { it.modelName }))

    fun selectedModels(): List<AvailableModel> = availableModels().filter { it.enabled }

    fun displayProviderName(id: String): String = ProviderCatalog.byId(id)?.displayName ?: id

    fun isConfigured(id: String): Boolean {
        val provider = settings.provider(id) ?: return false
        val definition = ProviderCatalog.byId(id)
        val base = provider.baseUrl.ifBlank { definition?.defaultBaseUrl.orEmpty() }
        if (base.isBlank()) return false
        return !definition?.keyRequired ?: true && provider.apiKey.isNotBlank()
    }

    fun sessionFor(rawModel: String): ProviderSession? {
        val parsed = ModelRef.parse(rawModel)
        val providerId = parsed.providerId.ifBlank { settings.activeProviderId }
        val provider = settings.provider(providerId) ?: return null
        val definition = ProviderCatalog.byId(providerId)
        val baseUrl = provider.baseUrl.ifBlank { definition?.defaultBaseUrl.orEmpty() }
        if (baseUrl.isBlank()) return null
        return ProviderSession(
            profile = ProviderProfile(
                name = displayProviderName(providerId),
                baseUrl = baseUrl,
                apiKey = provider.apiKey,
                model = parsed.model.ifBlank { rawModel },
            ),
            protocol = definition?.protocol ?: ProviderProtocol.OPENAI_COMPAT,
        )
    }

    fun refreshProvider(providerId: String) {
        if (busyProviderId != null) return
        val session = sessionFor("$providerId:")
        if (session == null) {
            errors = errors + (providerId to "先在提供商页填 base URL")
            return
        }
        if (!isConfigured(providerId)) {
            errors = errors + (providerId to "这家还没配好：内置提供商需要密钥，本地端点需要地址")
            return
        }
        busyProviderId = providerId
        errors = errors - providerId
        val body = Runnable {
            val client = ProviderClient(
                transportFactory(),
                GenerationSlot(),
                IdleWatchdog(IdleWatchdog.TRANSFER_IDLE_MS),
            )
            val listing = client.listModels(session)
            busyProviderId = null
            if (listing.error != null) {
                errors = errors + (providerId to listing.error.userMessage())
            } else if (listing.models.isEmpty()) {
                errors = errors + (providerId to "端点回话正常，但没有认出任何模型名")
            } else {
                val prefixed = listing.models.map {
                    "$providerId:${it.removePrefix("models/")}"
                }.distinct()
                update { current ->
                    current.copy(availableModels = current.availableModels + (providerId to prefixed))
                }
            }
        }
        worker(Thread(body).apply { name = "hualuo-model-sync" })
    }

    fun refreshAll() {
        if (busyProviderId != null) return
        settings.providers.map { it.id }.forEach { refreshProvider(it) }
    }

    private fun update(transform: (ModelSettings) -> ModelSettings) {
        val next = transform(settings)
        if (next == settings) return
        settings = next
        persist.save(ModelSettingsCodec.KEY, ModelSettingsCodec.encode(next))
        changed()
    }

    private fun loadInitial(): ModelSettings {
        val stored = persist.load(ModelSettingsCodec.KEY)
        if (!stored.isNullOrBlank()) return ModelSettingsCodec.decode(stored)
        return ModelSettingsCodec.migrateLegacy(
            persist.load(ChatRuntime.KEY_NAME),
            persist.load(ChatRuntime.KEY_BASE_URL),
            persist.load(ChatRuntime.KEY_API_KEY),
        )
    }
}
