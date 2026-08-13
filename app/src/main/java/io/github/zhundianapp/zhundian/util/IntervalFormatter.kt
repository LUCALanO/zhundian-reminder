package io.github.zhundianapp.zhundian.util

import android.content.Context
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.IntervalUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 提醒的间隔与触发时间的展示格式化。 */
object IntervalFormatter {

    /** 格式化间隔，如「2 天」「3 小时」。 */
    fun format(context: Context, value: Int, unit: IntervalUnit): String {
        val unitLabel = when (unit) {
            IntervalUnit.MINUTES -> context.getString(R.string.interval_unit_minutes)
            IntervalUnit.HOURS -> context.getString(R.string.interval_unit_hours)
            IntervalUnit.DAYS -> context.getString(R.string.interval_unit_days)
        }
        return context.getString(R.string.interval_format, value, unitLabel)
    }

    /** 格式化下次触发时间，跟随系统 24/12 小时制。 */
    fun formatTriggerTime(context: Context, epochMillis: Long): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) {
            "yyyy-MM-dd HH:mm"
        } else {
            "yyyy-MM-dd hh:mm a"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
    }
}
