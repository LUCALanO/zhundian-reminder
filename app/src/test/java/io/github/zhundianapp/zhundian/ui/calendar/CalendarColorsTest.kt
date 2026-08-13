package io.github.zhundianapp.zhundian.ui.calendar

import androidx.compose.ui.graphics.Color
import io.github.zhundianapp.zhundian.data.CalendarEvent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证日程视图色点函数对 App 自建日程（sourceCalendarId = -1 哨兵值）的健壮性。
 * 复现设备问题：新建日程（未推送成功）后在日程视图渲染时闪退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarColorsTest {

    @Test
    fun reminderDot_localEventSentinelId_doesNotThrow() {
        // App 自建日程的固定哨兵 sourceCalendarId = -1
        val dot = CalendarColors.reminderDot(CalendarEvent.SOURCE_CALENDAR_ID_LOCAL)
        assertTrue(dot != Color.Unspecified)
    }

    @Test
    fun reminderDot_negativeId_doesNotThrow() {
        val dot = CalendarColors.reminderDot(-47L)
        assertTrue(dot != Color.Unspecified)
    }
}
