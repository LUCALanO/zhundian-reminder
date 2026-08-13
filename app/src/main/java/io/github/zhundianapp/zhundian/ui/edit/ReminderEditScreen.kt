package io.github.zhundianapp.zhundian.ui.edit

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import android.content.Context
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.ui.components.AppTopBar
import io.github.zhundianapp.zhundian.ui.components.OverlayPermissionHint
import io.github.zhundianapp.zhundian.ui.components.RingtonePickerRow
import io.github.zhundianapp.zhundian.ui.components.SettingRow
import io.github.zhundianapp.zhundian.ui.components.SoundDurationRow
import io.github.zhundianapp.zhundian.ui.components.SoundVolumeRow
import io.github.zhundianapp.zhundian.ui.components.TimePickerDialog
import io.github.zhundianapp.zhundian.util.IntervalFormatter
import java.time.LocalTime
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditScreen(
    reminderId: Long?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val permissionManager =
        (context.applicationContext as IntervalReminderApp).container.permissionManager
    val viewModel: ReminderEditViewModel = viewModel(
        key = "edit_${reminderId ?: "new"}",
        factory = viewModelFactory {
            initializer {
                ReminderEditViewModel(
                    repository = (context.applicationContext as IntervalReminderApp)
                        .container.reminderRepository,
                    reminderId = reminderId
                )
            }
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (reminderId == null) R.string.create_title else R.string.edit_title
                ),
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
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.reminder_name)) },
                placeholder = { Text(stringResource(R.string.reminder_name_hint)) },
                singleLine = true,
                isError = state.nameError,
                supportingText = if (state.nameError) {
                    { Text(stringResource(R.string.name_required)) }
                } else null
            )

            OutlinedTextField(
                value = state.intervalValue,
                onValueChange = viewModel::onIntervalValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.interval)) },
                singleLine = true,
                isError = state.intervalError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = if (state.intervalError) {
                    { Text(stringResource(R.string.interval_invalid)) }
                } else null
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntervalUnit.entries.forEach { unit ->
                    FilterChip(
                        selected = state.intervalUnit == unit,
                        onClick = { viewModel.onUnitChange(unit) },
                        label = { Text(unitLabel(context, unit)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }

            if (state.intervalUnit == IntervalUnit.DAYS) {
                TriggerTimeRow(
                    minuteOfDay = state.triggerTimeMinutes,
                    onPick = viewModel::onTriggerTimeChange
                )
            }

            OutlinedTextField(
                value = state.message,
                onValueChange = viewModel::onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.reminder_message)) },
                placeholder = { Text(stringResource(R.string.notification_content)) },
                supportingText = { Text(stringResource(R.string.reminder_message_hint)) }
            )

            SettingRow(
                title = stringResource(R.string.sound_enabled),
                subtitle = stringResource(R.string.sound_hint),
                checked = state.soundEnabled,
                onCheckedChange = viewModel::onSoundChange
            )

            if (state.soundEnabled) {
                RingtonePickerRow(
                    currentUri = state.soundUri,
                    onPick = viewModel::onSoundUriChange
                )
                SoundVolumeRow(
                    volume = state.soundVolume,
                    onVolumeChange = viewModel::onSoundVolumeChange
                )
                SoundDurationRow(
                    selected = state.soundDurationSeconds,
                    onSelect = viewModel::onSoundDurationChange
                )
            }

            SettingRow(
                title = stringResource(R.string.vibration_enabled),
                subtitle = stringResource(R.string.vibration_hint),
                checked = state.vibrationEnabled,
                onCheckedChange = viewModel::onVibrationChange
            )

            SettingRow(
                title = stringResource(R.string.overlay_enabled),
                subtitle = stringResource(R.string.overlay_hint),
                checked = state.overlayEnabled,
                onCheckedChange = viewModel::onOverlayChange
            )
            if (state.overlayEnabled && !permissionManager.hasOverlayPermission()) {
                OverlayPermissionHint(onOpenOverlay = permissionManager::openOverlaySettings)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun unitLabel(context: Context, unit: IntervalUnit): String = when (unit) {
    IntervalUnit.MINUTES -> context.getString(R.string.interval_unit_minutes)
    IntervalUnit.HOURS -> context.getString(R.string.interval_unit_hours)
    IntervalUnit.DAYS -> context.getString(R.string.interval_unit_days)
}

/**
 * 触发时刻行：仅「天」间隔显示。点击弹出时间选择器，选中值即时预览。
 * 到点固定在该时刻触发，之后每 N 天按此节奏推进。
 */
@Composable
private fun TriggerTimeRow(
    minuteOfDay: Int,
    onPick: (hour: Int, minute: Int) -> Unit
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    val timeText = remember(minuteOfDay) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.trigger_time),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.trigger_time_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = timeText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }

    if (showPicker) {
        TimePickerDialog(
            title = stringResource(R.string.trigger_time),
            initial = LocalTime.of(hour, minute),
            onConfirm = { time ->
                onPick(time.hour, time.minute)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
