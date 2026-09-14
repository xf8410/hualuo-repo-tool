package com.hualuo.repotool.ui.model

// 设置页的数据模型（从 UiModels 拆出来）。家规：图标只放 IconKey 键名，图形字符住 res/values/icons.xml。

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
