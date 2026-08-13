package io.github.zhundianapp.zhundian.ui.settings

import android.Manifest
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.CalendarSourceInfo
import io.github.zhundianapp.zhundian.ui.components.AppTopBar
import io.github.zhundianapp.zhundian.ui.components.OverlayPermissionHint
import io.github.zhundianapp.zhundian.ui.components.RingtonePickerRow
import io.github.zhundianapp.zhundian.ui.components.SettingRow
import io.github.zhundianapp.zhundian.ui.components.SoundDurationRow
import io.github.zhundianapp.zhundian.ui.components.SoundVolumeRow

/**
 * 系统日历日程提醒的全局设置页。
 * 提醒方式（铃声/时长/音量/震动/顶部弹窗）默认即用户要求：3 秒铃声 + 震动 + 弹窗，
 * 可全局修改，且完全独立于间隔提醒的每提醒设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IntervalReminderApp).container
    val permissionManager = container.permissionManager
    val viewModel: CalendarSettingsViewModel = viewModel(
        key = "calendar_settings",
        factory = viewModelFactory {
            initializer {
                CalendarSettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    calendarEventRepository = container.calendarEventRepository
                )
            }
        }
    )
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()

    // 「同步哪些日历」需要读取日历权限；授权成功后重载日历列表
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.loadCalendarSources() }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.calendar_settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingRow(
                title = stringResource(R.string.calendar_auto_sync),
                subtitle = stringResource(R.string.calendar_auto_sync_hint),
                checked = settings.autoSyncEnabled,
                onCheckedChange = viewModel::onAutoSyncChange
            )

            HorizontalDivider()
            Text(
                text = stringResource(R.string.calendar_sync_sources_title),
                style = MaterialTheme.typography.titleMedium
            )

            val hasCalendarPermission = permissionManager.hasCalendarReadPermission()
            if (!hasCalendarPermission) {
                Text(
                    text = stringResource(R.string.calendar_sources_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                    Text(stringResource(R.string.grant_permission))
                }
            } else if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_sources_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                sources.forEach { source ->
                    // 未配置显式集合时按默认策略显示：只读系统日历默认不勾选
                    val selected =
                        settings.enabledCalendarIds?.contains(source.id) ?: !source.isReadOnly
                    CalendarSourceRow(
                        source = source,
                        selected = selected,
                        onCheckedChange = { viewModel.onCalendarToggle(source.id, it) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.calendar_sync_sources_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = settings.leadMinutes.toString(),
                onValueChange = viewModel::onLeadMinutesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.calendar_lead_minutes)) },
                supportingText = { Text(stringResource(R.string.calendar_lead_minutes_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            SettingRow(
                title = stringResource(R.string.sound_enabled),
                subtitle = stringResource(R.string.sound_hint),
                checked = settings.soundEnabled,
                onCheckedChange = viewModel::onSoundChange
            )

            if (settings.soundEnabled) {
                RingtonePickerRow(
                    currentUri = settings.soundUri,
                    onPick = viewModel::onSoundUriChange
                )
                SoundVolumeRow(
                    volume = settings.soundVolume,
                    onVolumeChange = viewModel::onSoundVolumeChange
                )
                SoundDurationRow(
                    selected = settings.soundDurationSeconds,
                    onSelect = viewModel::onSoundDurationChange
                )
            }

            SettingRow(
                title = stringResource(R.string.vibration_enabled),
                subtitle = stringResource(R.string.vibration_hint),
                checked = settings.vibrationEnabled,
                onCheckedChange = viewModel::onVibrationChange
            )

            SettingRow(
                title = stringResource(R.string.overlay_enabled),
                subtitle = stringResource(R.string.overlay_hint),
                checked = settings.overlayEnabled,
                onCheckedChange = viewModel::onOverlayChange
            )
            if (settings.overlayEnabled && !permissionManager.hasOverlayPermission()) {
                OverlayPermissionHint(onOpenOverlay = permissionManager::openOverlaySettings)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.calendar_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 「同步哪些日历」中的一行：日历名（只读日历标注「系统只读」）+ 账号 + Checkbox。 */
@Composable
private fun CalendarSourceRow(
    source: CalendarSourceInfo,
    selected: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val readOnlyTag = if (source.isReadOnly) {
        "（${stringResource(R.string.calendar_source_read_only)}）"
    } else {
        ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = source.displayName + readOnlyTag,
                style = MaterialTheme.typography.bodyLarge
            )
            if (source.accountName.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = source.accountName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = selected, onCheckedChange = onCheckedChange)
    }
}
