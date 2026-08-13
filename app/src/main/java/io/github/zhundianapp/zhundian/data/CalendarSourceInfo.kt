package io.github.zhundianapp.zhundian.data

/**
 * 系统日历来源（CalendarContract.Calendars 表的一行），用于「按来源选择性同步」。
 *
 * 默认策略只同步可编辑日历：系统自带的节日、节气、农历、天气等通常以只读订阅
 * 日历形式存在（[isReadOnly]），默认排除，避免它们被导入并触发提醒。
 */
data class CalendarSourceInfo(
    /** Calendars._ID。 */
    val id: Long,
    /** 日历显示名（如「中国节假日」）。 */
    val displayName: String,
    /** 所属账号名（如 google 邮箱 / 本地账号）。 */
    val accountName: String,
    /** 账号类型（如 com.google、com.android.localcalendar）。 */
    val accountType: String,
    /** 日历访问级别（CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL）。 */
    val accessLevel: Int,
    /** 是否主日历。 */
    val isPrimary: Boolean
) {
    /**
     * 是否只读：访问级别低于 CAL_ACCESS_CONTRIBUTOR(500) 时用户不可编辑，
     * 通常是系统自带/订阅的只读日历（节日、节气、农历、天气等）。
     */
    val isReadOnly: Boolean get() = accessLevel < CAL_ACCESS_CONTRIBUTOR

    companion object {
        /** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR（可编辑的最低级别）。 */
        const val CAL_ACCESS_CONTRIBUTOR = 500
    }
}
