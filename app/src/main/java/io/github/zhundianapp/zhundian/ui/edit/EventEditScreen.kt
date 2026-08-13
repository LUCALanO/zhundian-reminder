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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.ui.components.AppTopBar
import io.github.zhundianapp.zhundian.ui.components.SettingRow
import io.github.zhundianapp.zhundian.ui.components.TimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

/**
 * 新建日程页：标题 / 日期 / 起止时间（或全天）/ 地点 / 备注 / 到点提醒。
 * 保存后仅存本 App，不写入系统日历。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(
    initialDate: LocalDate?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IntervalReminderApp).container
    val viewModel: EventEditViewModel = viewModel(
        key = "event_edit_${initialDate ?: "today"}",
        factory = viewModelFactory {
            initializer {
                EventEditViewModel(
                    repository = container.calendarEventRepository,
                    initialDate = initialDate
                )
            }
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.event_edit_title),
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
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.event_title)) },
                placeholder = { Text(stringResource(R.string.event_title_hint)) },
                singleLine = true,
                isError = state.titleError,
                supportingText = if (state.titleError) {
                    { Text(stringResource(R.string.event_title_required)) }
                } else null
            )

            FieldRow(
                label = stringResource(R.string.event_date),
                value = formatFullDate(state.date)
            ) { showDatePicker = true }

            SettingRow(
                title = stringResource(R.string.event_all_day),
                subtitle = stringResource(R.string.event_all_day_hint),
                checked = state.allDay,
                onCheckedChange = viewModel::onAllDayChange
            )

            if (!state.allDay) {
                FieldRow(
                    label = stringResource(R.string.event_start_time),
                    value = formatTime(context, state.date, state.startTime),
                    isError = state.timeError
                ) { showStartPicker = true }

                FieldRow(
                    label = stringResource(R.string.event_end_time),
                    value = formatTime(context, state.date, state.endTime),
                    isError = state.timeError
                ) { showEndPicker = true }

                if (state.timeError) {
                    Text(
                        text = stringResource(R.string.event_time_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            OutlinedTextField(
                value = state.location,
                onValueChange = viewModel::onLocationChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.event_location)) },
                singleLine = true
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.event_description)) },
                placeholder = { Text(stringResource(R.string.event_description_hint)) }
            )

            SettingRow(
                title = stringResource(R.string.event_remind),
                subtitle = stringResource(R.string.event_remind_hint),
                checked = state.remind,
                onCheckedChange = viewModel::onRemindChange
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::save,
                enabled = !state.syncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }

    if (showDatePicker) {
        key(showDatePicker) {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC)
                    .toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let {
                            viewModel.onDateChange(
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            )
                        }
                        showDatePicker = false
                    }) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }
    }

    if (!state.allDay && showStartPicker) {
        TimePickerDialog(
            title = stringResource(R.string.event_start_time),
            initial = state.startTime,
            onConfirm = { viewModel.onStartTimeChange(it); showStartPicker = false },
            onDismiss = { showStartPicker = false }
        )
    }

    if (!state.allDay && showEndPicker) {
        TimePickerDialog(
            title = stringResource(R.string.event_end_time),
            initial = state.endTime,
            onConfirm = { viewModel.onEndTimeChange(it); showEndPicker = false },
            onDismiss = { showEndPicker = false }
        )
    }
}

/** 标签 + 可点击值行（点开日期/时间选择器）；时间校验错误时值标红。 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    isError: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatFullDate(date: LocalDate): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(
        Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
    )

private fun formatTime(context: android.content.Context, date: LocalDate, time: LocalTime): String =
    android.text.format.DateFormat.getTimeFormat(context).format(
        Date.from(date.atTime(time).atZone(ZoneId.systemDefault()).toInstant())
    )
