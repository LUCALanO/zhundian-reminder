package io.github.zhundianapp.zhundian.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.data.TriggerRecord
import io.github.zhundianapp.zhundian.permission.PermissionManager
import io.github.zhundianapp.zhundian.repository.CalendarEventRepository
import io.github.zhundianapp.zhundian.repository.ReminderRepository
import io.github.zhundianapp.zhundian.repository.SyncResult
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 日历视图模式：触发记录 / 系统日历日程。 */
enum class CalendarViewMode { TRIGGERS, EVENTS }

/** 系统日历同步的 UI 状态。 */
sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Syncing : SyncUiState
    data class Done(val inserted: Int, val refreshed: Int, val removed: Int) : SyncUiState
    data class Failed(val message: String?) : SyncUiState
    data object NotPermitted : SyncUiState
}

/**
 * 日历视图模型。reminderId 为 null 时是全局汇总视图（全部提醒的触发历史 + 系统日程），
 * 否则是单提醒视图（仅触发历史）。月份切换时经 flatMapLatest 实时拉取当月数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val reminderRepository: ReminderRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val permissionManager: PermissionManager,
    private val reminderId: Long?
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate

    private val _mode = MutableStateFlow(CalendarViewMode.TRIGGERS)
    val mode: StateFlow<CalendarViewMode> = _mode

    /** 当前展示月份的触发记录（单提醒按 id 过滤；全局为全部）。 */
    val records: StateFlow<List<TriggerRecord>> = _month
        .flatMapLatest { m ->
            val range = monthRangeMillis(m)
            if (reminderId == null) {
                reminderRepository.observeAllTriggerRecords(range.first, range.last)
            } else {
                reminderRepository.observeTriggerRecords(reminderId, range.first, range.last)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前展示月份的系统日历日程（含未提醒/已提醒；不显示被用户删除的）。 */
    val events: StateFlow<List<CalendarEvent>> = _month
        .flatMapLatest { m ->
            val range = monthRangeMillis(m)
            calendarEventRepository.observeEvents(range.first, range.last)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 被用户删除的日程（墓碑），供「已删除日程」持久入口展示与恢复。 */
    val deletedEvents: StateFlow<List<CalendarEvent>> = calendarEventRepository
        .observeDeletedEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部提醒（全局模式做名称/色点映射，单提醒模式取标题）。 */
    val reminders: StateFlow<List<Reminder>> = reminderRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState

    /** 是否已授予系统日历读取权限。 */
    val hasCalendarPermission: Boolean
        get() = permissionManager.hasCalendarReadPermission()

    fun previousMonth() {
        _month.update { it.minusMonths(1) }
        _selectedDate.value = null
    }

    fun nextMonth() {
        _month.update { it.plusMonths(1) }
        _selectedDate.value = null
    }

    fun goToday() {
        _month.value = YearMonth.now()
        _selectedDate.value = LocalDate.now()
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /** 切换视图模式；单提醒日历固定触发记录，不接受切换。 */
    fun setMode(mode: CalendarViewMode) {
        if (reminderId != null) return
        _mode.value = mode
        _selectedDate.value = null
    }

    /** 手动刷新同步系统日历：有权限则同步，无权限置 NotPermitted 供 UI 引导授权。 */
    fun refresh() {
        if (!hasCalendarPermission) {
            _syncState.value = SyncUiState.NotPermitted
            return
        }
        viewModelScope.launch {
            _syncState.value = SyncUiState.Syncing
            _syncState.value = when (val result = calendarEventRepository.sync()) {
                is SyncResult.Success ->
                    SyncUiState.Done(result.inserted, result.refreshed, result.removed)
                is SyncResult.Error -> SyncUiState.Failed(result.message)
                SyncResult.NotPermitted -> SyncUiState.NotPermitted
            }
        }
    }

    /** 权限请求结果回调：授权成功后自动同步一次。 */
    fun onPermissionResult(granted: Boolean) {
        if (granted) refresh() else _syncState.value = SyncUiState.NotPermitted
    }

    /** 从本 App 移除一条日程（不影响系统日历）。 */
    fun deleteEvent(event: CalendarEvent) {
        viewModelScope.launch { calendarEventRepository.deleteEvent(event) }
    }

    /** 移除一条日程并等待墓碑落库（供 Snackbar 撤销路径：先删成功再弹提示，避免撤销时删除未提交）。 */
    suspend fun deleteEventNow(event: CalendarEvent) = calendarEventRepository.deleteEvent(event)

    /** 恢复一条被删除的日程（清墓碑，未来未提醒的重新调度闹钟）。 */
    fun restoreEvent(event: CalendarEvent) {
        viewModelScope.launch { calendarEventRepository.restoreEvent(event) }
    }

    /** 永久删除一条日程（含墓碑行本身）。 */
    fun deleteEventPermanently(event: CalendarEvent) {
        viewModelScope.launch { calendarEventRepository.deleteEventPermanently(event) }
    }

    /** 精确删除某条触发历史记录。 */
    fun deleteRecord(record: TriggerRecord) {
        viewModelScope.launch { reminderRepository.deleteTriggerRecord(record) }
    }

    /** 批量删除多条触发历史记录（日历勾选批量删除）。 */
    fun deleteRecords(records: List<TriggerRecord>) {
        viewModelScope.launch { reminderRepository.deleteTriggerRecords(records) }
    }

    companion object {
        /** 月份对应的毫秒范围 [月初, 下月月初)。 */
        fun monthRangeMillis(month: YearMonth, zoneId: ZoneId = ZoneId.systemDefault()): LongRange {
            val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return start until end
        }
    }
}
