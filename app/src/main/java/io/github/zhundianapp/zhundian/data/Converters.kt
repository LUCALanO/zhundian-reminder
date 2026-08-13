package io.github.zhundianapp.zhundian.data

import androidx.room.TypeConverter

/** Room 类型转换器：将枚举以名称字符串落库。 */
class Converters {

    @TypeConverter
    fun triggerStatusToString(status: TriggerStatus): String = status.name

    @TypeConverter
    fun stringToTriggerStatus(value: String): TriggerStatus =
        TriggerStatus.valueOf(value)
}
