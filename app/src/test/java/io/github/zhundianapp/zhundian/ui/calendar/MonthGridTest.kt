package io.github.zhundianapp.zhundian.ui.calendar

import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.TriggerRecord
import io.github.zhundianapp.zhundian.data.TriggerStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthGridTest {

    private val today = LocalDate.of(2024, 2, 10)

    @Test
    fun cells_alwaysProduces42Cells() {
        val cells = MonthGrid.cells(YearMonth.of(2024, 2), today)
        assertEquals(42, cells.size)
    }

    @Test
    fun cells_firstDayMonday_hasNoLeadingOffset() {
        // 2024-01-01 是周一，首格即该月 1 日且在本月内
        val cells = MonthGrid.cells(YearMonth.of(2024, 1), today)
        assertEquals(LocalDate.of(2024, 1, 1), cells.first().date)
        assertTrue(cells.first().inMonth)
        assertEquals(java.time.DayOfWeek.MONDAY, cells.first().date.dayOfWeek)
    }

    @Test
    fun cells_firstDayThursday_hasThreeLeadingDays() {
        // 2024-02-01 是周四，周一开头需向前补 3 格（1月29/30/31）
        val cells = MonthGrid.cells(YearMonth.of(2024, 2), today)
        assertEquals(LocalDate.of(2024, 1, 29), cells.first().date)
        assertFalse(cells.first().inMonth)
        // 2 月 1 日落在下标 3，属本月
        val firstOfMonth = cells.first { it.date == LocalDate.of(2024, 2, 1) }
        assertTrue(firstOfMonth.inMonth)
        // 末格为 3 月 10 日（下月补位，闰年 2 月 29 天）
        assertEquals(LocalDate.of(2024, 3, 10), cells.last().date)
        assertFalse(cells.last().inMonth)
    }

    @Test
    fun cells_marksToday() {
        val cells = MonthGrid.cells(YearMonth.of(2024, 2), today)
        assertTrue(cells.first { it.date == today }.isToday)
        assertFalse(cells.first { it.date == today.plusDays(1) }.isToday)
    }

    private fun record(status: TriggerStatus, epochMillis: Long) =
        TriggerRecord(reminderId = 1L, triggerAt = epochMillis, status = status)

    @Test
    fun statusOf_empty_isNone() {
        assertEquals(DayStatus.NONE, MonthGrid.statusOf(emptyList()))
    }

    @Test
    fun statusOf_triggeredOnly_isTriggered() {
        assertEquals(DayStatus.TRIGGERED, MonthGrid.statusOf(listOf(record(TriggerStatus.TRIGGERED, 0L))))
    }

    @Test
    fun statusOf_completedTakesPrecedenceOverTriggered() {
        val records = listOf(
            record(TriggerStatus.TRIGGERED, 0L),
            record(TriggerStatus.COMPLETED, 1L)
        )
        assertEquals(DayStatus.COMPLETED, MonthGrid.statusOf(records))
    }

    @Test
    fun statusOf_snoozedTakesPrecedenceOverTriggered() {
        val records = listOf(
            record(TriggerStatus.TRIGGERED, 0L),
            record(TriggerStatus.SNOOZED, 1L)
        )
        assertEquals(DayStatus.SNOOZED, MonthGrid.statusOf(records))
    }

    @Test
    fun statusOf_completedTakesPrecedenceOverSnoozed() {
        val records = listOf(
            record(TriggerStatus.SNOOZED, 0L),
            record(TriggerStatus.COMPLETED, 1L)
        )
        assertEquals(DayStatus.COMPLETED, MonthGrid.statusOf(records))
    }

    @Test
    fun groupByDate_groupsByDayInGivenZone() {
        val zone = ZoneOffset.UTC
        val records = listOf(
            record(TriggerStatus.COMPLETED, 0L),                      // 1970-01-01
            record(TriggerStatus.TRIGGERED, 86_400_000L),             // 1970-01-02
            record(TriggerStatus.SNOOZED, 86_400_000L + 3_600_000L)   // 1970-01-02
        )
        val grouped = MonthGrid.groupByDate(records, zone)
        assertEquals(2, grouped.size)
        assertEquals(1, grouped[LocalDate.of(1970, 1, 1)]?.size)
        assertEquals(2, grouped[LocalDate.of(1970, 1, 2)]?.size)
        // 组内按原顺序保留
        assertEquals(TriggerStatus.TRIGGERED, grouped[LocalDate.of(1970, 1, 2)]?.first()?.status)
    }

    private fun event(epochMillis: Long) = CalendarEvent(
        title = "日程",
        startAt = epochMillis,
        endAt = epochMillis + 3_600_000L,
        sourceEventId = epochMillis,
        sourceCalendarId = 1L
    )

    @Test
    fun groupEventsByDate_groupsByDayInGivenZone() {
        val zone = ZoneOffset.UTC
        val events = listOf(
            event(0L),                        // 1970-01-01
            event(86_400_000L),               // 1970-01-02
            event(86_400_000L + 3_600_000L)   // 1970-01-02
        )
        val grouped = MonthGrid.groupEventsByDate(events, zone)
        assertEquals(2, grouped.size)
        assertEquals(1, grouped[LocalDate.of(1970, 1, 1)]?.size)
        assertEquals(2, grouped[LocalDate.of(1970, 1, 2)]?.size)
    }

    @Test
    fun groupEventsByDate_empty_isEmpty() {
        assertTrue(MonthGrid.groupEventsByDate(emptyList(), ZoneOffset.UTC).isEmpty())
    }
}
