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
 * 响应通知栏「完成 / 再隔 1 小时提醒」操作按钮的广播接收器。
 *
 * 与 AlarmReceiver 一致：先尝试拉起常驻前台服务保活，再用 goAsync + 协程异步
 * 访问数据库，避免阻塞广播线程。动作分发到 [ReminderRepository] 的
 * completeTrigger / snoozeTrigger，幂等处理（提醒已删除 / 停用 / 重复点击不崩溃）。
 */
class ActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPLETE = "io.github.zhundianapp.zhundian.action.COMPLETE"
        const val ACTION_SNOOZE = "io.github.zhundianapp.zhundian.action.SNOOZE"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"

        /** 与「完成」的 PendingIntent requestCode 错开，避免 FLAG_UPDATE_CURRENT 互相覆盖。 */
        const val ACTION_REQUEST_OFFSET = 1_000_000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_COMPLETE && action != ACTION_SNOOZE) return
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return
        val app = context.applicationContext as? IntervalReminderApp ?: return

        // 通知动作触发的广播允许短暂拉起前台服务，保证后续数据库操作时进程存活
        try {
            ReminderService.start(context)
        } catch (_: Exception) {
            // 受限 ROM 兜底：忽略即可，仍可完成本次动作处理
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 处理提醒动作即收起响铃，避免铃声持续到自动超时
                app.container.notificationHelper.stopSound()
                when (action) {
                    ACTION_COMPLETE -> app.container.reminderRepository.completeTrigger(reminderId)
                    ACTION_SNOOZE -> app.container.reminderRepository.snoozeTrigger(reminderId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
