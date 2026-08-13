package io.github.zhundianapp.zhundian.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.Reminder

/**
 * AlarmManager 调度封装。
 *
 * 优先使用精确闹钟 [AlarmManager.setExactAndAllowWhileIdle]（Doze 下仍可触发）；
 * 若精确闹钟权限缺失则降级为 [AlarmManager.setAndAllowWhileIdle] 并返回 false 供 UI 引导用户授权。
 *
 * 间隔提醒与系统日历日程共用同一调度器，但使用**独立 action** 区分：
 * [ACTION_TRIGGER]（提醒）与 [ACTION_TRIGGER_EVENT]（日程）。PendingIntent.filterEquals
 * 只比对 action/component，故两类闹钟的 requestCode 都从 1 开始也不会互相覆盖，cancel 精确。
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val ACTION_TRIGGER = "io.github.zhundianapp.zhundian.action.TRIGGER_REMINDER"
        const val ACTION_TRIGGER_EVENT = "io.github.zhundianapp.zhundian.action.TRIGGER_EVENT"
    }

    /** 设备是否允许本应用调度精确闹钟（Android 12+ 才有权限概念，更早恒为 true）。 */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * 为提醒安排闹钟。返回 true 表示已精确调度；false 表示精确权限缺失、已降级为非精确调度。
     */
    fun schedule(reminder: Reminder): Boolean {
        val triggerAt = reminder.nextTriggerAt
        if (triggerAt <= 0 || !reminder.isEnabled) return false
        val pendingIntent = pendingIntent(reminder.id)
        return if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            true
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            false
        }
    }

    fun cancel(reminder: Reminder) {
        alarmManager.cancel(pendingIntent(reminder.id))
    }

    /**
     * 为日程实例安排一次性闹钟，触发点为「事件开始时间 - 提前量」。
     * 已删除 / 已提醒 / 触发点已过时不调度。返回 true 表示精确调度；false 表示降级。
     */
    fun scheduleEvent(event: CalendarEvent, leadMillis: Long): Boolean {
        val triggerAt = event.startAt - leadMillis
        if (event.deleted || event.reminded || triggerAt <= System.currentTimeMillis()) return false
        val pendingIntent = eventPendingIntent(event.id)
        return if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            true
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            false
        }
    }

    fun cancelEvent(event: CalendarEvent) {
        alarmManager.cancel(eventPendingIntent(event.id))
    }

    private fun pendingIntent(id: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_REMINDER_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun eventPendingIntent(id: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_EVENT
            putExtra(EXTRA_EVENT_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
