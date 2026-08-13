package io.github.zhundianapp.zhundian.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 触发历史记录：某提醒的某一次到点触发的处理情况。
 *
 * 每次成功触发时写入一条 [status] 为 [TriggerStatus.TRIGGERED] 的记录；
 * 用户从通知栏点「完成」置为 COMPLETED、点「再隔 1 小时提醒」置为 SNOOZED，
 * 并记录处理时间 [resolvedAt]。删除提醒时一并清理其历史记录。
 */
@Entity(tableName = "trigger_records")
data class TriggerRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属提醒 id。 */
    val reminderId: Long,
    /** 该次触发的计划触发时间（epoch millis），即触发前该提醒的 nextTriggerAt。 */
    val triggerAt: Long,
    /** 处理状态：已触发 / 已完成 / 已改期。 */
    val status: TriggerStatus = TriggerStatus.TRIGGERED,
    /** 用户处理时间（完成或改期）；仍未处理为 null。 */
    val resolvedAt: Long? = null
)

/** 触发记录状态。 */
enum class TriggerStatus { TRIGGERED, COMPLETED, SNOOZED }
