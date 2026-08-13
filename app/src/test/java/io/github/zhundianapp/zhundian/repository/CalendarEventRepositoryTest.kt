package io.github.zhundianapp.zhundian.repository

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zhundianapp.zhundian.alarm.AlarmScheduler
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.CalendarSourceInfo
import io.github.zhundianapp.zhundian.data.ReminderDatabase
import io.github.zhundianapp.zhundian.data.SettingsRepository
import io.github.zhundianapp.zhundian.notification.NotificationHelper
import io.github.zhundianapp.zhundian.permission.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarEventRepositoryTest {

    private lateinit var db: ReminderDatabase
    private lateinit var repo: CalendarEventRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var context: Context
    private val fakeReader = FakeReader()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 默认不授予危险权限；本测试默认假定已授权 READ_CALENDAR
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_CALENDAR)
        db = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepository = SettingsRepository(context)
        repo = CalendarEventRepository(
            dao = db.calendarEventDao(),
            scheduler = AlarmScheduler(context),
            settingsRepository = settingsRepository,
            permissionManager = PermissionManager(context),
            notificationHelper = NotificationHelper(context, PermissionManager(context)),
            reader = fakeReader
        )
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // ---------- 同步 ----------

    @Test
    fun sync_importsInstances() = runTest {
        val now = System.currentTimeMillis()
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, "会议室", now + 3_600_000L, now + 7_200_000L, false),
            SystemEventInstance(2, 2, "全天假", null, null, now + 86_400_000L, now + 172_800_000L, true)
        )

        val result = repo.sync()

        assertTrue("sync 应成功，实际结果=$result", result is SyncResult.Success)
        assertEquals(2, (result as SyncResult.Success).inserted)
        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals(2, events.size)
        assertEquals("晨会", events.first().title)
        assertTrue("全天事件标记应保留", events.any { it.allDay })
    }

    @Test
    fun sync_withoutPermission_returnsNotPermitted() = runTest {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.READ_CALENDAR)
        val result = repo.sync()
        assertEquals("未授权时应返回 NotPermitted", SyncResult.NotPermitted, result)
    }

    @Test
    fun sync_secondSync_isIdempotent() = runTest {
        val now = System.currentTimeMillis()
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()
        repo.sync()

        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals("重复同步不应重复插入", 1, events.size)
    }

    @Test
    fun sync_refreshesChangedMetadata() = runTest {
        val now = System.currentTimeMillis()
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, "A 室", now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()

        // 系统日历里该日程地点变了
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, "B 室", now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()

        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals(1, events.size)
        assertEquals("元数据应刷新为最新", "B 室", events.single().location)
    }

    @Test
    fun sync_keepsUserTombstone() = runTest {
        val now = System.currentTimeMillis()
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()

        // 用户从 App 删除
        val event = db.calendarEventDao().getUpcoming(0L).single()
        repo.deleteEvent(event)

        // 系统日历仍存在该日程，再同步不应复活它
        repo.sync()

        assertTrue(
            "用户删除的日程不应被同步复活",
            db.calendarEventDao().getUpcoming(0L).isEmpty()
        )
    }

    @Test
    fun sync_removesSystemDeletedEvent() = runTest {
        val now = System.currentTimeMillis()
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()
        assertEquals(1, db.calendarEventDao().getUpcoming(0L).size)

        // 系统日历里该日程被删了
        fakeReader.instances = emptyList()
        val result = repo.sync()

        assertTrue(result is SyncResult.Success)
        assertTrue("系统删除的日程应从本库清理", db.calendarEventDao().getUpcoming(0L).isEmpty())
        assertEquals(1, (result as SyncResult.Success).removed)
    }

    @Test
    fun sync_doesNotResetRemindedState() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now - 3_600_000L // 1 小时前（错过 12h 内，可补发）
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, startAt, startAt + 3_600_000L, false)
        )
        repo.sync()

        // 到点触发成功，置 reminded
        val event = db.calendarEventDao().getUpcoming(startAt).single()
        assertNotNull("错过 12h 内的旧事件可触发", repo.tryTriggerEvent(event.id))
        assertTrue(db.calendarEventDao().getById(event.id)!!.reminded)

        // 再同步（系统仍返回该实例）不应重置 reminded
        repo.sync()
        assertTrue("已提醒状态应在同步后保留", db.calendarEventDao().getById(event.id)!!.reminded)
    }

    @Test
    fun sync_marksOlderEventsAsReminded() = runTest {
        val now = System.currentTimeMillis()
        // 20 小时前，超过错过容差 12h
        val startAt = now - 20L * 3600_000L
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "远古事件", null, null, startAt, startAt + 3_600_000L, false)
        )
        repo.sync()

        val event = db.calendarEventDao().getInWindow(startAt, startAt).single()
        assertTrue("超过容差的旧事件应被静默标记为已提醒", event.reminded)
    }

    // ---------- 按来源过滤（选择性同步） ----------

    @Test
    fun sync_excludesReadOnlyCalendarsByDefault() = runTest {
        val now = System.currentTimeMillis()
        // 日历 2 为只读系统日历（如节日/节气），低于 CONTRIBUTOR(500)
        fakeReader.calendars = listOf(
            CalendarSourceInfo(1, "我的日历", "local", "com.android.localcalendar", 500, true),
            CalendarSourceInfo(2, "中国节假日", "google", "com.google", 200, false)
        )
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, now + 3_600_000L, now + 7_200_000L, false),
            SystemEventInstance(2, 2, "中秋节", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )

        repo.sync()

        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals("只读系统日历的日程不应被导入", 1, events.size)
        assertEquals("晨会", events.single().title)
        assertEquals("默认策略应将只读日历排除在允许集合外", setOf(1L), fakeReader.lastAllowedIds)
    }

    @Test
    fun sync_respectsExplicitAllowlist() = runTest {
        val now = System.currentTimeMillis()
        settingsRepository.update { it.copy(enabledCalendarIds = setOf(2L)) }
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, now + 3_600_000L, now + 7_200_000L, false),
            SystemEventInstance(2, 2, "节日", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )

        repo.sync()

        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals("显式 allowlist 只导入勾选的日历", 1, events.size)
        assertEquals("节日", events.single().title)
        assertEquals(setOf(2L), fakeReader.lastAllowedIds)
    }

    @Test
    fun sync_excludedCalendar_removesExistingEvents() = runTest {
        val now = System.currentTimeMillis()
        // 首次两个日历都勾选，节日日程被导入
        settingsRepository.update { it.copy(enabledCalendarIds = setOf(1L, 2L)) }
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, now + 3_600_000L, now + 7_200_000L, false),
            SystemEventInstance(2, 2, "节日", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()
        assertEquals(2, db.calendarEventDao().getUpcoming(0L).size)

        // 用户把日历 2 取消勾选，再同步 → 已导入的节日日程应被清理
        settingsRepository.update { it.copy(enabledCalendarIds = setOf(1L)) }
        repo.sync()

        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals("排除的日历下已有日程应被清理", 1, events.size)
        assertEquals("晨会", events.single().title)
    }

    // ---------- 触发门控 ----------

    @Test
    fun tryTriggerEvent_whenDue_marksRemindedAndReturns() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now - 1_000L
        val event = insert(startAt, "晨会")

        val fired = repo.tryTriggerEvent(event.id)

        assertNotNull(fired)
        assertEquals("晨会", fired?.title)
        assertTrue("触发后应置 reminded", db.calendarEventDao().getById(event.id)!!.reminded)
    }

    @Test
    fun tryTriggerEvent_whenNotDue_returnsNull() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now + 3_600_000L, "未来事件")

        assertNull("未到点不应触发", repo.tryTriggerEvent(event.id))
    }

    @Test
    fun tryTriggerEvent_whenAlreadyReminded_returnsNull() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now - 1_000L, "已提醒")
        repo.tryTriggerEvent(event.id)

        assertNull("已提醒不应重复触发", repo.tryTriggerEvent(event.id))
    }

    @Test
    fun tryTriggerEvent_whenMissedTooLong_marksRemindedAndReturnsNull() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now - 20L * 3600_000L
        val event = insert(startAt, "远古事件")

        assertNull("错过超限不补发", repo.tryTriggerEvent(event.id))
        assertTrue("错过超限应静默标记", db.calendarEventDao().getById(event.id)!!.reminded)
    }

    @Test
    fun tryTriggerEvent_whenDeleted_returnsNull() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now - 1_000L, "已删除")
        repo.deleteEvent(event)

        assertNull("墓碑日程不应触发", repo.tryTriggerEvent(event.id))
    }

    // ---------- 轮询用 ----------

    @Test
    fun eventsDueNow_returnsDueEventsOnly() = runTest {
        val now = System.currentTimeMillis()
        insert(now - 3_600_000L, "1 小时前（可补发）")
        insert(now + 3_600_000L, "未来")

        val due = repo.eventsDueNow()

        assertEquals("只应触发到期的日程", 1, due.size)
        assertEquals("1 小时前（可补发）", due.single().title)
    }

    @Test
    fun nextEventTriggerAt_returnsEarliestFuture() = runTest {
        val now = System.currentTimeMillis()
        insert(now + 3_600_000L, "a")
        insert(now + 86_400_000L, "b")
        insert(now - 3_600_000L, "过期")

        val next = repo.nextEventTriggerAt()

        assertNotNull(next)
        assertEquals("最近触发应为最早未来事件（提前量 0）", now + 3_600_000L, next)
    }

    @Test
    fun nextEventTriggerAt_respectsLeadMinutes() = runTest {
        val now = System.currentTimeMillis()
        settingsRepository.update { it.copy(leadMinutes = 10) }
        insert(now + 3_600_000L, "a")

        val next = repo.nextEventTriggerAt()

        assertNotNull(next)
        assertEquals("提前 10 分钟触发", now + 3_600_000L - 600_000L, next)
    }

    @Test
    fun eventsDueNow_respectsLeadMinutes() = runTest {
        val now = System.currentTimeMillis()
        settingsRepository.update { it.copy(leadMinutes = 10) }
        // 事件开始 5 分钟后，但提前 10 分钟提醒 → 已过触发点，应补发
        insert(now + 300_000L, "已过触发点")

        val due = repo.eventsDueNow()

        assertEquals(1, due.size)
    }

    // ---------- 删除 ----------

    @Test
    fun deleteEvent_marksDeletedOnly() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now + 3_600_000L, "晨会")

        repo.deleteEvent(event)

        val loaded = db.calendarEventDao().getById(event.id)!!
        assertTrue("删除应置墓碑而非物理删除", loaded.deleted)
        assertTrue("删除后不再出现在未删列表中", db.calendarEventDao().getUpcoming(0L).isEmpty())
    }

    // ---------- 恢复 / 永久删除 ----------

    @Test
    fun restoreEvent_afterDelete_restoresTombstone() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now + 3_600_000L, "晨会")

        repo.deleteEvent(event)
        repo.restoreEvent(event)

        val restored = db.calendarEventDao().getById(event.id)!!
        assertFalse("恢复后应清墓碑", restored.deleted)
        assertTrue("恢复后的未来日程应回到未删列表", db.calendarEventDao().getUpcoming(0L).contains(restored))
    }

    @Test
    fun restoreEvent_whenAlreadyReminded_keepsReminded() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now - 3_600_000L // 1 小时前（错过 12h 内，可补发）
        val event = insert(startAt, "已提醒")

        assertNotNull("错过 12h 内的旧事件可触发", repo.tryTriggerEvent(event.id))
        val reminded = db.calendarEventDao().getById(event.id)!!
        repo.deleteEvent(reminded)
        repo.restoreEvent(reminded)

        val restored = db.calendarEventDao().getById(event.id)!!
        assertFalse("恢复后应清墓碑", restored.deleted)
        assertTrue("已提醒状态应在恢复后保留，避免二次触发", restored.reminded)
    }

    @Test
    fun restoreEvent_whenPermanentlyDeleted_isNoOp() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now + 3_600_000L, "晨会")

        repo.deleteEvent(event)
        repo.deleteEventPermanently(event)
        repo.restoreEvent(event) // 永久删除的不可恢复，应静默 no-op

        val current = db.calendarEventDao().getById(event.id)!!
        assertTrue("永久删除后应保持墓碑", current.deleted)
        assertTrue("永久删除后应保持永久标记", current.permanentlyDeleted)
        assertTrue("永久删除的不应出现在已删除列表", repo.observeDeletedEvents().first().isEmpty())
    }

    @Test
    fun restoreEvent_whenAlreadyRestored_isNoOp() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now + 3_600_000L, "晨会")

        repo.deleteEvent(event)
        repo.restoreEvent(event)
        repo.restoreEvent(event)

        val restored = db.calendarEventDao().getById(event.id)!!
        assertFalse("重复恢复应幂等保持已恢复", restored.deleted)
        assertEquals("应只有一行", 1, db.calendarEventDao().getUpcoming(0L).size)
    }

    @Test
    fun deleteEventPermanently_keepsTombstone_hidesFromTrash() = runTest {
        val now = System.currentTimeMillis()
        val event = insert(now + 3_600_000L, "晨会")

        repo.deleteEvent(event)
        repo.deleteEventPermanently(event)

        val current = db.calendarEventDao().getById(event.id)
        assertNotNull("永久删除应保留墓碑行（防同步复活）", current)
        assertTrue("应置墓碑", current!!.deleted)
        assertTrue("应置永久标记", current.permanentlyDeleted)
        assertTrue("不应出现在未删列表", db.calendarEventDao().getUpcoming(0L).isEmpty())
        assertTrue("不应出现在已删除列表", repo.observeDeletedEvents().first().isEmpty())
    }

    @Test
    fun deleteEventPermanently_syncDoesNotResurrect() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now + 3_600_000L
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, startAt, startAt + 3_600_000L, false)
        )
        repo.sync()

        val event = db.calendarEventDao().getUpcoming(0L).single()
        repo.deleteEventPermanently(event)

        // 系统日历仍返回该实例，再同步不应复活永久删除的日程
        repo.sync()

        assertTrue("永久删除的不应回到未删列表", db.calendarEventDao().getUpcoming(0L).isEmpty())
        assertTrue("永久删除的不应出现在已删除列表", repo.observeDeletedEvents().first().isEmpty())
        val current = db.calendarEventDao().getById(event.id)!!
        assertTrue("应保持墓碑", current.deleted)
        assertTrue("应保持永久标记", current.permanentlyDeleted)
    }

    @Test
    fun observeDeletedEvents_onlyReturnsTombstones() = runTest {
        val now = System.currentTimeMillis()
        insert(now + 3_600_000L, "保留")
        val removed = insert(now + 7_200_000L, "被删")

        repo.deleteEvent(removed)

        val deleted = repo.observeDeletedEvents().first()
        assertEquals("只应返回被删除的那条", listOf(removed.id), deleted.map { it.id })
    }

    @Test
    fun sync_afterRestore_keepsRestoredEvent() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now + 3_600_000L
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "晨会", null, null, startAt, startAt + 3_600_000L, false)
        )
        repo.sync()

        val event = db.calendarEventDao().getUpcoming(0L).single()
        repo.deleteEvent(event)
        repo.restoreEvent(event)

        // 系统日历仍返回该实例，再同步不应影响已恢复的日程
        repo.sync()

        val events = db.calendarEventDao().getUpcoming(0L)
        assertEquals("恢复后的日程应保留", 1, events.size)
        assertFalse("应保持非墓碑", events.single().deleted)
    }

    // ---------- App 自建日程 ----------

    @Test
    fun createLocalEvent_savesLocalRow() = runTest {
        val now = System.currentTimeMillis()
        val startAt = now + 3_600_000L

        val saved = repo.createLocalEvent(
            title = "复查", description = null, location = "医院",
            startAt = startAt, endAt = startAt + 3_600_000L,
            allDay = false, remind = true
        )

        assertTrue("应标记为 App 自建", saved.isLocal)
        assertTrue("sourceEventId 应为负数合成 id", saved.sourceEventId < 0)
        assertEquals(
            "sourceCalendarId 应为本地哨兵",
            CalendarEvent.SOURCE_CALENDAR_ID_LOCAL, saved.sourceCalendarId
        )
        assertTrue("到点提醒的本地日程应出现在未提醒列表中", db.calendarEventDao().getUpcoming(0L).contains(saved))
    }

    @Test
    fun sync_doesNotRemoveLocalEvent() = runTest {
        val now = System.currentTimeMillis()
        val saved = repo.createLocalEvent(
            title = "本地日程", description = null, location = null,
            startAt = now + 3_600_000L, endAt = now + 7_200_000L,
            allDay = false, remind = false
        )

        repo.sync() // 系统无任何日程

        assertNotNull("同步不应清理 App 自建日程", db.calendarEventDao().getById(saved.id))
    }

    @Test
    fun sync_doesNotPruneLocalEventOutsideWindow() = runTest {
        val now = System.currentTimeMillis()
        // 超出同步窗口（未来 13 个月）
        val farFuture = now + 13L * 30 * 24 * 3600_000L
        val saved = repo.createLocalEvent(
            title = "远期日程", description = null, location = null,
            startAt = farFuture, endAt = farFuture + 3_600_000L,
            allDay = false, remind = false
        )

        repo.sync()

        assertNotNull("超出同步窗口的 App 自建日程不应被裁剪", db.calendarEventDao().getById(saved.id))
    }

    @Test
    fun deleteLocalEvent_marksTombstone() = runTest {
        val now = System.currentTimeMillis()
        val saved = repo.createLocalEvent(
            title = "要删的", description = null, location = null,
            startAt = now + 3_600_000L, endAt = now + 7_200_000L,
            allDay = false, remind = false
        )

        repo.deleteEvent(saved)

        assertTrue("本地应置墓碑", db.calendarEventDao().getById(saved.id)!!.deleted)
    }

    @Test
    fun restoreLocalEvent_clearsTombstone() = runTest {
        val now = System.currentTimeMillis()
        val saved = repo.createLocalEvent(
            title = "恢复的", description = null, location = null,
            startAt = now + 3_600_000L, endAt = now + 7_200_000L,
            allDay = false, remind = false
        )
        repo.deleteEvent(saved)

        repo.restoreEvent(saved)

        val restored = db.calendarEventDao().getById(saved.id)!!
        assertFalse("应清墓碑", restored.deleted)
    }

    @Test
    fun deleteLocalEventPermanently_physicallyDeletesLocal() = runTest {
        val now = System.currentTimeMillis()
        val saved = repo.createLocalEvent(
            title = "永久删", description = null, location = null,
            startAt = now + 3_600_000L, endAt = now + 7_200_000L,
            allDay = false, remind = false
        )

        repo.deleteEventPermanently(saved)

        assertNull("本地行应物理删除", db.calendarEventDao().getById(saved.id))
    }

    @Test
    fun deleteImportedEvent_doesNotTouchSystem() = runTest {
        val now = System.currentTimeMillis()
        fakeReader.instances = listOf(
            SystemEventInstance(1, 1, "导入的", null, null, now + 3_600_000L, now + 7_200_000L, false)
        )
        repo.sync()

        val event = db.calendarEventDao().getUpcoming(0L).single()
        repo.deleteEvent(event)

        assertTrue("本地应置墓碑", db.calendarEventDao().getById(event.id)!!.deleted)
    }

    /** 直接插入一条本地日程（绕过 sync），返回生成的实体。 */
    private suspend fun insert(startAt: Long, title: String): CalendarEvent {
        val event = CalendarEvent(
            title = title,
            startAt = startAt,
            endAt = startAt + 3_600_000L,
            sourceEventId = (startAt % 100_000).toInt().toLong(),
            sourceCalendarId = 1
        )
        val id = db.calendarEventDao().insertIgnore(event)
        return db.calendarEventDao().getById(id)!!
    }
}

/** 可注入的系统日历 fake：返回可控实例/日历来源列表，并模拟按来源过滤。 */
private class FakeReader : SystemCalendarReader {

    /** 日历来源列表；默认两个可编辑日历（id 1、2），与现有用例的 sourceCalendarId 匹配。 */
    var calendars: List<CalendarSourceInfo> = listOf(
        CalendarSourceInfo(1, "我的日历", "local", "com.android.localcalendar", 500, true),
        CalendarSourceInfo(2, "第二个日历", "local", "com.android.localcalendar", 500, false)
    )

    var instances: List<SystemEventInstance> = emptyList()

    /** 记录最后一次 query 收到的 allowedCalendarIds，用于断言仓库的过滤计算。 */
    var lastAllowedIds: Set<Long>? = null

    override suspend fun query(
        begin: Long,
        end: Long,
        allowedCalendarIds: Set<Long>?
    ): List<SystemEventInstance> {
        lastAllowedIds = allowedCalendarIds
        return when {
            allowedCalendarIds == null -> instances
            allowedCalendarIds.isEmpty() -> emptyList()
            else -> instances.filter { it.sourceCalendarId in allowedCalendarIds }
        }
    }

    override suspend fun queryCalendars(): List<CalendarSourceInfo> = calendars
}
