package io.github.zhundianapp.zhundian

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.zhundianapp.zhundian.di.AppContainer
import io.github.zhundianapp.zhundian.util.AppLanguage
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IntervalReminderApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Android 8–12 上应用已保存的应用内语言（Android 13+ 走系统 LocaleManager）
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 提前创建全部通知渠道，保证前台服务常驻通知与到点提醒都走预期渠道
        container.notificationHelper.ensureChannels()
        // 后台预热数据库与 DAO：打开数据库并预先执行各页面的主要查询，
        // 让 Room 的 SQL 语句在后台完成首次 prepare，避免首次进入页面时的冷启动等待。
        // Robolectric 的 SQLite 模拟不支持跨线程并发，测试环境跳过预热。
        if (!Build.FINGERPRINT.contains("robolectric")) {
            appScope.launch {
                val repo = container.reminderRepository
                repo.observeAll().first() // 列表页
                // 日历页：当月范围的查询足以完成 trigger_records 表语句的首次 prepare
                val month = YearMonth.now()
                val start = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                repo.observeAllTriggerRecords(start, end).first()
                // 日程视图：预热 calendar_events 当月查询
                container.calendarEventRepository.observeEvents(start, end).first()
                // 启动自动同步系统日历（已授权且未关闭自动同步时）。
                // 放同一非测试分支：Robolectric 的 SQLite 模拟不支持跨线程并发，测试环境跳过。
                val settings = container.settingsRepository.load()
                if (settings.autoSyncEnabled &&
                    container.permissionManager.hasCalendarReadPermission()
                ) {
                    container.calendarEventRepository.sync()
                }
            }
        }
    }
}
