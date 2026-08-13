package io.github.zhundianapp.zhundian.repository

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.CalendarSourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 系统日历中的一个日程实例（重复日程按发生展开后的一次发生）。 */
data class SystemEventInstance(
    val sourceEventId: Long,
    val sourceCalendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean
) {
    /** 转为可入库的实体（提醒/墓碑状态走默认值，由同步流程维护）。 */
    fun toEvent() = CalendarEvent(
        title = title,
        description = description,
        location = location,
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        sourceEventId = sourceEventId,
        sourceCalendarId = sourceCalendarId
    )
}

/**
 * 系统日历读取抽象。独立成接口以便单测注入 fake（Robolectric 的 ShadowContentResolver
 * 难以构造 Instances 数据）。
 */
interface SystemCalendarReader {
    /**
     * 查询 [begin, end) 范围内的日程实例（含重复日程展开）。
     *
     * [allowedCalendarIds] 为 null 表示不按来源过滤（全部日历）；空集表示不同步任何日历；
     * 非空集表示仅同步这些日历 id 的实例。无 READ_CALENDAR 权限时抛 [SecurityException]。
     */
    suspend fun query(
        begin: Long,
        end: Long,
        allowedCalendarIds: Set<Long>?
    ): List<SystemEventInstance>

    /** 查询系统日历来源列表（Calendars 表），用于「按来源选择性同步」配置。无权限时抛 [SecurityException]。 */
    suspend fun queryCalendars(): List<CalendarSourceInfo>
}

/**
 * 基于 CalendarContract.Instances 的 Android 实现。
 *
 * - 时间范围拼在 URI 路径（`Instances.CONTENT_URI/<begin>/<end>`，epoch millis，UTC），
 *   不是 selection 条件；
 * - 重复日程（RRULE）由 Instances 自动展开为每次发生，正好对应「一次发生 = 一个闹钟」；
 * - 查询是阻塞调用，放 IO 线程。
 */
class AndroidSystemCalendarReader(private val context: Context) : SystemCalendarReader {

    override suspend fun query(
        begin: Long,
        end: Long,
        allowedCalendarIds: Set<Long>?
    ): List<SystemEventInstance> = withContext(Dispatchers.IO) {
        if (allowedCalendarIds != null && allowedCalendarIds.isEmpty()) {
            return@withContext emptyList()
        }
        val uri = ContentUris.withAppendedId(
            ContentUris.withAppendedId(CalendarContract.Instances.CONTENT_URI, begin),
            end
        )
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        // Instances 视图已排除已删除日程，仅需可见性过滤 + 可选的来源过滤
        val selection = StringBuilder("${CalendarContract.Instances.VISIBLE} != 0")
        val selectionArgs = ArrayList<String>()
        if (allowedCalendarIds != null) {
            selection.append(" AND ${CalendarContract.Instances.CALENDAR_ID} IN (")
                .append(List(allowedCalendarIds.size) { "?" }.joinToString(","))
                .append(")")
            allowedCalendarIds.forEach { selectionArgs.add(it.toString()) }
        }
        val cursor = context.contentResolver.query(
            uri, projection, selection.toString(), selectionArgs.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return@withContext emptyList()

        cursor.use {
            val result = ArrayList<SystemEventInstance>(it.count)
            val colEventId = it.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val colCalendarId = it.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
            val colTitle = it.getColumnIndex(CalendarContract.Instances.TITLE)
            val colDescription = it.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
            val colLocation = it.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val colBegin = it.getColumnIndex(CalendarContract.Instances.BEGIN)
            val colEnd = it.getColumnIndex(CalendarContract.Instances.END)
            val colAllDay = it.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            while (it.moveToNext()) {
                result.add(
                    SystemEventInstance(
                        sourceEventId = it.getLong(colEventId),
                        sourceCalendarId = it.getLong(colCalendarId),
                        title = it.getString(colTitle) ?: "",
                        description = it.getString(colDescription),
                        location = it.getString(colLocation),
                        startAt = it.getLong(colBegin),
                        endAt = it.getLong(colEnd),
                        allDay = it.getInt(colAllDay) != 0
                    )
                )
            }
            result
        }
    }

    override suspend fun queryCalendars(): List<CalendarSourceInfo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        ) ?: return@withContext emptyList()

        cursor.use {
            val result = ArrayList<CalendarSourceInfo>(it.count)
            val colId = it.getColumnIndex(CalendarContract.Calendars._ID)
            val colName = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val colAccount = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val colAccountType = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
            val colAccess = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val colPrimary = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            while (it.moveToNext()) {
                result.add(
                    CalendarSourceInfo(
                        id = it.getLong(colId),
                        displayName = it.getString(colName) ?: "",
                        accountName = it.getString(colAccount) ?: "",
                        accountType = it.getString(colAccountType) ?: "",
                        accessLevel = it.getInt(colAccess),
                        isPrimary = it.getInt(colPrimary) != 0
                    )
                )
            }
            result
        }
    }
}
