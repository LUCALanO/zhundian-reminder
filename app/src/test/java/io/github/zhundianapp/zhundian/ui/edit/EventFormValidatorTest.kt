package io.github.zhundianapp.zhundian.ui.edit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFormValidatorTest {

    @Test
    fun validForm_passes() {
        val v = validateEventForm("复查", startAt = 1000, endAt = 2000, allDay = false)
        assertTrue("标题非空 + 结束晚于开始应通过", v.isValid)
    }

    @Test
    fun blankTitle_fails() {
        val v = validateEventForm("  ", startAt = 1000, endAt = 2000, allDay = false)
        assertTrue("空白标题应报错", v.titleError)
        assertFalse(v.isValid)
    }

    @Test
    fun endAtNotAfterStart_fails() {
        val v = validateEventForm("复查", startAt = 2000, endAt = 2000, allDay = false)
        assertTrue("结束等于开始应报错", v.timeError)
        assertFalse(v.isValid)
    }

    @Test
    fun endBeforeStart_fails() {
        val v = validateEventForm("复查", startAt = 2000, endAt = 1000, allDay = false)
        assertTrue("结束早于开始应报错", v.timeError)
        assertFalse(v.isValid)
    }

    @Test
    fun allDay_skipsTimeCheck() {
        // 全天日程结束恒为次日零点（结束 > 开始），即使传入反序也不应报时间错
        val v = validateEventForm("全天假", startAt = 2000, endAt = 1000, allDay = true)
        assertFalse("全天日程不做时间先后校验", v.timeError)
    }

    @Test
    fun allDay_blankTitle_stillFails() {
        val v = validateEventForm("", startAt = 1000, endAt = 2000, allDay = true)
        assertTrue("全天日程标题仍必填", v.titleError)
        assertFalse(v.isValid)
    }
}
