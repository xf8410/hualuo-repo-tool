package com.hualuo.engine.api

/**
 * 现有 ChatRuntime 仍只传一个 ProviderProfile；这个运行桥把新模型设置接到那条稳定入口，
 * 不让 UI 状态层再复制一份提供商真相。未安装时为空，旧的 OpenAI 兼容测试与老设置照旧工作。
 */
object ProviderRouting {
    @Volatile private var resolver: ((String) -> ProviderSession?)? = null

    fun install(fn: (String) -> ProviderSession?) {
        resolver = fn
    }

    fun sessionFor(model: String): ProviderSession? = resolver?.invoke(model)
}
