package io.github.zhundianapp.zhundian.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.zhundianapp.zhundian.IntervalReminderApp
import io.github.zhundianapp.zhundian.service.ReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 系统重启后：重排全部已启用提醒的闹钟，并拉起常驻前台服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? IntervalReminderApp ?: return

        // BOOT_COMPLETED 广播豁免，允许后台启动前台服务
        try {
            ReminderService.start(context)
        } catch (_: Exception) {
            // 受限 ROM 兜底：忽略即可
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.reminderRepository.rescheduleAll()
                app.container.calendarEventRepository.rescheduleAllEvents()
                // 重启期间系统日历可能有变，开机后补一次增量同步
                app.container.calendarEventRepository.sync()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
