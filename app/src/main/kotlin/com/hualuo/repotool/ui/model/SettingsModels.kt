package com.hualuo.repotool.ui.model

// 设置页的数据模型（从 UiModels 拆出来：一个文件管一件事）。
// 家规：这里只放 IconKey 键名，图形字符住 res/values/icons.xml，源码不留裸字符也不留转义写法。

data class SettingsItem(
    val iconKey: IconKey,
    val title: String,
    val desc: String,
    val value: String?,
    val subKey: String,
)

data class SettingsSection(val id: String, val title: String, val items: List<SettingsItem>)

data class SubPage(val title: String, val fields: List<SubField>)

/**
 * 一家可选的搜索提供商（名称与说明）。
 *
 * 只留给**演示表**里的选择控件用；真设置页那五家由引擎件的事实表
 * （com.hualuo.engine.search.SearchProviders）说话，界面照那份渲染——
 * 两份 id 列表迟早漂移，所以真页不读这里。
 */
data class RadioChoice(
    val id: String,
    val name: String,
    val desc: String,
    val needsKey: Boolean,
)

sealed class SubField {
    data class Row(val label: String, val value: String = "", val gotoKey: String? = null) : SubField()
    data class Switch(val label: String, val on: Boolean) : SubField()
    data class PersistedSwitch(val label: String, val key: String, val defaultOn: Boolean) : SubField()
    data class PersistedText(
        val label: String,
        val key: String,
        val placeholder: String = "",
        val secret: Boolean = false,
    ) : SubField()

    /** 网页搜索那格真面板（开关、五家选择框、密钥、实例地址、条数都在里面）。 */
    object WebSearchSettings : SubField()

    object GithubLogin : SubField()
    object ProviderSettings : SubField()
    object ModelSettings : SubField()
    /** 存储占用卡（实装刀）：真统计各私有目录大小 + 缓存目录真清理。 */
    object StorageStats : SubField()
    /** 关于卡（实装刀）：版本真值 + 检查更新真查 + 提 issue 真 intent + 开源许可真弹层。 */
    object AboutCard : SubField()
    /** 记忆账卡（实装刀）：真记忆库统计 + 活动记忆查看 + 逐条删除（带确认）。 */
    object MemoryCard : SubField()
    /** CI 监视卡（实装刀）：真调 GitHub Actions API 列最近 run 状态。 */
    object CiRunsCard : SubField()
    /** 常用网站组（实装刀）：真 URL 跳浏览器（mine=自己仓清单，external=常用外站）。 */
    data class SiteRows(val title: String, val urls: List<Pair<String, String>>) : SubField()
    /** 沙盒状态卡（实装刀）：真探 rootfs/work/shared 目录（存在/大小/路径），不抄演示值。 */
    object SandboxStatusCard : SubField()
    /** 定时任务卡（实装刀）：真任务表（建/启停/删）+ 执行账；执行=开新会话发提示词。 */
    object TasksCard : SubField()
    data class Seg(val label: String, val options: List<String>, val sel: Int) : SubField()
    data class Input(val label: String, val placeholder: String = "") : SubField()

    /**
     * 落盘滑块（2026-10-04 实装刀）：拖动即写 [key]，值格式化到 [digits] 位小数存文本。
     * 旧 [Slider] 是原型演示数据（值写死、不落盘），只保留给还没实装的页面占位。
     */
    data class PersistedSlider(
        val label: String,
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val default: Double,
        val digits: Int = 2,
    ) : SubField()

    /**
     * 落盘选项组（同刀）：点选即写 [key]，存 [SegChoice.value]（不是显示名）。
     * 选项用 value 存盘、name 显示，跟 Radio 的既有形态对齐。
     */
    data class SegChoice(val name: String, val value: String)
    data class PersistedSeg(
        val label: String,
        val key: String,
        val options: List<SegChoice>,
        val default: String,
    ) : SubField()

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