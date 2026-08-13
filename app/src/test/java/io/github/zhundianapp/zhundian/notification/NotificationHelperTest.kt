package io.github.zhundianapp.zhundian.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.CalendarSettings
import io.github.zhundianapp.zhundian.data.IntervalUnit
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.notification.NotificationHelper.Companion.CHANNEL_SILENT
import io.github.zhundianapp.zhundian.notification.NotificationHelper.Companion.CHANNEL_VIBRATION
import io.github.zhundianapp.zhundian.permission.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationHelperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val helper = NotificationHelper(context, PermissionManager(context))

    private fun reminder(vibration: Boolean) = Reminder(
        name = "t",
        intervalValue = 1,
        intervalUnit = IntervalUnit.MINUTES,
        vibrationEnabled = vibration,
        nextTriggerAt = 0L
    )

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun channelFor_mapsVibrationToChannels() {
        assertEquals(CHANNEL_VIBRATION, helper.channelFor(reminder(vibration = true)))
        assertEquals(CHANNEL_SILENT, helper.channelFor(reminder(vibration = false)))
    }

    @Test
    fun showReminder_makesNotificationOngoing() {
        helper.showReminder(reminder(vibration = true))
        val shown = notificationManager.activeNotifications.single().notification
        assertTrue(
            "提醒通知应为常驻（ongoing），不会被通知栏「一键清空」批量移除，也不可滑动清除",
            shown.flags and Notification.FLAG_ONGOING_EVENT != 0
        )
        // FLAG_NO_CLEAR 由真实系统在 ongoing 基础上自动补齐，Robolectric 不模拟该层，故此处不断言。
    }

    @Test
    fun cancelReminder_removesPendingNotification() {
        helper.showReminder(reminder(vibration = true))
        assertEquals(1, notificationManager.activeNotifications.size)
        helper.cancelReminder(reminder(vibration = true).id)
        assertTrue(notificationManager.activeNotifications.isEmpty())
    }

    @Test
    fun showReminder_withVibration_forceVibrates() {
        helper.showReminder(reminder(vibration = true))
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertTrue(
            "开启振动时，应直接调 Vibrator 强制震动（不依赖系统通知震动设置）",
            shadowOf(vibrator).isVibrating
        )
    }

    @Test
    fun showReminder_withoutVibration_doesNotVibrate() {
        helper.showReminder(reminder(vibration = false))
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun showReminder_includesCompleteAndSnoozeActions() {
        helper.showReminder(reminder(vibration = true))
        val shown = notificationManager.activeNotifications.single().notification
        val titles = shown.actions.map { it.title.toString() }
        assertTrue(
            "通知应包含「完成」操作按钮",
            titles.contains(context.getString(R.string.notification_action_complete))
        )
        assertTrue(
            "通知应包含「再隔 1 小时提醒」操作按钮",
            titles.contains(context.getString(R.string.notification_action_snooze))
        )
    }

    @Test
    fun showReminder_usesCustomMessage_whenSet() {
        val custom = reminder(vibration = true).copy(message = "该起来走走了")
        helper.showReminder(custom)
        val shown = notificationManager.activeNotifications.single().notification
        assertEquals("该起来走走了", shown.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun showReminder_fallsBackToDefaultMessage_whenBlank() {
        helper.showReminder(reminder(vibration = true).copy(message = "   "))
        val shown = notificationManager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.notification_content),
            shown.extras.getString(Notification.EXTRA_TEXT)
        )
    }

    // ---------- 系统日历日程提醒 ----------

    private fun calendarEvent() = CalendarEvent(
        title = "晨会",
        location = "会议室",
        startAt = 3_600_000L,
        endAt = 7_200_000L,
        sourceEventId = 1L,
        sourceCalendarId = 1L
    )

    /** 关掉声音与弹窗，仅测通知/震动路径（Robolectric 无真实播放/悬浮窗环境）。 */
    private fun eventSettings(vibration: Boolean) = CalendarSettings(
        soundEnabled = false,
        vibrationEnabled = vibration,
        overlayEnabled = false
    )

    @Test
    fun showCalendarEvent_postsNotificationWithEventTitle() {
        helper.showCalendarEvent(calendarEvent(), eventSettings(vibration = false))
        val shown = notificationManager.activeNotifications.single().notification
        assertEquals("晨会", shown.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(
            "日程通知不应带「完成」按钮（一次性提醒）",
            (shown.actions ?: emptyArray())
                .none { it.title.toString() == context.getString(R.string.notification_action_complete) }
        )
    }

    @Test
    fun showCalendarEvent_withVibration_forceVibrates() {
        helper.showCalendarEvent(calendarEvent(), eventSettings(vibration = true))
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun showCalendarEvent_withoutVibration_doesNotVibrate() {
        helper.showCalendarEvent(calendarEvent(), eventSettings(vibration = false))
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun cancelEventNotification_removesPendingNotification() {
        helper.showCalendarEvent(calendarEvent(), eventSettings(vibration = false))
        assertEquals(1, notificationManager.activeNotifications.size)
        helper.cancelEventNotification(calendarEvent().id)
        assertTrue(notificationManager.activeNotifications.isEmpty())
    }
}
