package io.github.zhundianapp.zhundian.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 系统日历日程实例（导入到本 App 的日程提醒）或 App 自建的本地日程。
 *
 * 同步时把系统日历（CalendarContract.Instances）未来窗口内的每次发生展开为一条记录
 * （重复日程按发生展开，一次发生 = 一条记录 = 一个精确闹钟）。
 * [sourceEventId] + [startAt] 唯一，用于增量同步去重。
 *
 * 两类来源：
 * - **系统导入**（[isLocal] = false）：[sourceEventId] 为系统 Events._ID（正数）。
 * - **App 自建**（[isLocal] = true）：[sourceEventId] 为负数合成 id，与系统 id 永不冲突；
 *   仅存本 App，不写入系统日历。
 *
 * - [reminded]：是否已提醒（一次性日程提醒后不再重复）。
 * - [deleted]：墓碑位。用户从本 App 删除的日程仅置此位而非物理删除，
 *   避免下次同步把系统日历中仍存在的日程重新导入（复活）。
 */
@Entity(
    tableName = "calendar_events",
    indices = [Index(value = ["sourceEventId", "startAt"], unique = true)]
)
data class CalendarEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    /** 事件开始时间（epoch millis）。 */
    val startAt: Long,
    /** 事件结束时间（epoch millis）。 */
    val endAt: Long,
    /** 全天事件。 */
    val allDay: Boolean = false,
    /**
     * 系统日历 Events._ID（用于增量同步去重）。
     * App 自建日程（[isLocal]）为负数合成 id，与系统正 id 永不冲突。
     */
    val sourceEventId: Long,
    /**
     * 所属系统日历 id（用于日程视图按日历分色）。
     * App 自建日程固定为哨兵 [SOURCE_CALENDAR_ID_LOCAL]（-1）。
     */
    val sourceCalendarId: Long,
    /** App 自建日程标记：非系统导入，不受同步裁剪/清理影响。 */
    val isLocal: Boolean = false,
    /** 是否已提醒（一次性，提醒后不再触发）。 */
    val reminded: Boolean = false,
    /** 墓碑：用户从本 App 移除，同步不再复活。 */
    val deleted: Boolean = false,
    /** 永久删除标记：墓碑保留防同步复活，但从「已删除日程」隐藏且不可恢复。 */
    val permanentlyDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** App 自建日程的固定系统日历哨兵 id（用于日历色点区分）。 */
        const val SOURCE_CALENDAR_ID_LOCAL = -1L
    }
}
