package io.github.zhundianapp.zhundian.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.CalendarEvent
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date

/** 系统日历日程视图：月历 + 当天日程明细，日程色点按来源系统日历 id 派生色相。 */
@Composable
fun EventCalendarView(
    month: YearMonth,
    today: LocalDate,
    events: List<CalendarEvent>,
    deletedEvents: List<CalendarEvent>,
    selectedDate: LocalDate?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit,
    onRestoreEvent: (CalendarEvent) -> Unit,
    onDeleteEventPermanently: (CalendarEvent) -> Unit
) {
    val grouped = remember(events) { MonthGrid.groupEventsByDate(events) }
    var showDeletedDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        MonthHeader(
            month = month,
            onPrevious = onPrevious,
            onNext = onNext,
            onToday = onToday
        )
        WeekHeader()
        EventMonthGrid(
            month = month,
            today = today,
            grouped = grouped,
            selectedDate = selectedDate,
            onSelectDate = onSelectDate
        )
        Spacer(Modifier.height(12.dp))
        EventDayDetailCard(
            selectedDate = selectedDate,
            eventsForDay = selectedDate?.let { grouped[it].orEmpty() } ?: emptyList(),
            onDeleteEvent = onDeleteEvent
        )
        // 误删恢复入口：有墓碑日程时才显示
        if (deletedEvents.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { showDeletedDialog = true }) {
                    Text(stringResource(R.string.calendar_event_deleted_entry, deletedEvents.size))
                }
            }
        }
    }

    if (showDeletedDialog) {
        DeletedEventsDialog(
            deletedEvents = deletedEvents,
            onRestore = onRestoreEvent,
            onPermanentDelete = onDeleteEventPermanently,
            onDismiss = { showDeletedDialog = false }
        )
    }
}

/** 月历网格（系统日历日程版）：色点表示当天有日程，色相由来源系统日历派生。 */
@Composable
private fun EventMonthGrid(
    month: YearMonth,
    today: LocalDate,
    grouped: Map<LocalDate, List<CalendarEvent>>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit
) {
    val cells = remember(month, today) { MonthGrid.cells(month, today) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { rowCells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowCells.forEach { cell ->
                    val dayEvents = grouped[cell.date].orEmpty()
                    EventDayCellView(
                        cell = cell,
                        modifier = Modifier.weight(1f),
                        dots = dayEvents.map { CalendarColors.reminderDot(it.sourceCalendarId) }
                            .distinct(),
                        isSelected = cell.date == selectedDate,
                        onClick = { onSelectDate(cell.date) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EventDayCellView(
    cell: DayCell,
    modifier: Modifier,
    dots: List<Color>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val cellModifier = modifier
        .aspectRatio(1f)
        .clip(shape)
        .background(Color.Transparent)
        .let {
            if (isSelected) it.border(2.dp, MaterialTheme.colorScheme.primary, shape) else it
        }
        .clickable(onClick = onClick)

    Box(modifier = cellModifier) {
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

/** 当天日程明细卡：标题 / 时间范围或「全天」/ 地点 / 删除（二次确认）。 */
@Composable
private fun EventDayDetailCard(
    selectedDate: LocalDate?,
    eventsForDay: List<CalendarEvent>,
    onDeleteEvent: (CalendarEvent) -> Unit
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    val dateTitle = selectedDate?.let {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(Date.from(
            it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        ))
    } ?: ""

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
            Text(
                text = dateTitle,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            if (eventsForDay.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_no_record),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                eventsForDay.forEach { event ->
                    EventRow(
                        event = event,
                        onDelete = { pendingDelete = event }
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.calendar_event_delete_title)) },
            text = { Text(stringResource(R.string.calendar_event_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let(onDeleteEvent)
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
}

/** 单条日程行：时间（固定宽）/ 标题（单行省略，占剩余）/ 删除按钮。 */
@Composable
private fun EventRow(
    event: CalendarEvent,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val timeText = if (event.allDay) {
        context.getString(R.string.calendar_event_all_day)
    } else {
        val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
        timeFormat.format(Date(event.startAt)) + " - " + timeFormat.format(Date(event.endAt))
    }
    val subtitle = event.location?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeText,
            modifier = Modifier.width(104.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.calendar_event_delete),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 已删除日程对话框：列出全部墓碑日程，每条可「恢复」（清墓碑）或「永久删除」（物理删行）。
 * 恢复/永久删除后 Room Flow 表级失效重发，对应行自动消失，无需手动关闭对话框。
 */
@Composable
private fun DeletedEventsDialog(
    deletedEvents: List<CalendarEvent>,
    onRestore: (CalendarEvent) -> Unit,
    onPermanentDelete: (CalendarEvent) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_event_deleted_title)) },
        text = {
            if (deletedEvents.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_event_deleted_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    deletedEvents.forEach { event ->
                        DeletedEventRow(
                            event = event,
                            onRestore = { onRestore(event) },
                            onPermanentDelete = { onPermanentDelete(event) }
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.calendar_event_deleted_close))
            }
        }
    )
}

/** 单条已删除日程：标题（单行省略）+ 日期时间小字 + 恢复 / 永久删除。 */
@Composable
private fun DeletedEventRow(
    event: CalendarEvent,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    val context = LocalContext.current
    val dateText = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        .format(Date(event.startAt))
    val timeText = android.text.format.DateFormat.getTimeFormat(context).format(Date(event.startAt))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$dateText $timeText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onRestore) {
            Text(stringResource(R.string.calendar_event_restore))
        }
        TextButton(onClick = onPermanentDelete) {
            Text(
                text = stringResource(R.string.calendar_event_delete_permanent),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 月切换头（触发记录视图与日程视图共用）。 */
@Composable
fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.calendar_prev_month)
            )
        }
        Text(
            text = "${month.year}年${month.monthValue}月",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.calendar_next_month)
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onToday) {
            Text(stringResource(R.string.calendar_go_today))
        }
    }
}

/** 星期表头（周一起点，与 MonthGrid 一致）。 */
@Composable
fun WeekHeader() {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}
