package io.github.zhundianapp.zhundian.repository

import io.github.zhundianapp.zhundian.alarm.AlarmScheduler
import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.data.ReminderDao
import io.github.zhundianapp.zhundian.data.TriggerRecord
import io.github.zhundianapp.zhundian.data.TriggerRecordDao
import io.github.zhundianapp.zhundian.data.TriggerStatus
import io.github.zhundianapp.zhundian.notification.NotificationHelper
import io.github.zhundianapp.zhundian.notification.SoundPlayer
import io.github.zhundianapp.zhundian.util.IntervalCalculator
import kotlinx.coroutines.flow.Flow

/**
 * 业务逻辑唯一入口，协调数据库、闹钟调度与到点通知，保证三者状态始终一致。
 */
class ReminderRepository(
    private val dao: ReminderDao,
    private val triggerRecordDao: TriggerRecordDao,
    private val scheduler: AlarmScheduler,
    private val notificationHelper: NotificationHelper
) {

    fun observeAll(): Flow<List<Reminder>> = dao.observeAll()

    /** 指定提醒在 [start, end) 内的触发历史（日历单提醒视图）。 */
    fun observeTriggerRecords(reminderId: Long, start: Long, end: Long): Flow<List<TriggerRecord>> =
        triggerRecordDao.getBetween(reminderId, start, end)

    /** 全部提醒在 [start, end) 内的触发历史（日历全局汇总视图）。 */
    fun observeAllTriggerRecords(start: Long, end: Long): Flow<List<TriggerRecord>> =
        triggerRecordDao.getAllBetween(start, end)

    /** 精确删除单条触发历史记录（日历里删除某天某条记录）。 */
    suspend fun deleteTriggerRecord(record: TriggerRecord) {
        triggerRecordDao.deleteById(record.id)
    }

    /** 批量删除多条触发历史记录（日历勾选批量删除）；空集合时安全忽略。 */
    suspend fun deleteTriggerRecords(records: Collection<TriggerRecord>) {
        if (records.isEmpty()) return
        triggerRecordDao.deleteByIds(records.map { it.id })
    }

    suspend fun getById(id: Long): Reminder? = dao.getById(id)

    /**
     * 创建提醒：入库后立即调度。
     * 「天」间隔可指定 triggerTimeMinutes（分钟，0~1439）作为固定触发时刻——
     * 以创建当天该时刻为锚，之后每 N 天在该时刻触发；null 则按旧行为「当前时间 + 间隔」。
     */
    suspend fun create(
        name: String,
        intervalValue: Int,
        intervalUnit: IntervalUnit,
        vibrationEnabled: Boolean,
        triggerTimeMinutes: Int? = null,
        overlayEnabled: Boolean = false,
        message: String? = null,
        soundEnabled: Boolean = true,
        soundUri: String? = null,
        soundVolume: Float = SoundPlayer.DEFAULT_VOLUME,
        soundDurationSeconds: Int = SoundPlayer.DEFAULT_DURATION_SECONDS
    ): Reminder {
        val now = System.currentTimeMillis()
        val anchorAt = IntervalCalculator.anchorAtToday(triggerTimeMinutes)
        val reminder = Reminder(
            name = name.trim(),
            intervalValue = intervalValue,
            intervalUnit = intervalUnit,
            soundEnabled = soundEnabled,
            soundUri = soundUri,
            soundVolume = soundVolume,
            soundDurationSeconds = soundDurationSeconds,
            vibrationEnabled = vibrationEnabled,
            overlayEnabled = overlayEnabled,
            message = message?.trim()?.takeIf { it.isNotEmpty() },
            scheduleAnchorAt = anchorAt,
            isEnabled = true,
            nextTriggerAt = IntervalCalculator.nextTriggerAt(anchorAt, intervalValue, intervalUnit, now),
            createdAt = now
        )
        val id = dao.insert(reminder)
        val saved = reminder.copy(id = id)
        scheduler.schedule(saved)
        return saved
    }

    /** 更新提醒：重置 nextTriggerAt 为「当前时间 + 间隔」并重排闹钟。 */
    suspend fun update(reminder: Reminder) {
        val now = System.currentTimeMillis()
        val updated = reminder.copy(nextTriggerAt = IntervalCalculator.nextTriggerAt(reminder, now))
        dao.update(updated)
        if (updated.isEnabled) scheduler.schedule(updated) else scheduler.cancel(updated)
        // 编辑保存时若关掉了声音，立即停掉正在响的铃声（幂等）
        if (!updated.soundEnabled) notificationHelper.stopSound()
    }

    /** 删除提醒：先取消闹钟与到点通知并停铃，再移除数据与触发历史。 */
    suspend fun delete(reminder: Reminder) {
        scheduler.cancel(reminder)
        notificationHelper.stopSound()
        notificationHelper.cancelReminder(reminder.id)
        triggerRecordDao.deleteByReminderId(reminder.id)
        dao.delete(reminder)
    }

    /** 启停提醒：启用时重置为下一间隔触发，停用时取消闹钟与到点通知并停铃。 */
    suspend fun setEnabled(reminder: Reminder, enabled: Boolean) {
        val now = System.currentTimeMillis()
        val updated = if (enabled) {
            reminder.copy(isEnabled = true, nextTriggerAt = IntervalCalculator.nextTriggerAt(reminder, now))
        } else {
            reminder.copy(isEnabled = false)
        }
        dao.update(updated)
        if (enabled) scheduler.schedule(updated) else {
            scheduler.cancel(updated)
            notificationHelper.stopSound()
            notificationHelper.cancelReminder(reminder.id)
        }
    }

    /** 到点触发后：推进 nextTriggerAt 并安排下一次，实现永久循环。 */
    suspend fun onTriggered(reminderId: Long) {
        val reminder = dao.getById(reminderId) ?: return
        if (!reminder.isEnabled) return
        val now = System.currentTimeMillis()
        val anchorAt = reminder.scheduleAnchorAt
        val next = if (anchorAt != null && reminder.intervalUnit == IntervalUnit.DAYS) {
            // 锚定：以「刚触发的计划时刻」为基准回到固定时刻序列（含改期后的触发，
            // 改期只影响本次、不移动节奏）；max 保证设备关机补触发时下一时刻仍在未来，
            // 不会对错过的时刻连环补发。
            val after = maxOf(reminder.nextTriggerAt, now)
            IntervalCalculator.nextScheduledAfter(
                anchorAt = anchorAt,
                intervalMillis = IntervalCalculator.intervalMillis(reminder),
                after = after
            )
        } else {
            IntervalCalculator.nextTriggerAt(reminder, now)
        }
        val updated = reminder.copy(nextTriggerAt = next)
        dao.update(updated)
        scheduler.schedule(updated)
    }

    /**
     * 触发门控：仅当提醒已启用且已到点（允许小量容差）时才推进并返回该提醒，否则返回 null。
     * AlarmReceiver（系统闹钟兜底）与 ReminderService（进程内计时）共用此方法，天然去重。
     * 触发成功后写入一条「已触发」历史记录（triggerAt 取触发前的计划触发时间）。
     */
    suspend fun tryTrigger(reminderId: Long): Reminder? {
        val reminder = dao.getById(reminderId) ?: return null
        if (!reminder.isEnabled) return null
        if (System.currentTimeMillis() < reminder.nextTriggerAt - TRIGGER_GRACE_MILLIS) return null
        onTriggered(reminderId)
        triggerRecordDao.insert(
            TriggerRecord(reminderId = reminderId, triggerAt = reminder.nextTriggerAt)
        )
        return reminder
    }

    /**
     * 通知栏「完成」：收起当前到点通知，并把最新一条「已触发」历史记录标记为已完成。
     * 幂等：提醒已删除 / 无待处理记录时安全忽略。
     */
    suspend fun completeTrigger(reminderId: Long) {
        notificationHelper.stopSound()
        notificationHelper.dismissOverlay()
        notificationHelper.cancelReminder(reminderId)
        triggerRecordDao.latestTriggered(reminderId)?.let { record ->
            triggerRecordDao.update(
                record.copy(status = TriggerStatus.COMPLETED, resolvedAt = System.currentTimeMillis())
            )
        }
    }

    /**
     * 通知栏「再隔 1 小时提醒」：收起当前到点通知，把最新「已触发」记录标记为已改期，
     * 并将下次触发时间临时改为「当前时间 + 1 小时」后重排闹钟。
     * 幂等：提醒已删除或已停用时只收起通知，不改期。
     */
    suspend fun snoozeTrigger(reminderId: Long) {
        notificationHelper.stopSound()
        notificationHelper.dismissOverlay()
        notificationHelper.cancelReminder(reminderId)
        val reminder = dao.getById(reminderId) ?: return
        if (!reminder.isEnabled) return
        triggerRecordDao.latestTriggered(reminderId)?.let { record ->
            triggerRecordDao.update(
                record.copy(status = TriggerStatus.SNOOZED, resolvedAt = System.currentTimeMillis())
            )
        }
        val now = System.currentTimeMillis()
        val updated = reminder.copy(nextTriggerAt = now + IntervalCalculator.SNOOZE_MILLIS)
        dao.update(updated)
        scheduler.schedule(updated)
    }

    /** 触发当前所有已到点的启用提醒，返回触发成功的列表（供调用方弹通知）。 */
    suspend fun triggerDueNow(): List<Reminder> {
        val now = System.currentTimeMillis()
        return dao.getEnabled()
            .filter { now >= it.nextTriggerAt - TRIGGER_GRACE_MILLIS }
            .mapNotNull { tryTrigger(it.id) }
    }

    /** 最近的启用提醒触发时间；无启用提醒返回 null。 */
    suspend fun nextTriggerAt(): Long? =
        dao.getEnabled().minOfOrNull { it.nextTriggerAt }

    /** 重排全部已启用提醒（开机后，或前台服务启动时自愈被系统清掉的闹钟）。 */
    suspend fun rescheduleAll() {
        dao.getEnabled().forEach { scheduler.schedule(it) }
    }

    private companion object {
        /** 触发容差：允许在该范围内判定已到点，避免计时/广播微小的提前量导致漏触发。 */
        const val TRIGGER_GRACE_MILLIS: Long = 500L
    }
}
