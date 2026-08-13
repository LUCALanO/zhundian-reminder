package io.github.zhundianapp.zhundian.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhundianapp.zhundian.repository.CalendarEventRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 新建日程表单状态。 */
data class EventEditUiState(
    val title: String = "",
    val titleError: Boolean = false,
    /** 日程日期（默认今天或从日历传入的选中日期）。 */
    val date: LocalDate = LocalDate.now(),
    /** 默认开始时间：当前时间截断到分钟。 */
    val startTime: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    /** 默认结束时间：开始后 1 小时（截断到分钟）。 */
    val endTime: LocalTime = LocalTime.now().plusHours(1).withSecond(0).withNano(0),
    val allDay: Boolean = false,
    val location: String = "",
    val description: String = "",
    /** App 内到点提醒（走全局日程提醒设置）。 */
    val remind: Boolean = true,
    val timeError: Boolean = false,
    /** 保存中（防重复提交）。 */
    val syncing: Boolean = false,
    /** 保存完成，页面可返回。 */
    val saved: Boolean = false
)

/**
 * 新建日程视图模型。日期/时间本地状态乐观更新，保存时统一换算为 epoch millis
 * 交给 [CalendarEventRepository.createLocalEvent] 入库（仅存本 App）。
 */
class EventEditViewModel(
    private val repository: CalendarEventRepository,
    initialDate: LocalDate?
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EventEditUiState(date = initialDate ?: LocalDate.now())
    )
    val uiState: StateFlow<EventEditUiState> = _uiState

    fun onTitleChange(value: String) = update { it.copy(title = value, titleError = false) }

    fun onDateChange(date: LocalDate) = update { it.copy(date = date) }

    fun onStartTimeChange(time: LocalTime) = update { it.copy(startTime = time, timeError = false) }

    fun onEndTimeChange(time: LocalTime) = update { it.copy(endTime = time, timeError = false) }

    fun onAllDayChange(allDay: Boolean) = update { it.copy(allDay = allDay, timeError = false) }

    fun onLocationChange(value: String) = update { it.copy(location = value) }

    fun onDescriptionChange(value: String) = update { it.copy(description = value) }

    fun onRemindChange(remind: Boolean) = update { it.copy(remind = remind) }

    /** 保存：校验 → 换算毫秒 → 入库。 */
    fun save() {
        if (_uiState.value.syncing) return
        val s = _uiState.value
        val zone = ZoneId.systemDefault()
        val startAt = if (s.allDay) {
            s.date.atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            s.date.atTime(s.startTime).atZone(zone).toInstant().toEpochMilli()
        }
        val endAt = if (s.allDay) {
            s.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            s.date.atTime(s.endTime).atZone(zone).toInstant().toEpochMilli()
        }
        val validation = validateEventForm(s.title, startAt, endAt, s.allDay)
        _uiState.update { it.copy(titleError = validation.titleError, timeError = validation.timeError) }
        if (!validation.isValid) return

        _uiState.update { it.copy(syncing = true) }
        viewModelScope.launch {
            repository.createLocalEvent(
                title = s.title.trim(),
                description = s.description.trim().ifEmpty { null },
                location = s.location.trim().ifEmpty { null },
                startAt = startAt,
                endAt = endAt,
                allDay = s.allDay,
                remind = s.remind
            )
            _uiState.update { it.copy(syncing = false, saved = true) }
        }
    }

    private fun update(transform: (EventEditUiState) -> EventEditUiState) {
        _uiState.update(transform)
    }
}
