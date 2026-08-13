package io.github.zhundianapp.zhundian.repository

import io.github.zhundianapp.zhundian.alarm.AlarmScheduler
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.CalendarEventDao
import io.github.zhundianapp.zhundian.data.CalendarSourceInfo
import io.github.zhundianapp.zhundian.data.SettingsRepository
import io.github.zhundianapp.zhundian.notification.NotificationHelper
import io.github.zhundianapp.zhundian.permission.PermissionManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow

/** 一次系统日历同步的结果。 */
sealed class SyncResult {
    /** 同步成功（inserted 新增 / refreshed 刷新 / removed 清理的实例数）。 */
    data class Success(val inserted: Int, val refreshed: Int, val removed: Int) : SyncResult()

    /** 读取系统日历失败。 */
    data class Error(val message: String?) : SyncResult()

    /** 未授予 READ_CALENDAR 权限。 */
    object NotPermitted : SyncResult()
}

/**
 * 系统日历日程的业务入口，镜像 [io.github.zhundianapp.zhundian.repository.ReminderRepository]
 * 的门控/仓库风格，协调数据库、闹钟调度、通知与同步，保证状态一致。
 *
 * 核心正确性：
 * - **墓碑**：用户删除置 `deleted=1`，同步的 insertIgnore 因唯一键命中不复活它；
 * - **提醒去重**：`tryTriggerEvent` 是广播与前台服务轮询共用的唯一触发入口，
 *   `reminded` 门控保证一次性提醒只触发一次；
 * - **错过兜底**：回看窗内 12 小时内补发，更早的由 sync 结束时静默标记，防远古事件轰炸。
 */
class CalendarEventRepository(
    private val dao: CalendarEventDao,
    private val scheduler: AlarmScheduler,
    private val settingsRepository: SettingsRepository,
    private val permissionManager: PermissionManager,
    private val notificationHelper: NotificationHelper,
    private val reader: SystemCalendarReader
) {

    /** 指定时间范围内的日程（日程视图按月查询）。 */
    fun observeEvents(start: Long, end: Long): Flow<List<CalendarEvent>> =
        dao.observeBetween(start, end)

    /** 系统日历来源列表，供设置页「按来源选择性同步」展示。无权限时抛 [SecurityException]。 */
    suspend fun queryCalendarSources(): List<CalendarSourceInfo> = reader.queryCalendars()

    /**
     * 增量同步系统日历到本库，并重排闹钟。
     *
     * 流程（事务包裹）：
     * 1. 无权限直接返回 [SyncResult.NotPermitted]；
     * 2. 查询窗口 [now-24h, now+12月] 的系统实例；
     * 3. 逐实例 insertIgnore（已存在不重插，保留墓碑/reminded）+ refreshMetadata（非墓碑才更新元数据）；
     * 4. 裁剪窗口外记录；清理窗口内系统已删除/改时间的本地行并取消对应闹钟；
     * 5. 静默补标超期（>12h 前）未提醒实例，防补发轰炸；
     * 6. 重排全部未提醒闹钟并记录 lastSyncAt。
     */
    suspend fun sync(): SyncResult {
        if (!permissionManager.hasCalendarReadPermission()) return SyncResult.NotPermitted
        val now = System.currentTimeMillis()
        val windowStart = now - LOOKBACK_MILLIS
        val windowEnd = now + FORWARD_WINDOW_MILLIS

        val instances = try {
            val settings = settingsRepository.load()
            // 未配置显式集合时走默认策略：仅同步可编辑日历，自动排除节日/节气等只读系统日历
            val enabledIds = settings.enabledCalendarIds
                ?: reader.queryCalendars().filter { !it.isReadOnly }.map { it.id }.toSet()
            reader.query(windowStart, windowEnd, enabledIds)
        } catch (e: SecurityException) {
            return SyncResult.NotPermitted
        } catch (e: Exception) {
            return SyncResult.Error(e.message)
        }

        // 顺序执行，不包 Room 事务：同步幂等且可重试，某步失败由下次同步自愈。
        // （不用 db.withTransaction：其内部切到 TransactionExecutor 线程，Robolectric 的
        // SQLite 模拟不支持跨线程访问连接，会抛 Illegal connection pointer。）
        var inserted = 0
        var refreshed = 0
        var removed = 0
        instances.forEach { instance ->
            // IGNORE 冲突返回 -1：表示该 (sourceEventId,startAt) 已存在 → 仅刷新元数据
            if (dao.insertIgnore(instance.toEvent()) != -1L) {
                inserted++
            } else {
                dao.refreshMetadata(
                    title = instance.title,
                    description = instance.description,
                    location = instance.location,
                    endAt = instance.endAt,
                    allDay = instance.allDay,
                    sourceCalendarId = instance.sourceCalendarId,
                    sourceEventId = instance.sourceEventId,
                    startAt = instance.startAt
                )
                refreshed++
            }
        }
        dao.pruneOutside(windowStart, windowEnd)

        val fetchedKeys = instances.map { it.sourceEventId to it.startAt }.toSet()
        dao.getInWindow(windowStart, windowEnd).forEach { local ->
            // App 自建日程（isLocal）不属于系统，不参与「系统已删除 → 本地清理」
            if (!local.isLocal && (local.sourceEventId to local.startAt) !in fetchedKeys) {
                // 系统已删除 / 改时间：取消闹钟并物理清理（该行无墓碑语义需求）
                scheduler.cancelEvent(local)
                dao.deleteById(local.id)
                removed++
            }
        }

        dao.markOlderAsReminded(now - MISSED_GRACE_MILLIS)

        rescheduleAllEvents()
        settingsRepository.setLastSyncAt(now)
        return SyncResult.Success(inserted = inserted, refreshed = refreshed, removed = removed)
    }

    /** 重排全部未提醒的未来日程闹钟（同步后 / 服务启动自愈）。 */
    suspend fun rescheduleAllEvents() {
        val lead = leadMillis()
        dao.getUpcoming(System.currentTimeMillis()).forEach { event ->
            if (!event.deleted && !event.reminded) scheduler.scheduleEvent(event, lead)
        }
    }

    /**
     * 唯一触发门控（闹钟广播与前台服务轮询共用）：未到点 / 已删除 / 已提醒返回 null；
     * 错过超过 [MISSED_GRACE_MILLIS] 静默标记已提醒（不补发）；正常到点则标记已提醒、
     * 取消闹钟并返回事件供调用方弹通知。
     */
    suspend fun tryTriggerEvent(eventId: Long): CalendarEvent? {
        val event = dao.getById(eventId) ?: return null
        if (event.deleted || event.reminded) return null
        val triggerAt = event.startAt - leadMillis()
        val now = System.currentTimeMillis()
        if (now < triggerAt - TRIGGER_GRACE_MILLIS) return null
        if (now > triggerAt + MISSED_GRACE_MILLIS) {
            // 错过太久：静默标记，不补发远古事件
            dao.markReminded(eventId)
            scheduler.cancelEvent(event)
            return null
        }
        dao.markReminded(eventId)
        scheduler.cancelEvent(event)
        return event
    }

    /** 当前到点（含回看窗内补发）且未提醒的日程，供前台服务轮询触发。 */
    suspend fun eventsDueNow(): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val lead = leadMillis()
        return dao.getUpcoming(now - LOOKBACK_MILLIS)
            .filter { !it.deleted && !it.reminded }
            .filter { event ->
                val triggerAt = event.startAt - lead
                now in (triggerAt - TRIGGER_GRACE_MILLIS)..(triggerAt + MISSED_GRACE_MILLIS)
            }
            .mapNotNull { tryTriggerEvent(it.id) }
    }

    /** 最近的未提醒日程触发时间；无则返回 null（供服务循环睡眠）。 */
    suspend fun nextEventTriggerAt(): Long? {
        val now = System.currentTimeMillis()
        val lead = leadMillis()
        return dao.getUpcoming(now)
            .filter { !it.deleted && !it.reminded }
            .map { it.startAt - lead }
            .filter { it > now }
            .minOrNull()
    }

    /**
     * App 新建一条本地日程（仅存本 App，不写入系统日历）。
     *
     * - 本地始终入库（isLocal=1，sourceEventId 为负合成 id，不受同步裁剪/清理影响）；
     * - `remind`：用现有日程闹钟机制调度（到点走全局日程提醒设置）。
     *
     * @return 入库后的日程实体（含生成 id）。
     */
    suspend fun createLocalEvent(
        title: String,
        description: String?,
        location: String?,
        startAt: Long,
        endAt: Long,
        allDay: Boolean,
        remind: Boolean
    ): CalendarEvent {
        val event = CalendarEvent(
            title = title,
            description = description,
            location = location,
            startAt = startAt,
            endAt = endAt,
            allDay = allDay,
            sourceEventId = nextLocalSourceEventId(),
            sourceCalendarId = CalendarEvent.SOURCE_CALENDAR_ID_LOCAL,
            isLocal = true
        )
        val rowId = dao.insertIgnore(event)
        if (remind && rowId != -1L) {
            scheduler.scheduleEvent(event.copy(id = rowId), leadMillis())
        }
        return event.copy(id = rowId)
    }

    /**
     * 从本 App 移除一条日程：置墓碑 + 取消闹钟与通知。
     * 仅作用于本 App 内部，不影响系统日历。
     */
    suspend fun deleteEvent(event: CalendarEvent) {
        dao.markDeleted(event.id)
        scheduler.cancelEvent(event)
        notificationHelper.cancelEventNotification(event.id)
    }

    /** 全部墓碑日程（升序；供「已删除日程」持久入口）。 */
    fun observeDeletedEvents(): Flow<List<CalendarEvent>> = dao.observeDeletedEvents()

    /**
     * 恢复一条被删除的日程：清墓碑位，未来未提醒的重新调度闹钟。
     * 幂等：行不存在 / 已恢复（deleted=0）/ 永久删除（permanentlyDeleted=1）时静默 no-op。
     * 仅作用于本 App 内部，不影响系统日历。
     *
     * 注意必须 markRestored 后重新 getById 再调度——scheduleEvent 首行守卫
     * `event.deleted`，若用内存里 deleted=true 的旧对象调度永远不会成功。
     */
    suspend fun restoreEvent(event: CalendarEvent) {
        val current = dao.getById(event.id) ?: return
        if (!current.deleted || current.permanentlyDeleted) return
        dao.markRestored(current.id)
        val restored = dao.getById(current.id) ?: return
        if (!restored.reminded) {
            scheduler.scheduleEvent(restored, leadMillis())
        }
    }

    /**
     * 永久删除一条日程：
     * - App 自建（isLocal）：本地物理删除（本地行不会被同步复活，无需墓碑）；
     * - 导入日程：置墓碑 + 永久标记（保留行防同步复活），不影响系统日历。
     */
    suspend fun deleteEventPermanently(event: CalendarEvent) {
        scheduler.cancelEvent(event)
        notificationHelper.cancelEventNotification(event.id)
        if (event.isLocal) {
            dao.deleteById(event.id)
        } else {
            dao.markDeletedPermanently(event.id)
        }
    }

    /** 当前全局提前量（毫秒）。 */
    private fun leadMillis(): Long = settingsRepository.load().leadMinutes * 60_000L

    companion object {
        /** App 自建日程的合成 sourceEventId 序号（跨进程重启可能复用，配合时间戳足够唯一）。 */
        private val localSeq = AtomicLong(0)

        /** 生成负数合成 sourceEventId：`-(时间戳拼接序号)`，与系统真实正 id 永不冲突。 */
        private fun nextLocalSourceEventId(): Long {
            val now = System.currentTimeMillis()
            return -(now * 10_000 + localSeq.getAndIncrement())
        }

        /** 向前同步窗口：未来 12 个月。 */
        const val FORWARD_WINDOW_MILLIS = 12L * 30 * 24 * 3600_000L

        /** 回看窗口：24 小时，兜底刚错过的事件（含「今天」的全天事件）。 */
        const val LOOKBACK_MILLIS = 24L * 3600_000L

        /** 错过容差：触发点之后 12 小时内仍补发，更早的静默标记。 */
        const val MISSED_GRACE_MILLIS = 12L * 3600_000L

        /** 触发容差：允许提前该范围内判定已到点，避免计时/广播微小的提前量导致漏触发。 */
        const val TRIGGER_GRACE_MILLIS = 500L
    }
}
