package io.github.zhundianapp.zhundian.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.service.ReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 到点接收闹钟广播：按 action 分发到「间隔提醒」或「系统日历日程」触发。
 * 两类都走仓库统一的触发门控（ReminderRepository.tryTrigger / CalendarEventRepository.tryTriggerEvent），
 * 与前台服务的进程内计时器共用，天然去重。
 * 使用 goAsync + 协程异步读库，避免阻塞广播线程。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_TRIGGER -> handleReminder(context, intent)
            AlarmScheduler.ACTION_TRIGGER_EVENT -> handleEvent(context, intent)
        }
    }

    /** 间隔提醒到点：推进下一次触发并弹通知。 */
    private fun handleReminder(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return
        val app = context.applicationContext as? IntervalReminderApp ?: return

        // 精确闹钟广播允许从后台启动前台服务，保证广播处理完进程仍存活
        try {
            ReminderService.start(context)
        } catch (_: Exception) {
            // 受限 ROM 兜底：忽略即可，仍可完成本次触发
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fired = app.container.reminderRepository.tryTrigger(reminderId)
                if (fired != null) {
                    app.container.notificationHelper.showReminder(fired)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 系统日历日程到点：一次性触发（不循环），按全局设置弹通知。 */
    private fun handleEvent(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, -1L)
        if (eventId == -1L) return
        val app = context.applicationContext as? IntervalReminderApp ?: return

        try {
            ReminderService.start(context)
        } catch (_: Exception) {
            // 受限 ROM 兜底：忽略即可，仍可完成本次触发
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fired = app.container.calendarEventRepository.tryTriggerEvent(eventId)
                if (fired != null) {
                    app.container.notificationHelper.showCalendarEvent(
                        fired, app.container.settingsRepository.load()
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
