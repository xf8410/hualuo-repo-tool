package com.hualuo.repotool.ui.state

import com.hualuo.engine.settings.SettingsStore

interface UiPersistence {
    fun load(key: String): String?
    fun save(key: String, value: String)
    fun flush(): String?
    fun drainMessages(): List<String>

    companion object {
        val None: UiPersistence = object : UiPersistence {
            override fun load(key: String): String? = null
            override fun save(key: String, value: String) = Unit
            override fun flush(): String? = null
            override fun drainMessages(): List<String> = emptyList()
        }
    }
}

class SettingsUiPersistence(private val store: SettingsStore) : UiPersistence {
    override fun load(key: String): String? = store.raw(key)
    override fun save(key: String, value: String) { store.setString(key, value) }
    override fun flush(): String? = store.saveIfDirty()?.let { if (it.persisted) null else it.failure ?: "写入失败（原因未给出）" }
    override fun drainMessages(): List<String> = store.drainIssues().map { it.detail }
}

object UiKeys {
    const val TAB = "ui.tab"
    const val DRAFT = "ui.draft"
    const val MODEL = "ui.model"
    const val THINK_ON = "ui.think_on"
    const val THINK_LEVEL = "ui.think_level"
    const val WEB_SEARCH_ON = "ui.web_search_on"
    const val SHELL_ON = "ui.shell_on"
    const val CODE_EXEC_ON = "ui.code_exec_on"
    const val RELAY_ON = "ui.relay_on"
    const val LOCK_TO_CONVERSATION = "ui.lock_to_conversation"
    const val MAX_HISTORY = "ui.max_history_turns"
    const val GITHUB_REPO = "github.repo"
    const val GITHUB_TOKEN = "github.token"
    const val GITHUB_LOGIN = "github.login"
    const val GITHUB_SCOPES = "github.scopes"
    const val CI_NOTIFY = "ui.ci_notify_on"
    const val CI_LAST_RUN_ID = "ci.last_run_id"
    const val COURIER_REPO = "courier.repo"
    const val COURIER_BRANCH = "courier.branch"
    const val COURIER_TOKEN = "courier.token"
    const val IMAGE_GEN_KEY = "imagegen.key"
    const val IMAGE_GEN_BASE_URL = "imagegen.base_url"
    const val IMAGE_GEN_MODEL = "imagegen.model"
    const val IMAGE_GEN_SIZE = "imagegen.size"
    const val VISION_MODEL = "vision.model"
}
