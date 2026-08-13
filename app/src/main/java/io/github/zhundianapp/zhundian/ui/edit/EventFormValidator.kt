package io.github.zhundianapp.zhundian.ui.edit

/** 新建日程表单校验结果。 */
data class EventFormValidation(
    val titleError: Boolean,
    val timeError: Boolean
) {
    val isValid: Boolean get() = !titleError && !timeError
}

/**
 * 校验新建日程表单：标题非空；非全天日程结束时间必须晚于开始时间
 * （全天日程结束 = 次日零点，恒大于开始，无需校验时间）。
 * 独立为纯函数以便单元测试。
 */
fun validateEventForm(
    title: String,
    startAt: Long,
    endAt: Long,
    allDay: Boolean
): EventFormValidation {
    val titleError = title.trim().isEmpty()
    val timeError = !allDay && endAt <= startAt
    return EventFormValidation(titleError = titleError, timeError = timeError)
}
