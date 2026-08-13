package io.github.zhundianapp.zhundian.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 提醒实体：一个固定间隔、永久循环的定时提醒。
 *
 * nextTriggerAt 为最近一次触发时间（epoch millis）。每次触发后由
 * [io.github.zhundianapp.zhundian.repository.ReminderRepository.onTriggered]
 * 将其推进为「当前时间 + 间隔」，从而实现永久循环。
 */
@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val intervalValue: Int,
    val intervalUnit: IntervalUnit,
    /** 声音开关：到点时直接播放铃声（绕过系统通知声音设置，DND 下走闹钟流仍可响）。 */
    val soundEnabled: Boolean = true,
    /** 自定义铃声 URI（系统铃声选择器或本地音频所选）；null 时播放系统默认闹钟铃声。 */
    val soundUri: String? = null,
    /** 响铃音量（0~1，相对闹钟流音量缩放），用于替代系统通知音量不可控的场景。 */
    @ColumnInfo(defaultValue = "0.7")
    val soundVolume: Float = 0.7f,
    /** 响铃时长（秒）：到点单次响铃的最长时长，到点后自动停止。 */
    @ColumnInfo(defaultValue = "5")
    val soundDurationSeconds: Int = 5,
    val vibrationEnabled: Boolean = true,
    /** 顶部弹窗：到点时在屏幕上方弹出悬浮窗（需「显示在其他应用上层」权限）。 */
    val overlayEnabled: Boolean = false,
    /** 自定义通知提示语：到点横幅通知与悬浮窗展示的正文；null 或空白时回退默认文案。 */
    val message: String? = null,
    /**
     * 锚定参考点（epoch millis，锚定日当天触发时刻的具体时间点）：仅「天」间隔生效，
     * 到点固定在 anchorAt + k×间隔 的序列上（每 N 天在固定时刻触发），不随创建/触发/改期漂移。
     * null 表示不锚定（旧行为：触发后按「当前时间 + 间隔」推进）。
     */
    val scheduleAnchorAt: Long? = null,
    val isEnabled: Boolean = true,
    val nextTriggerAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)

/** 间隔单位：分钟 / 小时 / 天 */
enum class IntervalUnit { MINUTES, HOURS, DAYS }
