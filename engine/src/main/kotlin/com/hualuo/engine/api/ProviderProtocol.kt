package com.hualuo.engine.api

/**
 * 远端提供商协议。内置目录里的每家只声明一次，请求体、鉴权头、模型列表和
 * 流解析都从这里路由，避免把提供商名字散落成一堆 if。
 */
enum class ProviderProtocol {
    OPENAI_COMPAT,
    GEMINI,
    ANTHROPIC,
    OLLAMA,
}

/** 一家内置提供商的不可变目录项；用户覆盖的 base URL 不写回这里。 */
data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val protocol: ProviderProtocol,
    val keyRequired: Boolean,
)

/** 内置提供商目录，名称和默认地址对齐 Agora，模型名仍以端点实时清单为准。 */
object ProviderCatalog {
    val builtIns: List<ProviderDefinition> = listOf(
        ProviderDefinition("google", "Google", "https://generativelanguage.googleapis.com/v1beta", ProviderProtocol.GEMINI, true),
        ProviderDefinition("openai", "OpenAI", "https://api.openai.com/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("anthropic", "Anthropic", "https://api.anthropic.com/v1", ProviderProtocol.ANTHROPIC, true),
        ProviderDefinition("deepseek", "DeepSeek", "https://api.deepseek.com/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("groq", "Groq", "https://api.groq.com/openai/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("ollama", "Ollama", "http://localhost:11434", ProviderProtocol.OLLAMA, false),
        ProviderDefinition("openrouter", "Open Router", "https://openrouter.ai/api/v1", ProviderProtocol.OPENAI_COMPAT, true),
    )

    fun byId(id: String): ProviderDefinition? = builtIns.firstOrNull { it.id == id }
}

/**
 * 模型引用统一存成 `provider:model`。带前缀是唯一的新格式；老的无前缀值只在
 * 读取时兼容，界面与请求都不再另造一套解析。
 */
data class ModelRef(val providerId: String, val model: String) {
    val prefixed: String get() = "$providerId:$model"

    companion object {
        fun parse(raw: String): ModelRef {
            val value = raw.trim()
            val index = value.indexOf(':')
            return if (index > 0 && index < value.lastIndex) {
                ModelRef(value.substring(0, index), value.substring(index + 1))
            } else {
                ModelRef("", value.removePrefix("models/"))
            }
        }
    }
}
