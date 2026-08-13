package io.github.zhundianapp.zhundian.ui.edit

/** 表单校验结果。 */
data class ReminderFormValidation(
    val nameError: Boolean,
    val intervalError: Boolean
) {
    val isValid: Boolean get() = !nameError && !intervalError
}

/**
 * 校验提醒表单：名称非空、间隔为大于 0 的整数。
 * 独立为纯函数以便单元测试。
 */
fun validateReminderForm(name: String, intervalText: String): ReminderFormValidation {
    val nameError = name.trim().isEmpty()
    val interval = intervalText.toIntOrNull()
    val intervalError = interval == null || interval <= 0
    return ReminderFormValidation(nameError = nameError, intervalError = intervalError)
}
