package io.github.zhundianapp.zhundian.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.zhundianapp.zhundian.IntervalReminderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻前台服务（specialUse），为提醒可靠性做三层保障：
 * - 提升进程存活优先级，降低国产 ROM 杀后台导致的漏提醒概率；
 * - 进程内计时器：读库拿到最近的触发时间（间隔提醒 [Reminder.nextTriggerAt]
 *   与未提醒的日程触发点取最小值），到点直接触发——不依赖系统广播，进程活着就一定能触发；
 * - 每次启动重排全部精确闹钟（提醒 + 日程），作为进程被杀 / Doze 时的系统级兜底；
 * - 定期增量同步系统日历日程（30 分钟一次，受全局设置控制）。
 */
class ReminderService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1

        /** 无提醒 / 距下次触发较远时，最长轮询间隔，用于及时感知数据变化。 */
        private const val POLL_INTERVAL_MILLIS = 60_000L

        /** 系统日历增量同步间隔。 */
        private const val SYNC_INTERVAL_MILLIS = 30 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReminderService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as IntervalReminderApp
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                app.container.notificationHelper.serviceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            // 受限 ROM / Android 14 restricted 状态：前台服务启动失败，退化为纯系统闹钟
            stopSelf()
            return START_NOT_STICKY
        }

        // 每次启动重排全部闹钟（提醒 + 日程），自愈被 ROM 清掉的调度
        scope.launch {
            app.container.reminderRepository.rescheduleAll()
            app.container.calendarEventRepository.rescheduleAllEvents()
        }

        // 系统日历增量同步循环（受 autoSyncEnabled 设置控制）
        scope.launch { runSyncLoop(app) }

        // 进程内计时器循环：只启动一次
        if (loopJob == null) {
            loopJob = scope.launch { runSchedulerLoop(app) }
        }
        return START_STICKY
    }

    private suspend fun runSchedulerLoop(app: IntervalReminderApp) {
        val repository = app.container.reminderRepository
        val eventRepository = app.container.calendarEventRepository
        val helper = app.container.notificationHelper
        val settingsRepository = app.container.settingsRepository
        while (scope.isActive) {
            val nextReminder = repository.nextTriggerAt()
            val nextEvent = eventRepository.nextEventTriggerAt()
            val next = listOfNotNull(nextReminder, nextEvent).minOrNull()
            val wait = if (next == null) {
                POLL_INTERVAL_MILLIS
            } else {
                (next - System.currentTimeMillis()).coerceIn(0L, POLL_INTERVAL_MILLIS)
            }
            delay(wait)
            repository.triggerDueNow().forEach { helper.showReminder(it) }
            eventRepository.eventsDueNow().forEach {
                helper.showCalendarEvent(it, settingsRepository.load())
            }
        }
    }

    private suspend fun runSyncLoop(app: IntervalReminderApp) {
        val eventRepository = app.container.calendarEventRepository
        val settingsRepository = app.container.settingsRepository
        while (scope.isActive) {
            delay(SYNC_INTERVAL_MILLIS)
            if (settingsRepository.load().autoSyncEnabled) {
                eventRepository.sync()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
