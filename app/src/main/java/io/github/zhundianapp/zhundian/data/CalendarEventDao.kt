package io.github.zhundianapp.zhundian.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {

    /**
     * 插入新实例。已存在同 (sourceEventId, startAt) 的实例（含墓碑）时忽略不重插，
     * 从而保留用户的删除（deleted=1）与提醒（reminded=1）状态。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: CalendarEvent): Long

    /**
     * 刷新实例元数据（标题/时间/地点/日历）。仅更新非墓碑行，不碰 reminded/createdAt，
     * 保证同步不覆盖用户删除或已提醒的状态。
     */
    @Query(
        "UPDATE calendar_events SET title = :title, description = :description, " +
            "location = :location, endAt = :endAt, allDay = :allDay, " +
            "sourceCalendarId = :sourceCalendarId " +
            "WHERE sourceEventId = :sourceEventId AND startAt = :startAt AND deleted = 0"
    )
    suspend fun refreshMetadata(
        title: String,
        description: String?,
        location: String?,
        endAt: Long,
        allDay: Boolean,
        sourceCalendarId: Long,
        sourceEventId: Long,
        startAt: Long
    )

    /** 指定时间范围（按事件开始时间）内的非墓碑实例，升序（日程视图按月查询）。 */
    @Query(
        "SELECT * FROM calendar_events WHERE deleted = 0 " +
            "AND startAt >= :start AND startAt < :end ORDER BY startAt ASC"
    )
    fun observeBetween(start: Long, end: Long): Flow<List<CalendarEvent>>

    /** 指定时间之后的未提醒、非墓碑实例，升序（调度/轮询用）。 */
    @Query(
        "SELECT * FROM calendar_events WHERE deleted = 0 AND reminded = 0 " +
            "AND startAt >= :from ORDER BY startAt ASC"
    )
    suspend fun getUpcoming(from: Long): List<CalendarEvent>

    /** 指定时间范围内的全部实例（含墓碑，增量清理比对用）。 */
    @Query("SELECT * FROM calendar_events WHERE startAt >= :start AND startAt <= :end")
    suspend fun getInWindow(start: Long, end: Long): List<CalendarEvent>

    /** 全部可恢复的墓碑日程（升序；供「已删除日程」持久入口，永久删除的不显示）。 */
    @Query("SELECT * FROM calendar_events WHERE deleted = 1 AND permanentlyDeleted = 0 ORDER BY startAt ASC")
    fun observeDeletedEvents(): Flow<List<CalendarEvent>>

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: Long): CalendarEvent?

    @Query("UPDATE calendar_events SET reminded = 1 WHERE id = :id")
    suspend fun markReminded(id: Long)

    @Query("UPDATE calendar_events SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Query("UPDATE calendar_events SET deleted = 0 WHERE id = :id")
    suspend fun markRestored(id: Long)

    /** 永久删除：置墓碑 + 永久标记（保留行防同步复活，已删除列表不再显示）。 */
    @Query("UPDATE calendar_events SET deleted = 1, permanentlyDeleted = 1 WHERE id = :id")
    suspend fun markDeletedPermanently(id: Long)

    /** 静默补标 [before] 之前的未提醒实例为已提醒，防止同步时对远古事件批量补发。 */
    @Query("UPDATE calendar_events SET reminded = 1 WHERE deleted = 0 AND reminded = 0 AND startAt < :before")
    suspend fun markOlderAsReminded(before: Long)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 裁剪窗口之外的实例（含墓碑），保持表规模可控。
     * 只作用于系统导入的行（isLocal = 0）：App 自建日程即使超出同步窗口也保留，属于 App 自己的数据。
     */
    @Query("DELETE FROM calendar_events WHERE isLocal = 0 AND (startAt < :start OR startAt > :end)")
    suspend fun pruneOutside(start: Long, end: Long)
}
