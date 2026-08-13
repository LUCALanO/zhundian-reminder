package io.github.zhundianapp.zhundian.data

import io.github.zhundianapp.zhundian.notification.SoundPlayer

/**
 * 系统日历日程提醒的全局设置，**独立于**间隔提醒的每提醒设置。
 *
 * 默认值即用户要求：3 秒铃声 + 震动 + 顶部悬浮弹窗；提醒时机为事件开始时间（提前量 0）。
 */
data class CalendarSettings(
    /** 声音开关：到点时直接播放铃声（绕过系统通知声音设置）。 */
    val soundEnabled: Boolean = true,
    /** 自定义铃声 URI 字符串；null 用系统默认闹钟铃声。 */
    val soundUri: String? = null,
    /** 响铃音量（0~1，相对闹钟流音量缩放）。 */
    val soundVolume: Float = SoundPlayer.DEFAULT_VOLUME,
    /** 响铃时长（秒）：默认 3 秒（与间隔提醒默认 5 秒刻意区分）。 */
    val soundDurationSeconds: Int = 3,
    /** 震动开关：到点时直接调 Vibrator 强制震动。 */
    val vibrationEnabled: Boolean = true,
    /** 顶部悬浮弹窗开关：到点在屏幕上方弹出提醒（需「显示在其他应用上层」权限）。 */
    val overlayEnabled: Boolean = true,
    /** 提前提醒分钟数：0 表示事件开始时提醒。 */
    val leadMinutes: Int = 0,
    /** 自动同步系统日历开关。 */
    val autoSyncEnabled: Boolean = true,
    /**
     * 显式允许同步的系统日历 id 集合；null 表示未配置，使用默认策略
     * （仅同步可编辑日历，自动排除节日/节气等只读系统日历）。空集表示不同步任何日历。
     */
    val enabledCalendarIds: Set<Long>? = null,
    /** 上次成功同步时间（epoch millis）；0 表示从未同步。 */
    val lastSyncAt: Long = 0L
)
