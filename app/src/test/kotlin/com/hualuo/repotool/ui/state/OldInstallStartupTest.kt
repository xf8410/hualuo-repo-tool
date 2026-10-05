package com.hualuo.repotool.ui.state

import com.hualuo.engine.settings.FileSettingsStorage
import com.hualuo.engine.settings.SettingsStore
import com.hualuo.engine.store.SessionStore
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 老装机数据遇上新代码的启动复现测试（2026-09-30 闪退取证第二刀）。
 *
 * 背景：用户手里的可用安装包是「修复模型前」的老版本，覆盖装上新 CI 包就闪退
 * （debug 与 release 签名版都闪）。头号怀疑：老版本写出的设置/会话数据走进
 * 新代码的启动路径。启动链里能在纯 JVM 复刻的部分是
 * createUiPersistence + ModelSettingsState + AppUiState（Application 去掉安卓壳），
 * 这里按 Application 的真实顺序原样复刻，喂「老版本会写出的数据形状」：
 *  - 只有 legacy 三键（provider.name/base_url/api_key），没有 model.settings_json，
 *    逼 decode 走 migrateLegacy 兑换路径；
 *  - 老格式 model.settings_json（没有 custom_models、带陌生键、缺键）；
 *  - 各种读不懂的键值（非数字的历史条数、怪 tab 名、空串、超长串）；
 *  - 带坏行的老会话 + 没有头行的老会话文件。
 * 全部走完不许抛。哪个环节炸，CI 日志里的堆栈就是闪退根因的方向。
 *
 * 边界说明（不装懂）：Compose 组装与安卓框架层（WorkManager / edge-to-edge /
 * MediaStore）本测试够不着。若这里全绿而真机仍闪退，嫌疑就收敛到界面组装层，
 * 真机现场只能靠本分支的 CrashObserver 拿堆栈。
 */
class OldInstallStartupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 按真实启动顺序复刻 Application 构造链：持久化出口、模型设置、界面状态。 */
    private fun bootApp(seed: (SettingsStore) -> Unit): AppUiState {
        val store = SettingsStore(FileSettingsStorage(File(tmp.root, "settings/ui.properties")))
        seed(store)
        val persist = SettingsUiPersistence(store)
        val modelSettings = ModelSettingsState(persist)
        ModelSettingsRuntime.install(modelSettings)
        val sessions = SessionStore(File(tmp.root, "sessions"))
        return AppUiState(
            persist = persist,
            store = sessions,
            writeGate = WriteConfirmGate(),
            modelSettings = modelSettings,
            imageGenConfig = { null },
            imageGenPersist = { _, _ -> "" },
            watchInboxDir = File(tmp.root, "watch_inbox").apply { mkdirs() },
            watchFramesDir = File(tmp.root, "watch_frames").apply { mkdirs() },
        )
    }

    @Test
    fun 只有Legacy三键的旧设置走兑换路径不炸() {
        val state = bootApp { store ->
            store.setString("provider.name", "自定义端点")
            store.setString("provider.base_url", "https://example.com/v1")
            store.setString("provider.api_key", "sk-old-install")
        }
        assertTrue(state.chat.messages.isEmpty())
        state.flushPersistence()
    }

    @Test
    fun 老格式模型设置Json遇上新Decode不炸() {
        val state = bootApp { store ->
            store.setString(
                "model.settings_json",
                "{\"format\":1,\"active_provider\":\"openai\"," +
                    "\"providers\":[{\"id\":\"openai\",\"custom\":false," +
                    "\"base_url\":\"https://x/v1\",\"api_key\":\"k\",\"legacy_unknown\":\"?\"}]," +
                    "\"available_models\":{\"openai\":[\"openai:gpt\"]}," +
                    "\"enabled_models\":[\"openai:gpt\"]," +
                    "\"aliases\":{\"openai:gpt\":\"GPT\"}," +
                    "\"陌生键\":[1,2,3]}",
            )
        }
        assertTrue(state.repo != null)
        state.flushPersistence()
    }

    @Test
    fun 各种读不懂的键值不炸() {
        val state = bootApp { store ->
            store.setString("ui.tab", "NoSuchTab")
            store.setString("ui.max_history_turns", "abc")
            store.setString("ui.think_level", "")
            store.setString("ui.think_on", "perhaps")
            store.setString("ui.model", "")
            store.setString("ui.draft", "x".repeat(5000))
            store.setString("ci.last_run_id", "not-a-number")
        }
        assertTrue(state.input.length == 5000)
        state.flushPersistence()
    }

    @Test
    fun 带坏行与坏头的老会话不炸() {
        val state = bootApp { store ->
            store.setString("provider.base_url", "https://example.com/v1")
            // 一份老会话：头行之后混进坏行（老版本断电/写一半的常见现场）
            val sid = SessionStore(File(tmp.root, "sessions")).create("qwen3.8-flash")
            val f = File(File(tmp.root, "sessions"), "$sid.jsonl")
            f.appendText("这不是json\n")
            f.appendText("{\"k\":\"m\",\"role\":\"user\",\"text\":\"老话还在\",\"at\":1}\n")
            f.appendText("{\"k\":\"m\",\"role\":\"神秘角色\",\"text\":\"角色不认\",\"at\":2}\n")
            // 一份没有头行的老会话文件（头行被删的账，list() 要把它数成不可读）
            File(File(tmp.root, "sessions"), "old1.jsonl").writeText("没有头\n")
        }
        assertTrue(state.busy.not())
        state.flushPersistence()
    }

    @Test
    fun 停在最新改动最重的观测页Tab不炸() {
        val state = bootApp { store ->
            store.setString("ui.tab", "Observe")
            store.setString("observe.base", "http://127.0.0.1:18765")
        }
        assertEqualsTab(state)
    }

    private fun assertEqualsTab(state: AppUiState) {
        // 只验证构造与读键路径；tab 的界面组装在 JVM 够不着（见类头边界说明）
        assertTrue(state.observe.baseUrl.isNotBlank())
    }
}
