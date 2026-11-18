package com.flashskip.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.flashskip.SkipAdService

/**
 * Exact permission, battery-optimization, and OEM keep-alive helpers.
 */
object PermissionHelper {

    /**
     * Accurately checks whether [SkipAdService] is enabled in system settings by parsing
     * colon-separated [ComponentName] entries rather than doing substring matching.
     */
    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, SkipAdService::class.java)
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        } ?: return false

        return enabled.split(':')
            .asSequence()
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .any { it == expected }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens App Info so Android 13+ users can enable "Allow restricted settings" from the top-right menu.
     */
    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        try {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(direct)
            return true
        } catch (_: Exception) {
        }
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun oemKeepAliveAdvice(): String {
        val brand = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("honor") ->
                "荣耀 MagicOS：应用启动管理 → 关闭自动管理 → 开启自启动/关联启动/后台活动，并在最近任务卡片下拉加锁。"
            brand.contains("huawei") ->
                "华为 EMUI/HarmonyOS：应用启动管理 → 关闭自动管理 → 全开三项，最近任务卡片下拉加锁。"
            brand.contains("xiaomi") || brand.contains("redmi") ->
                "小米 HyperOS/MIUI：开启自启动、省电策略设为无限制、关闭“闲置时删除权限”，最近任务加锁；Android 13+ 先在应用详情右上角允许受限设置。"
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "OPPO/一加 ColorOS：允许自启动与后台运行，最近任务锁定；若无障碍灰色不可点，先在应用详情右上角允许受限设置。"
            brand.contains("vivo") || brand.contains("iqoo") ->
                "vivo OriginOS：开启自启动并设后台高耗电允许，最近任务锁定。"
            else ->
                "建议在电池优化中将 FlashSkip 设为无限制，Android 13+ 若无障碍被限制请前往“应用详情 → 右上角菜单 → 允许受限设置”。"
        }
    }
}
