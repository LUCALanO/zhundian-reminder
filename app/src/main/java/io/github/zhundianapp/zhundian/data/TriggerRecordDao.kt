package io.github.zhundianapp.zhundian.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerRecordDao {

    @Insert
    suspend fun insert(record: TriggerRecord): Long

    /** 指定提醒的最新一条「已触发」记录；无则返回 null。 */
    @Query(
        "SELECT * FROM trigger_records WHERE reminderId = :reminderId AND status = 'TRIGGERED' " +
            "ORDER BY triggerAt DESC LIMIT 1"
    )
    suspend fun latestTriggered(reminderId: Long): TriggerRecord?

    /** 指定提醒的最新一条记录（不限状态）；无则返回 null。 */
    @Query("SELECT * FROM trigger_records WHERE reminderId = :reminderId ORDER BY triggerAt DESC LIMIT 1")
    suspend fun latest(reminderId: Long): TriggerRecord?

    /** 指定提醒在 [start, end) 时间范围内的全部记录（日历单提醒视图，按触发时间升序）。 */
    @Query(
        "SELECT * FROM trigger_records WHERE reminderId = :reminderId " +
            "AND triggerAt >= :start AND triggerAt < :end ORDER BY triggerAt ASC"
    )
    fun getBetween(reminderId: Long, start: Long, end: Long): Flow<List<TriggerRecord>>

    /** 全部提醒在 [start, end) 时间范围内的记录（日历全局汇总视图）。 */
    @Query(
        "SELECT * FROM trigger_records " +
            "WHERE triggerAt >= :start AND triggerAt < :end ORDER BY triggerAt ASC"
    )
    fun getAllBetween(start: Long, end: Long): Flow<List<TriggerRecord>>

    @Update
    suspend fun update(record: TriggerRecord)

    /** 精确删除单条历史记录（日历里删除某天某条记录）。 */
    @Query("DELETE FROM trigger_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 批量删除多条历史记录（日历勾选批量删除）；ids 为空时无操作。 */
    @Query("DELETE FROM trigger_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 删除某提醒的全部历史记录（删除提醒时调用）。 */
    @Query("DELETE FROM trigger_records WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)
}
