package io.github.zhundianapp.zhundian.ui.calendar

import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.TriggerRecord
import io.github.zhundianapp.zhundian.data.TriggerStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 日历网格中的一个日期格。 */
data class DayCell(
    val date: LocalDate,
    /** 是否属于当前展示月份（否则为前后月的补位格）。 */
    val inMonth: Boolean,
    val isToday: Boolean
)

/** 单提醒模式下某天记录聚合后的展示状态。 */
enum class DayStatus { NONE, TRIGGERED, SNOOZED, COMPLETED }

/**
 * 月历网格纯计算工具：固定周一开头、6 行 × 7 列 = 42 格，
 * 首行用上月末尾补位、末行用下月开头补位，保证任何月份布局稳定。
 */
object MonthGrid {

    private const val ROWS = 6
    private const val COLS = 7

    /** 某月完整的 42 格（周一开头）。 */
    fun cells(month: YearMonth, today: LocalDate): List<DayCell> {
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val leading = (monthStart.dayOfWeek.value - 1) % COLS // 周一起点：周一=0
        val gridStart = monthStart.minusDays(leading.toLong())
        return (0 until ROWS * COLS).map { i ->
            val date = gridStart.plusDays(i.toLong())
            DayCell(
                date = date,
                inMonth = !date.isBefore(monthStart) && !date.isAfter(monthEnd),
                isToday = date == today
            )
        }
    }

    /** 同一天多条记录聚合成展示状态：已完成 > 已改期 > 已触发。 */
    fun statusOf(records: List<TriggerRecord>): DayStatus {
        if (records.isEmpty()) return DayStatus.NONE
        var best = DayStatus.TRIGGERED
        for (record in records) {
            best = when (record.status) {
                TriggerStatus.COMPLETED -> DayStatus.COMPLETED
                TriggerStatus.SNOOZED -> if (best != DayStatus.COMPLETED) DayStatus.SNOOZED else best
                TriggerStatus.TRIGGERED -> best
            }
        }
        return best
    }

    /** 按日期（设备时区）分组记录：LocalDate → 该天全部记录（升序）。 */
    fun groupByDate(
        records: List<TriggerRecord>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Map<LocalDate, List<TriggerRecord>> = records.groupBy { record ->
        java.time.Instant.ofEpochMilli(record.triggerAt).atZone(zoneId).toLocalDate()
    }

    /** 按日期（设备时区）分组系统日历日程：LocalDate → 该天全部日程（升序）。 */
    fun groupEventsByDate(
        events: List<CalendarEvent>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Map<LocalDate, List<CalendarEvent>> = events.groupBy { event ->
        java.time.Instant.ofEpochMilli(event.startAt).atZone(zoneId).toLocalDate()
    }
}
