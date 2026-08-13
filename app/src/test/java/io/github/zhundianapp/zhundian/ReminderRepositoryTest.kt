package io.github.zhundianapp.zhundian

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zhundianapp.zhundian.alarm.AlarmScheduler
import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.data.ReminderDatabase
import io.github.zhundianapp.zhundian.data.TriggerStatus
import io.github.zhundianapp.zhundian.notification.NotificationHelper
import io.github.zhundianapp.zhundian.permission.PermissionManager
import io.github.zhundianapp.zhundian.repository.ReminderRepository
import io.github.zhundianapp.zhundian.util.IntervalCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import java.util.Calendar
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderRepositoryTest {

    private lateinit var db: ReminderDatabase
    private lateinit var repo: ReminderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ReminderRepository(
            db.reminderDao(),
            db.triggerRecordDao(),
            AlarmScheduler(context),
            NotificationHelper(context, PermissionManager(context))
        )
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun create_setsNextTriggerAtAndEnabled() = runTest {
        val reminder = repo.create("吃药", 2, IntervalUnit.DAYS, true)
        assertTrue(reminder.id > 0)
        assertTrue(reminder.isEnabled)
        val expected = reminder.createdAt + IntervalCalculator.intervalMillis(2, IntervalUnit.DAYS)
        assertEquals(expected, reminder.nextTriggerAt)
    }

    @Test
    fun create_thenGetById_roundTrip() = runTest {
        val reminder = repo.create("喝水", 1, IntervalUnit.HOURS, false)
        val loaded = repo.getById(reminder.id)
        assertEquals("喝水", loaded?.name)
        assertEquals(1, loaded?.intervalValue)
        assertEquals(IntervalUnit.HOURS, loaded?.intervalUnit)
        assertEquals(false, loaded?.vibrationEnabled)
    }

    @Test
    fun onTriggered_advancesNextTriggerAt() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        val before = reminder.nextTriggerAt
        repo.onTriggered(reminder.id)
        val updated = repo.getById(reminder.id)!!
        assertTrue("下次触发应晚于之前", updated.nextTriggerAt > before)
        val drift = updated.nextTriggerAt - System.currentTimeMillis() - IntervalCalculator.HOUR_MILLIS
        assertTrue("推进应约等于一个间隔（允许少量漂移）: drift=$drift", abs(drift) < 2000)
    }

    @Test
    fun setEnabled_false_disablesReminder() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        repo.setEnabled(reminder, false)
        val updated = repo.getById(reminder.id)!!
        assertFalse(updated.isEnabled)
    }

    @Test
    fun setEnabled_true_resetsTriggerToNextInterval() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        repo.setEnabled(reminder, false)
        repo.setEnabled(reminder, true)
        val updated = repo.getById(reminder.id)!!
        assertTrue(updated.isEnabled)
        val diff = updated.nextTriggerAt - System.currentTimeMillis()
        assertTrue("重新启用后应为未来时间: diff=$diff", diff > 0)
        assertTrue("重新启用后应约等于一个间隔", diff <= IntervalCalculator.HOUR_MILLIS + 2000)
    }

    @Test
    fun update_resetsNextTrigger() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        repo.update(reminder.copy(name = "新名字", intervalValue = 3, intervalUnit = IntervalUnit.DAYS))
        val loaded = repo.getById(reminder.id)!!
        assertEquals("新名字", loaded.name)
        val diff = loaded.nextTriggerAt - System.currentTimeMillis()
        assertTrue(diff > 0)
        assertTrue(diff <= IntervalCalculator.intervalMillis(3, IntervalUnit.DAYS) + 2000)
    }

    @Test
    fun delete_removesReminder() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        repo.delete(reminder)
        assertNull(repo.getById(reminder.id))
    }

    @Test
    fun delete_cancelsPendingReminderNotification() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        NotificationHelper(context, PermissionManager(context)).showReminder(reminder)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, manager.activeNotifications.size)

        repo.delete(reminder)

        assertTrue(
            "删除提醒后应取消其常驻通知，避免残留无法清除",
            manager.activeNotifications.isEmpty()
        )
    }

    @Test
    fun setEnabled_false_cancelsPendingReminderNotification() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        NotificationHelper(context, PermissionManager(context)).showReminder(reminder)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, manager.activeNotifications.size)

        repo.setEnabled(reminder, false)

        assertTrue(
            "停用提醒后应取消其常驻通知，避免残留无法清除",
            manager.activeNotifications.isEmpty()
        )
    }

    @Test
    fun rescheduleAll_keepsEnabledState() = runTest {
        repo.create("enabled", 1, IntervalUnit.HOURS, true)
        val disabled = repo.create("disabled", 1, IntervalUnit.HOURS, true)
        repo.setEnabled(disabled, false)
        // 不抛异常即视为调度成功；并校验启用状态保持正确
        repo.rescheduleAll()
        val enabled = db.reminderDao().getEnabled()
        assertEquals(1, enabled.size)
        assertEquals("enabled", enabled.first().name)
    }

    @Test
    fun tryTrigger_whenDue_advancesAndReturnsReminder() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        // 直接把 nextTriggerAt 拨到过去（绕过 repo.update 的重置逻辑），模拟已到点
        val past = reminder.copy(nextTriggerAt = System.currentTimeMillis() - 1000)
        db.reminderDao().update(past)
        val fired = repo.tryTrigger(reminder.id)
        assertNotNull(fired)
        assertEquals("t", fired?.name)
        val updated = repo.getById(reminder.id)!!
        assertTrue("触发后应推进到未来时间", updated.nextTriggerAt > System.currentTimeMillis())
    }

    @Test
    fun tryTrigger_whenNotDue_returnsNull() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        // nextTriggerAt 仍在未来，未到点
        assertNull(repo.tryTrigger(reminder.id))
    }

    @Test
    fun tryTrigger_whenDisabled_returnsNull() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        repo.setEnabled(reminder, false)
        val past = repo.getById(reminder.id)!!.copy(nextTriggerAt = System.currentTimeMillis() - 1000)
        db.reminderDao().update(past)
        assertNull(repo.tryTrigger(reminder.id))
    }

    @Test
    fun nextTriggerAt_returnsEarliestEnabledTrigger() = runTest {
        val a = repo.create("a", 2, IntervalUnit.MINUTES, true)
        val b = repo.create("b", 1, IntervalUnit.MINUTES, true)
        assertEquals(b.nextTriggerAt, repo.nextTriggerAt())
        // 禁用最早的 b 后，a 成为最近触发
        repo.setEnabled(b, false)
        assertEquals(a.nextTriggerAt, repo.nextTriggerAt())
    }

    /** 把提醒的 nextTriggerAt 拨到过去使其到点，返回实际到点时间。 */
    private suspend fun makeDue(reminder: io.github.zhundianapp.zhundian.data.Reminder): Long {
        val due = System.currentTimeMillis() - 1000
        db.reminderDao().update(reminder.copy(nextTriggerAt = due))
        return due
    }

    @Test
    fun tryTrigger_createsTriggerRecord() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        val due = makeDue(reminder)

        repo.tryTrigger(reminder.id)

        val record = db.triggerRecordDao().latest(reminder.id)
        assertNotNull("触发后应写入历史记录", record)
        assertEquals(TriggerStatus.TRIGGERED, record?.status)
        assertEquals("triggerAt 应为触发前的计划触发时间", due, record?.triggerAt)
    }

    @Test
    fun completeTrigger_marksRecordCompleted_andCancelsNotification() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        makeDue(reminder)
        repo.tryTrigger(reminder.id)
        NotificationHelper(context, PermissionManager(context)).showReminder(reminder)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, manager.activeNotifications.size)

        repo.completeTrigger(reminder.id)

        assertTrue("完成后应收起通知", manager.activeNotifications.isEmpty())
        val record = db.triggerRecordDao().latest(reminder.id)
        assertEquals(TriggerStatus.COMPLETED, record?.status)
        assertNotNull("应记录完成时间", record?.resolvedAt)
    }

    @Test
    fun snoozeTrigger_reschedulesOneHour_andMarksSnoozed() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        makeDue(reminder)
        repo.tryTrigger(reminder.id)

        repo.snoozeTrigger(reminder.id)

        val updated = repo.getById(reminder.id)!!
        val diff = updated.nextTriggerAt - System.currentTimeMillis()
        assertTrue("顺延应约 1 小时: diff=$diff", abs(diff - IntervalCalculator.HOUR_MILLIS) < 2000)
        val record = db.triggerRecordDao().latest(reminder.id)
        assertEquals(TriggerStatus.SNOOZED, record?.status)
        assertNotNull("应记录改期时间", record?.resolvedAt)
    }

    @Test
    fun snoozeTrigger_whenDisabled_doesNotReschedule() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        repo.setEnabled(reminder, false)
        val disabled = repo.getById(reminder.id)!!

        repo.snoozeTrigger(disabled.id)

        val after = repo.getById(reminder.id)!!
        assertEquals("停用提醒不应被改期", disabled.nextTriggerAt, after.nextTriggerAt)
    }

    @Test
    fun completeTrigger_withoutRecord_isSafe() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        // 从未触发过，无 TRIGGERED 记录
        repo.completeTrigger(reminder.id)
        assertNull(db.triggerRecordDao().latest(reminder.id))
    }

    @Test
    fun delete_removesHistoryRecords() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        makeDue(reminder)
        repo.tryTrigger(reminder.id)
        assertNotNull(db.triggerRecordDao().latest(reminder.id))

        repo.delete(reminder)

        assertNull("删除提醒后应清空其历史", db.triggerRecordDao().latest(reminder.id))
    }

    @Test
    fun create_withMessage_storesTrimmedOrNull() = runTest {
        val withMessage = repo.create("a", 1, IntervalUnit.HOURS, true, message = "  该起来了  ")
        assertEquals("该起来了", repo.getById(withMessage.id)?.message)
        val blank = repo.create("b", 1, IntervalUnit.HOURS, true, message = "   ")
        assertNull("空白提醒语应存为 null", repo.getById(blank.id)?.message)
    }

    @Test
    fun create_withSoundSettings_roundTrip() = runTest {
        val uri = "content://media/internal/audio/1001"
        val reminder = repo.create(
            "a", 1, IntervalUnit.HOURS, true,
            soundEnabled = false, soundUri = uri
        )
        val loaded = repo.getById(reminder.id)!!
        assertEquals("声音开关应持久化", false, loaded.soundEnabled)
        assertEquals("自定义铃声 URI 应持久化", uri, loaded.soundUri)
    }

    @Test
    fun create_soundEnabledDefaultsOn_soundUriDefaultsNull() = runTest {
        val reminder = repo.create("a", 1, IntervalUnit.HOURS, true)
        val loaded = repo.getById(reminder.id)!!
        assertTrue("声音默认应开启", loaded.soundEnabled)
        assertNull("未选择铃声时 URI 应为 null", loaded.soundUri)
    }

    @Test
    fun create_soundVolumeAndDuration_roundTrip() = runTest {
        val reminder = repo.create(
            "a", 1, IntervalUnit.HOURS, true,
            soundVolume = 0.3f, soundDurationSeconds = 15
        )
        val loaded = repo.getById(reminder.id)!!
        assertEquals("音量应持久化", 0.3f, loaded.soundVolume, 0.0001f)
        assertEquals("响铃时长应持久化", 15, loaded.soundDurationSeconds)
    }

    @Test
    fun deleteTriggerRecord_removesOnlyThatRecord() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        makeDue(reminder)
        repo.tryTrigger(reminder.id)
        // 再触发一次，产生第二条记录
        val second = repo.getById(reminder.id)!!
        db.reminderDao().update(second.copy(nextTriggerAt = System.currentTimeMillis() - 1000))
        repo.tryTrigger(reminder.id)

        val all = db.triggerRecordDao().getBetween(reminder.id, 0L, Long.MAX_VALUE).first()
        assertEquals("应产生两条记录", 2, all.size)

        repo.deleteTriggerRecord(all.first())

        val remaining = db.triggerRecordDao().getBetween(reminder.id, 0L, Long.MAX_VALUE).first()
        assertEquals("只删一条，剩余一条", 1, remaining.size)
        assertEquals("删除应精确命中目标记录", all.last().id, remaining.single().id)
    }

    @Test
    fun deleteTriggerRecords_batchRemovesOnlySelected() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        makeDue(reminder)
        repo.tryTrigger(reminder.id)
        // 再触发两次，共产生三条记录
        repeat(2) {
            val next = repo.getById(reminder.id)!!
            db.reminderDao().update(next.copy(nextTriggerAt = System.currentTimeMillis() - 1000))
            repo.tryTrigger(reminder.id)
        }

        val all = db.triggerRecordDao().getBetween(reminder.id, 0L, Long.MAX_VALUE).first()
        assertEquals("应产生三条记录", 3, all.size)

        // 按 id 明确指定保留哪条，避免 triggerAt 同毫秒时排序不确定
        val idToKeep = all.minByOrNull { it.id }!!.id
        repo.deleteTriggerRecords(all.filter { it.id != idToKeep })

        val remaining = db.triggerRecordDao().getBetween(reminder.id, 0L, Long.MAX_VALUE).first()
        assertEquals("只删选中两条，剩余一条", 1, remaining.size)
        assertEquals("剩余应为未被选中的那条", idToKeep, remaining.single().id)
    }

    @Test
    fun deleteTriggerRecords_emptyCollection_isSafe() = runTest {
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        makeDue(reminder)
        repo.tryTrigger(reminder.id)
        val before = db.triggerRecordDao().getBetween(reminder.id, 0L, Long.MAX_VALUE).first()

        repo.deleteTriggerRecords(emptyList())

        val after = db.triggerRecordDao().getBetween(reminder.id, 0L, Long.MAX_VALUE).first()
        assertEquals("空集合应无副作用", before.size, after.size)
    }

    // ---------- 锚定调度（天间隔 + 固定触发时刻） ----------

    @Test
    fun create_daysWithTriggerTime_anchorsNextTriggerToTimeOfDay() = runTest {
        val minutes = 6 * 60 // 06:00
        val reminder = repo.create("吃药", 2, IntervalUnit.DAYS, true, triggerTimeMinutes = minutes)
        val anchor = IntervalCalculator.anchorAtToday(minutes)!!

        assertEquals("锚定参考点应持久化", anchor, reminder.scheduleAnchorAt)
        val cal = Calendar.getInstance()
        cal.timeInMillis = reminder.nextTriggerAt
        assertEquals("触发时刻应固定为 06:00", 6, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        val diff = reminder.nextTriggerAt - anchor
        assertTrue("与锚点差应为 2 天的整数倍: diff=$diff", diff >= 0 && diff % (2 * IntervalCalculator.DAY_MILLIS) == 0L)
    }

    @Test
    fun onTriggered_daysAnchored_keepsFixedTimeOfDay() = runTest {
        val minutes = 6 * 60
        val reminder = repo.create("t", 2, IntervalUnit.DAYS, true, triggerTimeMinutes = minutes)
        db.reminderDao().update(reminder.copy(nextTriggerAt = System.currentTimeMillis() - 1000))
        repo.tryTrigger(reminder.id)

        val updated = repo.getById(reminder.id)!!
        val cal = Calendar.getInstance()
        cal.timeInMillis = updated.nextTriggerAt
        assertEquals("触发后下次仍应是 06:00", 6, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        val diff = updated.nextTriggerAt - updated.scheduleAnchorAt!!
        assertTrue(
            "应沿锚定序列推进: diff=$diff",
            diff >= 0 && diff % (2 * IntervalCalculator.DAY_MILLIS) == 0L
        )
    }

    @Test
    fun snoozeThenTrigger_daysAnchored_returnsToSchedule() = runTest {
        val minutes = 6 * 60
        val reminder = repo.create("t", 2, IntervalUnit.DAYS, true, triggerTimeMinutes = minutes)
        val anchor = reminder.scheduleAnchorAt!!
        // 第一次到点触发
        db.reminderDao().update(reminder.copy(nextTriggerAt = System.currentTimeMillis() - 1000))
        repo.tryTrigger(reminder.id)
        // 「再隔 1 小时」：脱离锚定序列
        repo.snoozeTrigger(reminder.id)
        val snoozed = repo.getById(reminder.id)!!
        // 改期后的临时时刻再触发
        db.reminderDao().update(snoozed.copy(nextTriggerAt = System.currentTimeMillis() - 1000))
        repo.tryTrigger(reminder.id)

        val updated = repo.getById(reminder.id)!!
        val cal = Calendar.getInstance()
        cal.timeInMillis = updated.nextTriggerAt
        assertEquals("改期后再触发应回落到 06:00", 6, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        val diff = updated.nextTriggerAt - anchor
        assertTrue(
            "改期不应移动节奏，仍在锚定序列上: diff=$diff",
            diff >= 0 && diff % (2 * IntervalCalculator.DAY_MILLIS) == 0L
        )
        assertTrue("下次应在将来", updated.nextTriggerAt > System.currentTimeMillis() - 1000)
    }

    @Test
    fun update_daysWithTriggerTime_reanchorsToNewAnchor() = runTest {
        // 先以旧行为创建（小时间隔），再编辑为「天间隔 + 触发时刻」
        val reminder = repo.create("t", 1, IntervalUnit.HOURS, true)
        val minutes = 18 * 60 // 18:00
        val anchor = IntervalCalculator.anchorAtToday(minutes)
        repo.update(
            reminder.copy(
                intervalValue = 2,
                intervalUnit = IntervalUnit.DAYS,
                scheduleAnchorAt = anchor
            )
        )

        val updated = repo.getById(reminder.id)!!
        assertEquals("新锚点应持久化", anchor, updated.scheduleAnchorAt)
        val cal = Calendar.getInstance()
        cal.timeInMillis = updated.nextTriggerAt
        assertEquals("编辑后应固定在新触发时刻 18:00", 18, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        val diff = updated.nextTriggerAt - anchor!!
        assertTrue(
            "编辑后应沿新锚点重排: diff=$diff",
            diff >= 0 && diff % (2 * IntervalCalculator.DAY_MILLIS) == 0L
        )
    }
}
