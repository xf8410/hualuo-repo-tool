package com.hualuo.repotool.ui.model

// 设置页的数据模型（从 UiModels 拆出来：一个文件管一件事）。
// 家规：这里只放 IconKey 键名，图形字符住 res/values/icons.xml，源码不留裸字符也不留转义写法。

/** 设置主页一项（v13 .item）：图标键名 + 标题 + 一句大白话 + 右侧值 + 下钻 key。 */
data class SettingsItem(
    val iconKey: IconKey,
    val title: String,
    val desc: String,
    val value: String?,
    val subKey: String,
)

/** 设置主页一组（v13 .sec + 若干 item）。 */
data class SettingsSection(val id: String, val title: String, val items: List<SettingsItem>)

/** 设置二级页：标题 + 字段表。 */
data class SubPage(val title: String, val fields: List<SubField>)

/** 单选一项：名称 + 一句描述 + 是否需要 API Key。 */
data class RadioChoice(
    val id: String,
    val name: String,
    val desc: String,
    val needsKey: Boolean,
)

/** 设置二级页的字段类型，原型字段与真实设置字段保持同一渲染入口。 */
sealed class SubField {
    data class Row(val label: String, val value: String = "", val gotoKey: String? = null) : SubField()

    /** 演示态开关：只活在本次界面的 remember 里。 */
    data class Switch(val label: String, val on: Boolean) : SubField()

    /** 真设置开关：状态经 AppUiState 写进设置文件。 */
    data class PersistedSwitch(val label: String, val key: String, val defaultOn: Boolean) : SubField()

    /** 真设置文本框：值走 AppUiState 的 text/setText 通道。 */
    data class PersistedText(
        val label: String,
        val key: String,
        val placeholder: String = "",
        val secret: Boolean = false,
    ) : SubField()

    object GithubLogin : SubField()

    /** 多提供商与密钥设置页，渲染仍使用本文件外的既有卡片样式。 */
    object ProviderSettings : SubField()

    /** 真实模型目录、默认模型、启用模型和别名页。 */
    object ModelSettings : SubField()

    data class Seg(val label: String, val options: List<String>, val sel: Int) : SubField()
    data class Input(val label: String, val placeholder: String = "") : SubField()

    data class Slider(
        val label: String,
        val min: Double,
        val max: Double,
        val value: Double,
        val step: Double = 1.0,
    ) : SubField()

    data class Note(val text: String) : SubField()
    data class Sec(val text: String) : SubField()
    data class Action(val iconKey: IconKey, val title: String, val desc: String) : SubField()
    data class Head(val title: String, val desc: String) : SubField()
    data class BigInput(val placeholder: String) : SubField()
    data class Button(val text: String, val iconKey: IconKey? = null, val actionKey: String? = null) : SubField()
    data class Radio(val label: String, val options: List<RadioChoice>, val selId: String) : SubField()
}
