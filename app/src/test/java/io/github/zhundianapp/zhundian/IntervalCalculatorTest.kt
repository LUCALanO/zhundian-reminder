package io.github.zhundianapp.zhundian

import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.util.IntervalCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class IntervalCalculatorTest {

    @Test
    fun unitMillis_minutes_hours_days() {
        assertEquals(60_000L, IntervalCalculator.unitMillis(IntervalUnit.MINUTES))
        assertEquals(3_600_000L, IntervalCalculator.unitMillis(IntervalUnit.HOURS))
        assertEquals(86_400_000L, IntervalCalculator.unitMillis(IntervalUnit.DAYS))
    }

    @Test
    fun intervalMillis_byValueAndUnit() {
        assertEquals(2 * 86_400_000L, IntervalCalculator.intervalMillis(2, IntervalUnit.DAYS))
        assertEquals(3 * 3_600_000L, IntervalCalculator.intervalMillis(3, IntervalUnit.HOURS))
        assertEquals(30 * 60_000L, IntervalCalculator.intervalMillis(30, IntervalUnit.MINUTES))
    }

    @Test
    fun intervalMillis_fromReminder() {
        val reminder = Reminder(
            name = "t",
            intervalValue = 5,
            intervalUnit = IntervalUnit.DAYS,
            nextTriggerAt = 0
        )
        assertEquals(5 * 86_400_000L, IntervalCalculator.intervalMillis(reminder))
    }

    @Test
    fun intervalMillis_largeDayValue_noOverflow() {
        // Long 运算保证大间隔不溢出
        assertEquals(365 * 86_400_000L, IntervalCalculator.intervalMillis(365, IntervalUnit.DAYS))
    }

    @Test
    fun nextTriggerAt_byValueAndUnit() {
        val now = 1_000_000L
        assertEquals(now + 2 * 3_600_000L, IntervalCalculator.nextTriggerAt(2, IntervalUnit.HOURS, now))
    }

    @Test
    fun nextTriggerAt_fromReminder() {
        val reminder = Reminder(
            name = "t",
            intervalValue = 1,
            intervalUnit = IntervalUnit.DAYS,
            nextTriggerAt = 0
        )
        val now = 100L
        assertEquals(now + 86_400_000L, IntervalCalculator.nextTriggerAt(reminder, now))
    }

    // ---------- 锚定调度 ----------

    @Test
    fun nextScheduledAfter_stepsByInterval() {
        val anchor = 1_000_000L
        val interval = 2 * IntervalCalculator.DAY_MILLIS
        // 恰在锚点 → 下一跳
        assertEquals(anchor + interval, IntervalCalculator.nextScheduledAfter(anchor, interval, anchor))
        // 紧贴锚点之后
        assertEquals(anchor + interval, IntervalCalculator.nextScheduledAfter(anchor, interval, anchor + 1))
        // 恰好一个间隔 → 再下一跳
        assertEquals(
            anchor + 2 * interval,
            IntervalCalculator.nextScheduledAfter(anchor, interval, anchor + interval)
        )
        // 一个间隔后 +1ms
        assertEquals(
            anchor + 2 * interval,
            IntervalCalculator.nextScheduledAfter(anchor, interval, anchor + interval + 1)
        )
    }

    @Test
    fun nextScheduledAfter_afterInPast_returnsAnchor() {
        val anchor = 1_000_000L
        val interval = 2 * IntervalCalculator.DAY_MILLIS
        assertEquals(anchor, IntervalCalculator.nextScheduledAfter(anchor, interval, anchor - 1))
        assertEquals(anchor, IntervalCalculator.nextScheduledAfter(anchor, interval, anchor - interval))
        // 锚点远早于 after → 正常取序列
        assertEquals(
            anchor + 3 * interval,
            IntervalCalculator.nextScheduledAfter(anchor, interval, anchor + 2 * interval + 1)
        )
    }

    @Test
    fun anchorAtToday_null_returnsNull() {
        assertNull(IntervalCalculator.anchorAtToday(null))
    }

    @Test
    fun anchorAtToday_returnsTodayAtTime() {
        val minute = 6 * 60 + 30 // 06:30
        val anchor = IntervalCalculator.anchorAtToday(minute)
        assertTrue(anchor != null)
        val cal = Calendar.getInstance()
        cal.timeInMillis = anchor!!
        assertEquals(6, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
        // 应是今天
        val today = Calendar.getInstance()
        assertEquals(today.get(Calendar.YEAR), cal.get(Calendar.YEAR))
        assertEquals(today.get(Calendar.DAY_OF_YEAR), cal.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun nextTriggerAt_anchored_dayInterval_alignsToSchedule() {
        val anchor = IntervalCalculator.anchorAtToday(6 * 60)!! // 今天 06:00
        val reminder = Reminder(
            name = "t",
            intervalValue = 2,
            intervalUnit = IntervalUnit.DAYS,
            scheduleAnchorAt = anchor,
            nextTriggerAt = 0
        )
        val reference = anchor + 14 * IntervalCalculator.HOUR_MILLIS // 当天 20:00
        val next = IntervalCalculator.nextTriggerAt(reminder, reference)
        // 下次应为隔两个整天后的 06:00（跳过次日）
        assertEquals(anchor + 2 * IntervalCalculator.DAY_MILLIS, next)
        assertTrue("下一次应在参考时刻之后", next > reference)
    }

    @Test
    fun nextTriggerAt_unanchored_legacyReferencePlusInterval() {
        val reminder = Reminder(
            name = "t",
            intervalValue = 2,
            intervalUnit = IntervalUnit.DAYS,
            scheduleAnchorAt = null,
            nextTriggerAt = 0
        )
        val reference = 1000L
        assertEquals(
            reference + 2 * IntervalCalculator.DAY_MILLIS,
            IntervalCalculator.nextTriggerAt(reminder, reference)
        )
    }

    @Test
    fun nextTriggerAt_anchored_nonDayInterval_ignoresAnchor() {
        // 有锚定但单位不是「天」→ 走旧行为
        val reminder = Reminder(
            name = "t",
            intervalValue = 2,
            intervalUnit = IntervalUnit.HOURS,
            scheduleAnchorAt = IntervalCalculator.anchorAtToday(6 * 60),
            nextTriggerAt = 0
        )
        val reference = 1000L
        assertEquals(
            reference + 2 * IntervalCalculator.HOUR_MILLIS,
            IntervalCalculator.nextTriggerAt(reminder, reference)
        )
    }

    /** 用户场景：8-13 20:00 创建「2 天 @ 06:00」→ 下次应为 8-15 06:00（跳过 8-14）。 */
    @Test
    fun nextScheduledAfter_userScenario_create2dAt6am() {
        val anchor = localMillis(2026, Calendar.AUGUST, 13, 6, 0)
        val now = localMillis(2026, Calendar.AUGUST, 13, 20, 0)
        val expected = localMillis(2026, Calendar.AUGUST, 15, 6, 0)
        assertEquals(
            expected,
            IntervalCalculator.nextScheduledAfter(anchor, 2 * IntervalCalculator.DAY_MILLIS, now)
        )
    }

    /** 用户场景：15 号 06:00 触发后被「再隔 1 小时」到 07:00，再触发 → 下次仍回到 17 号 06:00。 */
    @Test
    fun nextScheduledAfter_userScenario_snoozeKeepsSchedule() {
        val anchor = localMillis(2026, Calendar.AUGUST, 13, 6, 0)
        val snoozeFire = localMillis(2026, Calendar.AUGUST, 15, 7, 0)
        val expected = localMillis(2026, Calendar.AUGUST, 17, 6, 0)
        assertEquals(
            expected,
            IntervalCalculator.nextScheduledAfter(anchor, 2 * IntervalCalculator.DAY_MILLIS, snoozeFire)
        )
    }

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        return cal.timeInMillis
    }
}
