package io.github.zhundianapp.zhundian.permission

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 通知、精确闹钟、电池优化与国产 ROM 保活相关权限的检查、请求与设置跳转。 */
class PermissionManager(private val context: Context) {

    /** 通知权限（Android 13+ 为运行时权限；更早恒为已授予）。 */
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /** 是否可调度精确闹钟。 */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** 是否已授予系统日历读取权限（运行时权限，用于同步系统日历日程）。 */
    fun hasCalendarReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    /** 跳转系统设置授予精确闹钟权限。 */
    fun openExactAlarmSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 是否已加入电池优化白名单。 */
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 申请加入电池优化白名单的 Intent。
     * 该授权对话框须由前台 Activity 通过 ActivityResult 拉起，不要直接 startActivity。
     */
    fun ignoreBatteryOptimizationsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 跳转国产 ROM / 三星的「自启动 / 后台管理」设置页；无匹配或跳转失败时回退到应用详情页。 */
    fun openAutostartSettings() {
        if (openFirstResolvable(autostartCandidatesFor(romKey()))) return
        openAppDetailsSettings()
    }

    /** 应用详情设置页（Flyme 上可从「权限管理」进入自启动管理）。 */
    fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 跳转应用的通知设置页。 */
    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 是否已授予「显示在其他应用上层」悬浮窗权限。 */
    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    /** 跳转系统「显示在其他应用上层」设置页。 */
    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 当前 ROM 的规范厂商标识，供 UI 定制引导文案（"other" 表示无法识别的系统）。
     * 同时用 Build.MANUFACTURER 与 Build.BRAND，提升个别设备 brand 更准确的覆盖率。
     */
    fun romKey(): String {
        val source = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            source.contains("xiaomi") || source.contains("redmi") -> "xiaomi"
            source.contains("honor") -> "honor"
            source.contains("huawei") -> "huawei"
            source.contains("oppo") || source.contains("realme") || source.contains("oneplus") -> "oppo"
            source.contains("vivo") || source.contains("iqoo") -> "vivo"
            source.contains("samsung") -> "samsung"
            source.contains("meizu") -> "meizu"
            else -> "other"
        }
    }

    // ===== 厂商「自启动 / 后台管理」入口候选 =====
    // 国产 ROM / 三星各自维护多条候选，应对系统版本升级导致入口路径变化；
    // [openFirstResolvable] 逐条 resolveActivity 探测，命中才跳转，全失败回退应用详情页。
    private val autostartCandidates: Map<String, List<String>> = mapOf(
        // 小米 / 红米（MIUI / HyperOS）
        "xiaomi" to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter/com.miui.securitycenter.autostart.AutoStartActivity"
        ),
        // 华为（HarmonyOS 4.x）
        "huawei" to listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity"
        ),
        // 荣耀（MagicOS，系统包为 com.hihonor.*）
        "honor" to listOf(
            "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager/com.hihonor.systemmanager.appcontrol.ui.AppPermissionActivity"
        ),
        // OPPO / Realme / 一加（ColorOS）
        "oppo" to listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        // vivo / iQOO（OriginOS）
        "vivo" to listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpActivity"
        ),
        // 魅族（Flyme）
        "meizu" to listOf(
            "com.meizu.securitycenter/com.meizu.securitycenter.permission.BgStartUpActivity",
            "com.meizu.securitycenter/com.meizu.securitycenter.autostart.AutoStartActivity"
        ),
        // 三星（One UI）：智能管理器 / 设备维护 → 电池页，「从不休眠的应用」入口在此
        "samsung" to listOf(
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.AppPowerManagementActivity"
        )
    )

    private fun autostartCandidatesFor(key: String): List<String> =
        autostartCandidates[key].orEmpty()

    /** 逐条探测候选 Activity 是否可解析，命中即启动；全部失败返回 false。 */
    private fun openFirstResolvable(candidates: List<String>): Boolean {
        val pm = context.packageManager
        for (candidate in candidates) {
            val separator = candidate.indexOf('/')
            if (separator <= 0) continue
            val pkg = candidate.substring(0, separator)
            val cls = candidate.substring(separator + 1)
            val intent = Intent().apply {
                setClassName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 部分 ROM 的入口 Activity 未导出，resolveActivity 可能返回 null，跳过尝试下一条
            if (pm.resolveActivity(intent, 0) == null) continue
            runCatching { context.startActivity(intent) }.onSuccess { return true }
        }
        return false
    }
}
