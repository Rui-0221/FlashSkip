package com.flashskip.keepalive

import java.util.Locale

data class KeepAliveGuide(val steps: List<String>)

internal data class OemSettingsPage(
    val packageName: String,
    val activity: String? = null,
    val action: String? = null,
    val activityType: Int? = null
)

internal enum class OemBackgroundSettings(
    private val aliases: Set<String>,
    val pages: List<OemSettingsPage>,
    val guide: KeepAliveGuide
) {
    HONOR(
        setOf("honor", "hihonor"),
        listOf(
            OemSettingsPage(
                "com.hihonor.systemmanager",
                ".startupmgr.ui.StartupNormalAppListActivity",
                "hihonor.intent.action.HSM_STARTUPAPP_MANAGER"
            ),
            OemSettingsPage("com.hihonor.systemmanager", ".appcontrol.activity.StartupAppControlActivity"),
            OemSettingsPage("com.huawei.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity")
        ),
        launchManagementGuide()
    ),
    HUAWEI(
        setOf("huawei"),
        listOf(
            OemSettingsPage("com.huawei.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"),
            OemSettingsPage("com.huawei.systemmanager", ".appcontrol.activity.StartupAppControlActivity")
        ),
        launchManagementGuide()
    ),
    XIAOMI(
        setOf("xiaomi", "redmi", "poco", "blackshark"),
        listOf(OemSettingsPage("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        KeepAliveGuide(listOf(
            "打开手机设置，搜索「后台自启动」或「自启动」。",
            "找到 FlashSkip，打开旁边的开关。",
            "回到手机设置，打开「应用管理 → FlashSkip → 省电策略」，选择「无限制」。"
        ))
    ),
    COLOR_OS(
        setOf("oppo", "oneplus", "realme"),
        listOf(
            OemSettingsPage("com.coloros.safecenter", ".permission.startup.StartupAppListActivity"),
            OemSettingsPage("com.coloros.safecenter", ".startupapp.StartupAppListActivity"),
            OemSettingsPage("com.oppo.safe", ".permission.startup.StartupAppListActivity"),
            OemSettingsPage("com.oneplus.security", ".chainlaunch.view.ChainLaunchAppListActivity")
        ),
        KeepAliveGuide(listOf(
            "打开手机设置，在「应用」里搜索「自启动」，允许 FlashSkip 自启动。",
            "进入「应用管理 → FlashSkip → 耗电管理」或「电池用量」。",
            "如果有「允许后台活动」或「允许后台运行」，把它打开。"
        ))
    ),
    VIVO(
        setOf("vivo", "iqoo"),
        listOf(
            OemSettingsPage("com.vivo.permissionmanager", ".activity.BgStartUpManagerActivity"),
            OemSettingsPage("com.iqoo.secure", ".ui.phoneoptimize.BgStartUpManager")
        ),
        KeepAliveGuide(listOf(
            "打开手机设置，搜索「自启动」，允许 FlashSkip 自启动。",
            "在手机设置里搜索「后台耗电管理」或「后台高耗电」。",
            "找到 FlashSkip，允许后台运行或选择「允许后台高耗电」。"
        ))
    ),
    SAMSUNG(
        setOf("samsung"),
        listOf(OemSettingsPage(
            "com.samsung.android.lool",
            action = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
            activityType = 2
        )),
        KeepAliveGuide(listOf(
            "打开手机设置，在「电池」或「设备维护」里找到「后台使用限制」。",
            "打开「从不休眠的应用程序」，点加号。",
            "选择 FlashSkip，然后点「添加」。"
        ))
    ),
    MEIZU(
        setOf("meizu"),
        listOf(OemSettingsPage("com.meizu.safe", ".permission.SmartBGActivity")),
        KeepAliveGuide(listOf(
            "打开「手机管家」，进入「权限管理」。",
            "在「自启动」里允许 FlashSkip 自启动。",
            "在「后台管理」里找到 FlashSkip，选择「允许后台运行」。"
        ))
    ),
    GENERIC(
        emptySet(),
        emptyList(),
        KeepAliveGuide(listOf(
            "打开手机设置，进入「应用 → FlashSkip → 电池」。",
            "选择「无限制」，或打开「允许后台使用」后选择「不受限制」。",
            "如果应用详情里没有这个选项，在设置里搜索「电池优化」，找到 FlashSkip 后选择「不优化」。"
        ))
    );

    companion object {
        fun forDevice(manufacturer: String, brand: String): OemBackgroundSettings {
            val words = "$brand $manufacturer".lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
            return words.firstNotNullOfOrNull { word -> entries.firstOrNull { word in it.aliases } } ?: GENERIC
        }
    }
}

private fun launchManagementGuide() = KeepAliveGuide(listOf(
    "打开手机设置，搜索并进入「应用启动管理」。",
    "找到 FlashSkip，关闭旁边的「自动管理」。",
    "在弹出的手动管理窗口里打开「允许自启动」「允许关联启动」和「允许后台活动」，然后点「确定」。"
))
