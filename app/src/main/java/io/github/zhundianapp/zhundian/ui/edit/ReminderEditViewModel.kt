package io.github.zhundianapp.zhundian.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.notification.SoundPlayer
import io.github.zhundianapp.zhundian.repository.ReminderRepository
import io.github.zhundianapp.zhundian.util.IntervalCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/** 当前时间对应的分钟数（0~1439），作为新提醒「天」间隔触发时刻的默认值。 */
private fun currentTimeMinutes(): Int {
    val cal = Calendar.getInstance()
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

/** 由锚定参考点（epoch millis）取其中的时分对应的分钟数（0~1439）。 */
private fun minutesOfDay(epochMillis: Long): Int {
    val cal = Calendar.getInstance()
    cal.timeInMillis = epochMillis
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

/** 编辑页 UI 状态。 */
data class EditUiState(
    val name: String = "",
    val intervalValue: String = "",
    val intervalUnit: IntervalUnit = IntervalUnit.HOURS,
    /** 固定触发时刻（分钟 0~1439）：仅「天」间隔生效，到点固定在该时刻。 */
    val triggerTimeMinutes: Int = currentTimeMinutes(),
    /** 声音开关：到点直接播放铃声（绕过系统通知声音设置）。 */
    val soundEnabled: Boolean = true,
    /** 自定义铃声 URI 字符串；null 用系统默认闹钟铃声。 */
    val soundUri: String? = null,
    /** 响铃音量（0~1，相对闹钟流音量）。 */
    val soundVolume: Float = SoundPlayer.DEFAULT_VOLUME,
    /** 响铃时长（秒）。 */
    val soundDurationSeconds: Int = SoundPlayer.DEFAULT_DURATION_SECONDS,
    val vibrationEnabled: Boolean = true,
    /** 顶部弹窗：到点在屏幕上方弹出悬浮窗。 */
    val overlayEnabled: Boolean = false,
    /** 自定义通知提示语：留空则通知使用默认文案。 */
    val message: String = "",
    val nameError: Boolean = false,
    val intervalError: Boolean = false,
    val loading: Boolean = false,
    val saved: Boolean = false
)

class ReminderEditViewModel(
    private val repository: ReminderRepository,
    private val reminderId: Long?
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditUiState(loading = reminderId != null))
    val uiState: StateFlow<EditUiState> = _uiState

    init {
        if (reminderId != null) {
            viewModelScope.launch {
                val r = repository.getById(reminderId)
                _uiState.value = if (r != null) {
                    EditUiState(
                        name = r.name,
                        intervalValue = r.intervalValue.toString(),
                        intervalUnit = r.intervalUnit,
                        triggerTimeMinutes = r.scheduleAnchorAt?.let(::minutesOfDay)
                            ?: currentTimeMinutes(),
                        soundEnabled = r.soundEnabled,
                        soundUri = r.soundUri,
                        soundVolume = r.soundVolume,
                        soundDurationSeconds = r.soundDurationSeconds,
                        vibrationEnabled = r.vibrationEnabled,
                        overlayEnabled = r.overlayEnabled,
                        message = r.message ?: "",
                        loading = false
                    )
                } else {
                    EditUiState(loading = false)
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value, nameError = false)
    }

    fun onIntervalValueChange(value: String) {
        _uiState.value = _uiState.value.copy(
            intervalValue = value.filter { it.isDigit() },
            intervalError = false
        )
    }

    fun onUnitChange(unit: IntervalUnit) {
        _uiState.value = _uiState.value.copy(intervalUnit = unit)
    }

    fun onTriggerTimeChange(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(triggerTimeMinutes = hour * 60 + minute)
    }

    fun onSoundChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(soundEnabled = enabled)
    }

    fun onSoundUriChange(uri: String?) {
        _uiState.value = _uiState.value.copy(soundUri = uri)
    }

    fun onSoundVolumeChange(volume: Float) {
        _uiState.value = _uiState.value.copy(soundVolume = volume)
    }

    fun onSoundDurationChange(seconds: Int) {
        _uiState.value = _uiState.value.copy(soundDurationSeconds = seconds)
    }

    fun onVibrationChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(vibrationEnabled = enabled)
    }

    fun onOverlayChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(overlayEnabled = enabled)
    }

    fun onMessageChange(value: String) {
        _uiState.value = _uiState.value.copy(message = value)
    }

    fun save() {
        val state = _uiState.value
        val validation = validateReminderForm(state.name, state.intervalValue)
        if (!validation.isValid) {
            _uiState.value = state.copy(
                nameError = validation.nameError,
                intervalError = validation.intervalError
            )
            return
        }
        val interval = state.intervalValue.toInt()
        val message = state.message.ifBlank { null }?.trim()
        // 触发时刻仅对「天」间隔生效
        val triggerTimeMinutes = if (state.intervalUnit == IntervalUnit.DAYS) {
            state.triggerTimeMinutes
        } else {
            null
        }
        viewModelScope.launch {
            if (reminderId == null) {
                repository.create(
                    name = state.name,
                    intervalValue = interval,
                    intervalUnit = state.intervalUnit,
                    triggerTimeMinutes = triggerTimeMinutes,
                    soundEnabled = state.soundEnabled,
                    soundUri = state.soundUri,
                    soundVolume = state.soundVolume,
                    soundDurationSeconds = state.soundDurationSeconds,
                    vibrationEnabled = state.vibrationEnabled,
                    overlayEnabled = state.overlayEnabled,
                    message = message
                )
            } else {
                repository.getById(reminderId)?.let { existing ->
                    repository.update(
                        existing.copy(
                            name = state.name.trim(),
                            intervalValue = interval,
                            intervalUnit = state.intervalUnit,
                            soundEnabled = state.soundEnabled,
                            soundUri = state.soundUri,
                            soundVolume = state.soundVolume,
                            soundDurationSeconds = state.soundDurationSeconds,
                            vibrationEnabled = state.vibrationEnabled,
                            overlayEnabled = state.overlayEnabled,
                            message = message,
                            scheduleAnchorAt = if (state.intervalUnit == IntervalUnit.DAYS) {
                                // 编辑保存即重定相：以保存当天的新触发时刻为锚
                                IntervalCalculator.anchorAtToday(state.triggerTimeMinutes)
                            } else {
                                null
                            }
                        )
                    )
                }
            }
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }
}
