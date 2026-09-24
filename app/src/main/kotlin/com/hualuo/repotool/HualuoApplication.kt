package com.hualuo.repotool

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.api.ProviderRouting
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ModelSettingsRuntime
import com.hualuo.repotool.ui.state.ModelSettingsState
import com.hualuo.repotool.ui.state.UiPersistenceBundle
import com.hualuo.repotool.ui.state.WriteConfirmGate
import com.hualuo.repotool.ui.state.createUiPersistence

class OnceNotice(private val provide: () -> String?) {
    private var consumed = false
    fun consume(): String? = if (consumed) null else provide()?.also { consumed = true }
}

class HualuoApplication : Application() {
    val uiBundle: UiPersistenceBundle by lazy { createUiPersistence(this) }
    val modelSettings: ModelSettingsState by lazy {
        ModelSettingsState(uiBundle.persistence, changed = { uiBundle.persistence.flush() }).also {
            ModelSettingsRuntime.install(it)
            ProviderRouting.install(it::sessionFor)
        }
    }
    val writeGate = WriteConfirmGate()
    val uiState: AppUiState by lazy { AppUiState(uiBundle.persistence, uiBundle.store, writeGate) }
    private val startupNotice = OnceNotice { uiBundle.notice }
    fun consumeStartupNotice(): String? = startupNotice.consume()
    var backupProgress by mutableStateOf<String?>(null)
    var courierProgress by mutableStateOf<String?>(null)

    init {
        modelSettings
    }
}
