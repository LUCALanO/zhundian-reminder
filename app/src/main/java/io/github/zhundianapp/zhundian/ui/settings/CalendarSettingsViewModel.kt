package io.github.zhundianapp.zhundian.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhundianapp.zhundian.data.CalendarSettings
import io.github.zhundianapp.zhundian.data.CalendarSourceInfo
import io.github.zhundianapp.zhundian.data.SettingsRepository
import io.github.zhundianapp.zhundian.repository.CalendarEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 系统日历日程提醒的全局设置页视图模型。
 * 每项变更乐观更新本地状态，同时异步落盘（SharedPreferences）。
 *
 * 「同步哪些日历」：sources 列出系统日历来源；默认策略下（enabledCalendarIds 为 null）
 * 只同步可编辑日历，只读系统日历（节日/节气/农历/天气等）默认不勾选。
 * 用户首次勾选/取消任一日历后即固化为显式集合。
 */
class CalendarSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val calendarEventRepository: CalendarEventRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(settingsRepository.load())
    val uiState: StateFlow<CalendarSettings> = _uiState

    /** 系统日历来源列表；无权限或加载失败时为空。 */
    private val _sources = MutableStateFlow<List<CalendarSourceInfo>>(emptyList())
    val sources: StateFlow<List<CalendarSourceInfo>> = _sources

    init {
        loadCalendarSources()
    }

    /** 加载日历来源列表；无权限抛 SecurityException 时保持空列表。授权回调后调用以重载。 */
    fun loadCalendarSources() {
        viewModelScope.launch {
            _sources.value = runCatching { calendarEventRepository.queryCalendarSources() }
                .getOrDefault(emptyList())
        }
    }

    fun onAutoSyncChange(enabled: Boolean) = update { it.copy(autoSyncEnabled = enabled) }

    fun onLeadMinutesChange(value: String) =
        update { it.copy(leadMinutes = value.filter { it.isDigit() }.toIntOrNull() ?: 0) }

    fun onSoundChange(enabled: Boolean) = update { it.copy(soundEnabled = enabled) }

    fun onSoundUriChange(uri: String?) = update { it.copy(soundUri = uri) }

    fun onSoundVolumeChange(volume: Float) = update { it.copy(soundVolume = volume) }

    fun onSoundDurationChange(seconds: Int) = update { it.copy(soundDurationSeconds = seconds) }

    fun onVibrationChange(enabled: Boolean) = update { it.copy(vibrationEnabled = enabled) }

    fun onOverlayChange(enabled: Boolean) = update { it.copy(overlayEnabled = enabled) }

    /**
     * 勾选/取消某日历来源。基准集合 = 显式集合，未配置时取默认策略（仅可编辑日历），
     * 首次变更后即固化为显式 allowlist 落盘。
     */
    fun onCalendarToggle(id: Long, selected: Boolean) {
        val sources = _sources.value
        if (sources.isEmpty()) return
        val base = _uiState.value.enabledCalendarIds
            ?: sources.filter { !it.isReadOnly }.map { it.id }.toSet()
        val next = if (selected) base + id else base - id
        update { it.copy(enabledCalendarIds = next) }
    }

    private fun update(transform: (CalendarSettings) -> CalendarSettings) {
        // 乐观更新 UI，避免等待 IO 落盘造成交互延迟
        _uiState.value = transform(_uiState.value)
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
