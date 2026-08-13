package io.github.zhundianapp.zhundian.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.permission.PermissionManager
import io.github.zhundianapp.zhundian.ui.components.AppTopBar
import io.github.zhundianapp.zhundian.util.AppLanguage
import io.github.zhundianapp.zhundian.util.IntervalFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderListScreen(
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onOpenCalendar: (Long?) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutostart: () -> Unit,
    highlightedReminderId: Long? = null
) {
    val viewModel: ReminderListViewModel = viewModel(factory = reminderListViewModelFactory())
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionManager =
        (context.applicationContext as IntervalReminderApp).container.permissionManager

    // 从系统设置页返回后重新评估权限/白名单状态，让提示卡即时消失
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val showPermissionHint = remember(refreshTick) {
        !permissionManager.hasNotificationPermission() ||
            !permissionManager.canScheduleExact() ||
            !permissionManager.isIgnoringBatteryOptimizations()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.reminder_list_title),
                actions = { LanguageMenuButton() }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_reminder)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showPermissionHint) {
                item {
                    PermissionHintCard(
                        permissionManager = permissionManager,
                        onOpenBatterySettings = onOpenBatterySettings
                    )
                }
            }
            item { AutostartGuidanceCard(onOpenAutostart = onOpenAutostart) }
            if (reminders.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.empty_list),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(reminders, key = { it.id }) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    highlighted = reminder.id == highlightedReminderId,
                    onToggle = { viewModel.toggleEnabled(reminder) },
                    onOpenCalendar = { onOpenCalendar(reminder.id) },
                    onEdit = { onEditClick(reminder.id) },
                    onDelete = { viewModel.delete(reminder) }
                )
            }
        }
    }
}

@Composable
private fun LanguageMenuButton() {
    val context = LocalContext.current
    val languageLabel = stringResource(R.string.language)
    // recreate() 后整体重组，remember 会重新求值，无需额外状态刷新
    val currentLang = remember { AppLanguage.currentTag(context) }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_language),
                contentDescription = languageLabel
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AppLanguage.options().forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    trailingIcon = {
                        if (option.tag == currentLang) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        if (option.tag != currentLang) {
                            AppLanguage.apply(context, option.tag)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionHintCard(
    permissionManager: PermissionManager,
    onOpenBatterySettings: () -> Unit
) {
    val hintText = when {
        !permissionManager.hasNotificationPermission() ->
            stringResource(R.string.permission_hint_notification)
        !permissionManager.canScheduleExact() ->
            stringResource(R.string.permission_hint_exact_alarm)
        !permissionManager.isIgnoringBatteryOptimizations() ->
            stringResource(R.string.permission_hint_battery)
        else -> return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hintText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                when {
                    !permissionManager.hasNotificationPermission() ->
                        permissionManager.openNotificationSettings()
                    !permissionManager.canScheduleExact() ->
                        permissionManager.openExactAlarmSettings()
                    else -> onOpenBatterySettings()
                }
            }) {
                Text(stringResource(R.string.grant_permission))
            }
        }
    }
}

@Composable
private fun AutostartGuidanceCard(onOpenAutostart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.autostart_hint_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.autostart_hint_text),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenAutostart) {
                    Text(stringResource(R.string.open_autostart_settings))
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(
    reminder: Reminder,
    highlighted: Boolean,
    onToggle: () -> Unit,
    onOpenCalendar: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val surfaceColor = if (highlighted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = surfaceColor)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reminder.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = reminder.isEnabled,
                    onCheckedChange = { onToggle() }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.next_trigger_at,
                    IntervalFormatter.formatTriggerTime(context, reminder.nextTriggerAt)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (reminder.isEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Gray
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = IntervalFormatter.format(context, reminder.intervalValue, reminder.intervalUnit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenCalendar) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = stringResource(R.string.calendar_open)
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun reminderListViewModelFactory() = viewModelFactory {
    val context = androidx.compose.ui.platform.LocalContext.current
    initializer {
        ReminderListViewModel(
            (context.applicationContext as IntervalReminderApp).container.reminderRepository
        )
    }
}
