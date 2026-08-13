package io.github.zhundianapp.zhundian.ui.calendar

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.ui.components.AppTopBar
import io.github.zhundianapp.zhundian.data.TriggerRecord
import io.github.zhundianapp.zhundian.data.TriggerStatus
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import kotlinx.coroutines.launch

/** 日历配色：已完成=绿、已触发=琥珀、已改期=橙；全局模式色点由提醒 id 派生色相。 */
internal object CalendarColors {
    val completed = Color(0xFF4CAF50)
    val triggered = Color(0xFFF5B301)
    val snoozed = Color(0xFFF97316)
    val todayContainer = Color(0x1A000000)

    fun statusColor(status: DayStatus): Color? = when (status) {
        DayStatus.COMPLETED -> completed
        DayStatus.TRIGGERED -> triggered
        DayStatus.SNOOZED -> snoozed
        DayStatus.NONE -> null
    }

    /** 单条记录状态 → 文字颜色（当天明细行的状态列）。 */
    fun statusTextColor(status: TriggerStatus): Color = when (status) {
        TriggerStatus.COMPLETED -> completed
        TriggerStatus.TRIGGERED -> triggered
        TriggerStatus.SNOOZED -> snoozed
    }

    /**
     * 按 id 派生色相（同一日历/提醒稳定同色）。
     * App 自建日程固定用品牌绿（[CalendarEvent.SOURCE_CALENDAR_ID_LOCAL] 哨兵值），
     * 与导入日历的派生色区分；其余 id 经 floorMod 归一化到 [0,360)——Color.hsv 要求色相
     * 非负，负数 id 直接传会抛 IllegalArgumentException（闪退），这里杜绝该路径。
     */
    fun reminderDot(id: Long): Color {
        if (id == CalendarEvent.SOURCE_CALENDAR_ID_LOCAL) return Color(0xFF4CAF50)
        return Color.hsv(Math.floorMod(id * 47, 360L).toFloat(), 0.62f, 0.85f)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    reminderId: Long?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddEvent: (LocalDate?) -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 用 Activity 级 ViewModelStoreOwner：切换 Tab / 进出页面时保留月份与已加载数据，
    // 避免每次进入日历都重建 ViewModel 重新查询数据库（切回更流畅）。
    val viewModel: CalendarViewModel = viewModel(
        viewModelStoreOwner = context.findActivity(),
        key = "calendar_${reminderId ?: "global"}",
        factory = viewModelFactory {
            initializer {
                val container = (context.applicationContext as IntervalReminderApp).container
                CalendarViewModel(
                    reminderRepository = container.reminderRepository,
                    calendarEventRepository = container.calendarEventRepository,
                    permissionManager = container.permissionManager,
                    reminderId = reminderId
                )
            }
        }
    )
    val month by viewModel.month.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val deletedEvents by viewModel.deletedEvents.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val isGlobal = reminderId == null

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    val title = if (isGlobal) {
        stringResource(R.string.calendar_title)
    } else {
        reminders.find { it.id == reminderId }?.name
            ?: stringResource(R.string.calendar_title)
    }
    val grouped = remember(records) { MonthGrid.groupByDate(records) }

    // 删除日程：先等墓碑落库，再弹「撤销」提示。闭包捕获每条 event，
    // 连删时 Snackbar 自动排队，各协程互不污染，无需共享待撤销状态。
    val onDeleteEvent: (CalendarEvent) -> Unit = { event ->
        scope.launch {
            viewModel.deleteEventNow(event)
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.calendar_event_deleted_snackbar, event.title),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreEvent(event)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // 新建日程只服务于全局日历的日程视图
            if (isGlobal && mode == CalendarViewMode.EVENTS) {
                ExtendedFloatingActionButton(
                    onClick = { onAddEvent(selectedDate) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.event_add)) }
                )
            }
        },
        topBar = {
            AppTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 刷新/设置只服务于日程功能，仅在日程模式显示，避免在触发记录界面造成歧义
                    if (isGlobal && mode == CalendarViewMode.EVENTS) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.calendar_sync_refresh)
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.calendar_settings)
                            )
                        }
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
                .padding(horizontal = 16.dp)
        ) {
            // 全局日历才允许在「触发记录 / 日程」间切换；单提醒日历固定触发记录
            if (isGlobal) {
                CalendarModeSelector(
                    mode = mode,
                    onSelect = viewModel::setMode
                )
                Spacer(Modifier.height(12.dp))
            }
            when (mode) {
                CalendarViewMode.TRIGGERS -> {
                    MonthHeader(
                        month = month,
                        onPrevious = viewModel::previousMonth,
                        onNext = viewModel::nextMonth,
                        onToday = viewModel::goToday
                    )
                    WeekHeader()
                    MonthGrid(
                        month = month,
                        today = today,
                        grouped = grouped,
                        reminderId = reminderId,
                        selectedDate = selectedDate,
                        onSelectDate = viewModel::selectDate
                    )
                    Spacer(Modifier.height(12.dp))
                    StatusLegend(isGlobal = isGlobal)
                    Spacer(Modifier.height(12.dp))
                    DayDetailCard(
                        selectedDate = selectedDate,
                        recordsForDay = selectedDate?.let { grouped[it].orEmpty() } ?: emptyList(),
                        remindersByName = reminders.associate { it.id to it.name },
                        onDeleteRecord = viewModel::deleteRecord,
                        onDeleteRecords = viewModel::deleteRecords
                    )
                }

                CalendarViewMode.EVENTS -> {
                    EventSyncBanner(
                        hasPermission = viewModel.hasCalendarPermission,
                        syncState = syncState,
                        onGrant = {
                            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                    )
                    EventCalendarView(
                        month = month,
                        today = today,
                        events = events,
                        deletedEvents = deletedEvents,
                        selectedDate = selectedDate,
                        onPrevious = viewModel::previousMonth,
                        onNext = viewModel::nextMonth,
                        onToday = viewModel::goToday,
                        onSelectDate = viewModel::selectDate,
                        onDeleteEvent = onDeleteEvent,
                        onRestoreEvent = viewModel::restoreEvent,
                        onDeleteEventPermanently = viewModel::deleteEventPermanently
                    )
                }
            }
        }
    }
}

/** 日历视图切换：触发记录 / 日程（仅全局日历显示）。 */
@Composable
private fun CalendarModeSelector(
    mode: CalendarViewMode,
    onSelect: (CalendarViewMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == CalendarViewMode.TRIGGERS,
            onClick = { onSelect(CalendarViewMode.TRIGGERS) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) {
            Text(stringResource(R.string.calendar_mode_triggers))
        }
        SegmentedButton(
            selected = mode == CalendarViewMode.EVENTS,
            onClick = { onSelect(CalendarViewMode.EVENTS) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) {
            Text(stringResource(R.string.calendar_mode_events))
        }
    }
}

/**
 * 日程视图顶部的权限引导与同步状态条：
 * - 未授权：横幅提示「去授权」拉起 READ_CALENDAR 权限请求；
 * - 同步中 / 完成 / 失败 / 未授权：一行状态文本。
 */
@Composable
private fun EventSyncBanner(
    hasPermission: Boolean,
    syncState: SyncUiState,
    onGrant: () -> Unit
) {
    if (!hasPermission) {
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
                    text = stringResource(R.string.calendar_event_permission_hint),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onGrant) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
        return
    }
    val statusText = when (syncState) {
        SyncUiState.Idle -> null
        SyncUiState.Syncing -> stringResource(R.string.calendar_sync_in_progress)
        is SyncUiState.Done -> stringResource(
            R.string.calendar_sync_done, syncState.inserted, syncState.refreshed, syncState.removed
        )
        is SyncUiState.Failed -> stringResource(R.string.calendar_sync_failed)
        SyncUiState.NotPermitted -> stringResource(R.string.calendar_sync_not_permitted)
    }
    if (statusText != null) {
        Text(
            text = statusText,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    grouped: Map<LocalDate, List<TriggerRecord>>,
    reminderId: Long?,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit
) {
    val cells = remember(month, today) { MonthGrid.cells(month, today) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { rowCells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowCells.forEach { cell ->
                    DayCellView(
                        cell = cell,
                        modifier = Modifier.weight(1f),
                        fillColor = if (reminderId == null) {
                            null // 全局模式用色点，不整格填色
                        } else {
                            CalendarColors.statusColor(
                                MonthGrid.statusOf(grouped[cell.date].orEmpty())
                            )
                        },
                        dots = if (reminderId == null) {
                            grouped[cell.date].orEmpty()
                                .map { it.reminderId }
                                .distinct()
                                .map { CalendarColors.reminderDot(it) }
                        } else {
                            emptyList()
                        },
                        isSelected = cell.date == selectedDate,
                        onClick = { onSelectDate(cell.date) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCellView(
    cell: DayCell,
    modifier: Modifier,
    fillColor: Color?,
    dots: List<Color>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val cellModifier = modifier
        .aspectRatio(1f)
        .clip(shape)
        .background(fillColor ?: Color.Transparent)
        .let {
            if (isSelected) it.border(2.dp, MaterialTheme.colorScheme.primary, shape) else it
        }
        .clickable(onClick = onClick)

    Box(modifier = cellModifier) {
        // 今天：数字套圆，突出当天
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .let {
                    if (cell.isToday) {
                        it
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(CalendarColors.todayContainer)
                    } else it
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (cell.inMonth) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        }
        // 全局模式色点：有记录的提醒各占一个
        if (dots.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                dots.take(4).forEach { color ->
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLegend(isGlobal: Boolean) {
    if (isGlobal) {
        Text(
            text = stringResource(R.string.calendar_legend_global_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LegendItem(CalendarColors.completed, stringResource(R.string.calendar_legend_completed))
        LegendItem(CalendarColors.triggered, stringResource(R.string.calendar_legend_triggered))
        LegendItem(CalendarColors.snoozed, stringResource(R.string.calendar_legend_snoozed))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayDetailCard(
    selectedDate: LocalDate?,
    recordsForDay: List<TriggerRecord>,
    remindersByName: Map<Long, String>,
    onDeleteRecord: (TriggerRecord) -> Unit,
    onDeleteRecords: (List<TriggerRecord>) -> Unit
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<TriggerRecord?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    // 批量管理模式：切换日期时自动退出并清空选中
    var manageMode by remember(selectedDate) { mutableStateOf(false) }
    var selectedIds by remember(selectedDate) { mutableStateOf(setOf<Long>()) }
    val dateTitle = selectedDate?.let {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(Date.from(
            it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        ))
    } ?: ""

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (recordsForDay.isNotEmpty()) {
                    if (manageMode) {
                        TextButton(onClick = { selectedIds = recordsForDay.map { it.id }.toSet() }) {
                            Text(stringResource(R.string.calendar_batch_select_all))
                        }
                    } else {
                        TextButton(onClick = { manageMode = true }) {
                            Text(stringResource(R.string.calendar_batch_delete))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (recordsForDay.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_no_record),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recordsForDay.forEach { record ->
                    RecordRow(
                        record = record,
                        reminderName = remindersByName[record.reminderId],
                        showCheck = manageMode,
                        checked = record.id in selectedIds,
                        onCheckToggle = { toggleSelected(record.id) },
                        onDelete = if (manageMode) null else ({ pendingDelete = record })
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (manageMode) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = {
                            manageMode = false
                            selectedIds = emptySet()
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { pendingBatchDelete = true },
                            enabled = selectedIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                stringResource(R.string.calendar_batch_selected) +
                                    "(${selectedIds.size})"
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.calendar_delete_record_title)) },
            text = { Text(stringResource(R.string.calendar_delete_record_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let(onDeleteRecord)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (pendingBatchDelete) {
        val selectedCount = recordsForDay.count { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text(stringResource(R.string.calendar_batch_delete_title, selectedCount)) },
            text = { Text(stringResource(R.string.calendar_delete_record_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecords(recordsForDay.filter { it.id in selectedIds })
                    selectedIds = emptySet()
                    manageMode = false
                    pendingBatchDelete = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 单条记录行：可选勾选框 / 时间（固定宽）/ 提醒名称（单行省略，占剩余）/ 状态（右对齐着色）/ 删除按钮。
 * 批量管理模式（showCheck）下整行可点击切换选中，删除按钮隐藏由勾选代替。
 * 分列对齐替代原先的「时间 · 名称 · 状态」单行拼接，记录再多也一目了然。
 */
@Composable
private fun RecordRow(
    record: TriggerRecord,
    reminderName: String?,
    onDelete: (() -> Unit)? = null,
    showCheck: Boolean = false,
    checked: Boolean = false,
    onCheckToggle: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val time = android.text.format.DateFormat
        .getTimeFormat(context)
        .format(Date(record.triggerAt))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (showCheck) it.clickable(onClick = { onCheckToggle?.invoke() }) else it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCheck) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onCheckToggle?.invoke() },
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            text = time,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (reminderName != null) {
            Text(
                text = reminderName,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = statusText(context, record.status),
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = CalendarColors.statusTextColor(record.status)
        )
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.calendar_delete_record),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun statusText(context: Context, status: TriggerStatus): String =
    when (status) {
        TriggerStatus.TRIGGERED -> context.getString(R.string.calendar_legend_triggered)
        TriggerStatus.COMPLETED -> context.getString(R.string.calendar_legend_completed)
        TriggerStatus.SNOOZED -> context.getString(R.string.calendar_legend_snoozed)
    }

/** 沿 Context 包装链向上找到宿主 Activity（用于 Activity 级 ViewModel）。 */
private tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> throw IllegalArgumentException("CalendarScreen 需要在 Activity 内使用")
}
