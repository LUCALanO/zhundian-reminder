package io.github.zhundianapp.zhundian

import io.github.zhundianapp.zhundian.ui.edit.validateReminderForm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑表单校验测试：ReminderEditViewModel.save() 委托给纯函数 validateReminderForm。
 */
class ReminderEditViewModelTest {

    @Test
    fun emptyName_isRejected() {
        val result = validateReminderForm("", "2")
        assertTrue(result.nameError)
        assertFalse(result.isValid)
    }

    @Test
    fun blankName_isRejected() {
        val result = validateReminderForm("   ", "2")
        assertTrue(result.nameError)
        assertFalse(result.isValid)
    }

    @Test
    fun zeroInterval_isRejected() {
        val result = validateReminderForm("吃药", "0")
        assertTrue(result.intervalError)
        assertFalse(result.isValid)
    }

    @Test
    fun negativeInterval_isRejected() {
        val result = validateReminderForm("吃药", "-3")
        assertTrue(result.intervalError)
        assertFalse(result.isValid)
    }

    @Test
    fun nonNumericInterval_isRejected() {
        val result = validateReminderForm("吃药", "abc")
        assertTrue(result.intervalError)
        assertFalse(result.isValid)
    }

    @Test
    fun emptyInterval_isRejected() {
        val result = validateReminderForm("吃药", "")
        assertTrue(result.intervalError)
        assertFalse(result.isValid)
    }

    @Test
    fun validForm_passes() {
        val result = validateReminderForm("吃药", "2")
        assertFalse(result.nameError)
        assertFalse(result.intervalError)
        assertTrue(result.isValid)
    }
}
