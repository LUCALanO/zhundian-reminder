package io.github.zhundianapp.zhundian.di

import android.content.Context
import io.github.zhundianapp.zhundian.alarm.AlarmScheduler
import io.github.zhundianapp.zhundian.data.ReminderDatabase
import io.github.zhundianapp.zhundian.data.SettingsRepository
import io.github.zhundianapp.zhundian.notification.NotificationHelper
import io.github.zhundianapp.zhundian.permission.PermissionManager
import io.github.zhundianapp.zhundian.repository.AndroidSystemCalendarReader
import io.github.zhundianapp.zhundian.repository.CalendarEventRepository
import io.github.zhundianapp.zhundian.repository.ReminderRepository

/** 手动依赖注入容器：以惰性单例方式装配全局核心组件。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val scheduler: AlarmScheduler by lazy { AlarmScheduler(appContext) }

    private val database: ReminderDatabase by lazy { ReminderDatabase.getInstance(appContext) }

    val notificationHelper: NotificationHelper by lazy {
        NotificationHelper(appContext, permissionManager)
    }

    val reminderRepository: ReminderRepository by lazy {
        ReminderRepository(
            database.reminderDao(),
            database.triggerRecordDao(),
            scheduler,
            notificationHelper
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val calendarEventRepository: CalendarEventRepository by lazy {
        CalendarEventRepository(
            dao = database.calendarEventDao(),
            scheduler = scheduler,
            settingsRepository = settingsRepository,
            permissionManager = permissionManager,
            notificationHelper = notificationHelper,
            reader = AndroidSystemCalendarReader(appContext)
        )
    }

    val permissionManager: PermissionManager by lazy { PermissionManager(appContext) }
}
