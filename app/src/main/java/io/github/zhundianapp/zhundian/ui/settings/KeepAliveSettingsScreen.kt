package io.github.zhundianapp.zhundian.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.permission.PermissionManager
import io.github.zhundianapp.zhundian.ui.components.AppTopBar

/**
 * 后台保活设置聚合引导页。
 *
 * 检测当前 ROM，逐项列出需要放行的权限 / 系统设置（通知、精确闹钟、电池优化、悬浮窗、
 * 自启动/后台管理），带状态检测与一键跳转；从系统设置页返回后自动刷新状态。
 * 代码层已用「前台服务 + 进程内计时 + 精确闹钟兜底」保证尽力触发，本页负责引导用户放行，
 * 各 ROM 的差异主要在「自启动/后台管理」入口，由 [PermissionManager.openAutostartSettings] 按厂商跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val permissionManager =
        (context.applicationContext as IntervalReminderApp).container.permissionManager

    // 从系统设置页返回后重新评估各状态，让「已就绪」即时生效
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val states = remember(refreshTick) {
        PermissionStates(
            notification = permissionManager.hasNotificationPermission(),
            exactAlarm = permissionManager.canScheduleExact(),
            battery = permissionManager.isIgnoringBatteryOptimizations(),
            overlay = permissionManager.hasOverlayPermission()
        )
    }

    // 电池优化白名单授权对话框须由前台 Activity 拉起（Android 11+ 部分系统需手动跳电池设置）
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 状态由返回前台后的 refreshTick 重新评估 */ }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.keep_alive_settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ROM 识别卡片
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.keep_alive_rom_detected,
                            stringResource(romNameRes(permissionManager.romKey()))
                        ),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (permissionManager.romKey() == "other") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.keep_alive_rom_unknown_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.keep_alive_list_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            StatusActionRow(
                title = stringResource(R.string.keep_alive_notification),
                desc = stringResource(R.string.keep_alive_notification_desc),
                ready = states.notification,
                actionLabel = stringResource(R.string.keep_alive_action_open),
                onAction = permissionManager::openNotificationSettings
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                StatusActionRow(
                    title = stringResource(R.string.keep_alive_exact_alarm),
                    desc = stringResource(R.string.keep_alive_exact_alarm_desc),
                    ready = states.exactAlarm,
                    actionLabel = stringResource(R.string.keep_alive_action_open),
                    onAction = permissionManager::openExactAlarmSettings
                )
            }

            StatusActionRow(
                title = stringResource(R.string.keep_alive_battery),
                desc = stringResource(R.string.keep_alive_battery_desc),
                ready = states.battery,
                actionLabel = stringResource(R.string.keep_alive_action_open),
                onAction = { batteryLauncher.launch(permissionManager.ignoreBatteryOptimizationsIntent()) }
            )

            StatusActionRow(
                title = stringResource(R.string.keep_alive_overlay),
                desc = stringResource(R.string.keep_alive_overlay_desc),
                ready = states.overlay,
                actionLabel = stringResource(R.string.keep_alive_action_open),
                onAction = permissionManager::openOverlaySettings
            )

            // 自启动 / 后台管理：无标准状态检测，始终提供跳转入口
            StatusActionRow(
                title = stringResource(R.string.keep_alive_autostart),
                desc = stringResource(R.string.keep_alive_autostart_desc),
                ready = false,
                actionLabel = stringResource(R.string.keep_alive_action_settings),
                onAction = permissionManager::openAutostartSettings
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.keep_alive_autostart_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 如实说明后台可靠性边界：即使全部放行，系统激进省电策略仍可能以概率性推迟/阻止触发
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.keep_alive_reliability_note),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 保活相关权限/设置的一次性快照，返回前台时重新计算。 */
private data class PermissionStates(
    val notification: Boolean,
    val exactAlarm: Boolean,
    val battery: Boolean,
    val overlay: Boolean
)

/** ROM 识别 key → 显示名资源。 */
@Composable
private fun romNameRes(key: String): Int = when (key) {
    "xiaomi" -> R.string.rom_xiaomi
    "huawei" -> R.string.rom_huawei
    "honor" -> R.string.rom_honor
    "oppo" -> R.string.rom_oppo
    "vivo" -> R.string.rom_vivo
    "samsung" -> R.string.rom_samsung
    "meizu" -> R.string.rom_meizu
    else -> R.string.rom_other
}

/** 状态行：标题 + 说明；已就绪显示绿色状态字，否则显示操作按钮。 */
@Composable
private fun StatusActionRow(
    title: String,
    desc: String,
    ready: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            if (ready) {
                Text(
                    text = stringResource(R.string.keep_alive_status_ok),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
