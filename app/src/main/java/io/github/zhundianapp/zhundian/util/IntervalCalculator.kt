package io.github.zhundianapp.zhundian.util

import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.data.Reminder
import java.util.Calendar

/**
 * 间隔换算工具。所有毫秒换算集中于此，供调度与仓库复用。
 */
object IntervalCalculator {

    const val MINUTE_MILLIS: Long = 60_000L
    const val HOUR_MILLIS: Long = 3_600_000L
    const val DAY_MILLIS: Long = 86_400_000L

    /** 通知栏「再隔 1 小时提醒」的顺延时长为 1 小时。 */
    const val SNOOZE_MILLIS: Long = HOUR_MILLIS

    fun unitMillis(unit: IntervalUnit): Long = when (unit) {
        IntervalUnit.MINUTES -> MINUTE_MILLIS
        IntervalUnit.HOURS -> HOUR_MILLIS
        IntervalUnit.DAYS -> DAY_MILLIS
    }

    /** 由「数值 + 单位」计算间隔毫秒数（用 Long 避免天级溢出）。 */
    fun intervalMillis(value: Int, unit: IntervalUnit): Long =
        value.toLong() * unitMillis(unit)

    fun intervalMillis(reminder: Reminder): Long =
        intervalMillis(reminder.intervalValue, reminder.intervalUnit)

    /** 未锚定的下次触发（旧行为）：now + 间隔。 */
    fun nextTriggerAt(value: Int, unit: IntervalUnit, now: Long = System.currentTimeMillis()): Long =
        now + intervalMillis(value, unit)

    /**
     * 锚定参考点：由「触发时刻（分钟，0~1439）」生成「今天该时刻」的 epoch millis。
     * 触发序列以创建/编辑当天的该时刻为锚，之后固定在 anchorAt + k×间隔 上。
     * null 原样返回 null（不锚定）。
     */
    fun anchorAtToday(triggerTimeMinutes: Int?): Long? {
        if (triggerTimeMinutes == null) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, triggerTimeMinutes / 60)
        cal.set(Calendar.MINUTE, triggerTimeMinutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 锚定调度：返回 anchorAt + k×intervalMillis 中**最小的大于 after** 的时刻。
     * 时间戳直接用 epoch 毫秒做整数运算（floorDiv 正确处理负差），不拆解年月日，
     * 天然避免时区/跨日问题；after 传「参考时刻」（当前时间 / 刚触发的计划时刻）。
     */
    fun nextScheduledAfter(anchorAt: Long, intervalMillis: Long, after: Long): Long {
        val k = Math.floorDiv(after - anchorAt, intervalMillis) + 1
        return anchorAt + k * intervalMillis
    }

    /**
     * 锚定感知的下次触发：
     * - 锚定（天间隔 + 指定触发时刻）→ 返回锚定序列中最小的大于 reference 的时刻；
     * - 未锚定 → reference + 间隔。
     * 调用方按场景传 reference：创建/编辑/启用传当前时间；到点推进传刚触发的 nextTriggerAt。
     */
    fun nextTriggerAt(
        anchorAt: Long?,
        intervalValue: Int,
        intervalUnit: IntervalUnit,
        reference: Long
    ): Long {
        return if (anchorAt != null && intervalUnit == IntervalUnit.DAYS) {
            nextScheduledAfter(anchorAt, intervalMillis(intervalValue, intervalUnit), reference)
        } else {
            reference + intervalMillis(intervalValue, intervalUnit)
        }
    }

    /** 以 now 为基准（创建 / 编辑 / 启用）计算下次触发；锚定时自动读取 scheduleAnchorAt。 */
    fun nextTriggerAt(reminder: Reminder, now: Long = System.currentTimeMillis()): Long =
        nextTriggerAt(reminder.scheduleAnchorAt, reminder.intervalValue, reminder.intervalUnit, now)
}
