package io.github.zhundianapp.zhundian.data

import android.content.Context
import io.github.zhundianapp.zhundian.notification.SoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局设置存储（SharedPreferences）。
 *
 * 项目无 DataStore 依赖，日程提醒全局设置规模小（单一数据类），用 SharedPreferences 足够。
 * 读写都在 IO 线程执行，避免主线程文件 IO。
 */
class SettingsRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前全局设置。 */
    fun load(): CalendarSettings = CalendarSettings(
        soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true),
        soundUri = prefs.getString(KEY_SOUND_URI, null),
        soundVolume = prefs.getFloat(KEY_SOUND_VOLUME, SoundPlayer.DEFAULT_VOLUME),
        soundDurationSeconds = prefs.getInt(KEY_SOUND_DURATION_SECONDS, DEFAULT_DURATION_SECONDS),
        vibrationEnabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true),
        overlayEnabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, true),
        leadMinutes = prefs.getInt(KEY_LEAD_MINUTES, 0),
        autoSyncEnabled = prefs.getBoolean(KEY_AUTO_SYNC, true),
        enabledCalendarIds = loadEnabledCalendarIds(),
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
    )

    /** 以原子方式更新部分字段（IO 线程执行）。 */
    suspend fun update(transform: (CalendarSettings) -> CalendarSettings) {
        withContext(Dispatchers.IO) {
            val next = transform(load())
            val edit = prefs.edit()
                .putBoolean(KEY_SOUND_ENABLED, next.soundEnabled)
                .putString(KEY_SOUND_URI, next.soundUri)
                .putFloat(KEY_SOUND_VOLUME, next.soundVolume)
                .putInt(KEY_SOUND_DURATION_SECONDS, next.soundDurationSeconds)
                .putBoolean(KEY_VIBRATION_ENABLED, next.vibrationEnabled)
                .putBoolean(KEY_OVERLAY_ENABLED, next.overlayEnabled)
                .putInt(KEY_LEAD_MINUTES, next.leadMinutes)
                .putBoolean(KEY_AUTO_SYNC, next.autoSyncEnabled)
                .putLong(KEY_LAST_SYNC_AT, next.lastSyncAt)
            if (next.enabledCalendarIds == null) {
                // 未配置 → 走默认策略（仅可编辑日历）
                edit.putBoolean(KEY_CALENDAR_SELECTION_CONFIGURED, false)
            } else {
                edit.putBoolean(KEY_CALENDAR_SELECTION_CONFIGURED, true)
                    .putStringSet(
                        KEY_ENABLED_CALENDAR_IDS,
                        next.enabledCalendarIds.map { it.toString() }.toSet()
                    )
            }
            edit.commit()
        }
    }

    /**
     * 读取显式允许的日历 id 集合；未配置（configured=false）返回 null。
     * 注意：用户首次显式勾选后即固化，空集也合法（表示不同步任何日历）。
     */
    private fun loadEnabledCalendarIds(): Set<Long>? {
        if (!prefs.getBoolean(KEY_CALENDAR_SELECTION_CONFIGURED, false)) return null
        return prefs.getStringSet(KEY_ENABLED_CALENDAR_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    /** 仅更新上次同步时间（不触及其他字段，避免读改写竞态）。 */
    fun setLastSyncAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_AT, millis).commit()
    }

    companion object {
        private const val PREFS_NAME = "calendar_settings"

        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_URI = "sound_uri"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_SOUND_DURATION_SECONDS = "sound_duration_seconds"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_LEAD_MINUTES = "lead_minutes"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_CALENDAR_SELECTION_CONFIGURED = "calendar_selection_configured"
        private const val KEY_ENABLED_CALENDAR_IDS = "enabled_calendar_ids"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"

        /** 日程提醒默认响铃时长（秒），与 CalendarSettings 默认值保持一致。 */
        private const val DEFAULT_DURATION_SECONDS = 3
    }
}
