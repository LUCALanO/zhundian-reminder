package io.github.zhundianapp.zhundian.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import io.github.zhundianapp.zhundian.MainActivity
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.alarm.ActionReceiver
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.CalendarSettings
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.permission.PermissionManager
import java.text.DateFormat
import java.util.Date

/**
 * 通知渠道创建、横幅通知构建，以及到点的强制震动 / 铃声 / 悬浮窗触发。
 *
 * 声音与振动都不走通知渠道（国产 ROM 常压制通知声音与震动，不可预期）：
 * 振动直接调 Vibrator、铃声直接播 SoundPlayer，均绕过系统通知设置，DND 下也尽量可达。
 * 两个提醒渠道均保持 IMPORTANCE_HIGH 以保证到点弹出横幅，但**不启用渠道声音/震动**。
 * 「顶部弹窗」为可选悬浮窗，需「显示在其他应用上层」权限。
 */
class NotificationHelper(
    private val context: Context,
    private val permissionManager: PermissionManager
) {

    companion object {
        const val CHANNEL_VIBRATION = "reminders_vibration"
        const val CHANNEL_SILENT = "reminders_silent"
        const val CHANNEL_SERVICE = "reminder_service"

        /** 已废弃的渠道 ID：渠道设置首次创建后即被系统固化，需显式删除。 */
        private val REMOVED_CHANNELS = listOf(
            "reminders",                // 最早的单渠道
            "reminders_sound_vibration", // 已移除的声音+振动渠道
            "reminders_sound"           // 已移除的声音渠道
        )

        private const val NOTIFICATION_ID_OFFSET = 1000

        /** 系统日历日程通知的 id 偏移，与间隔提醒的 1000+id 错开，互不覆盖。 */
        const val EVENT_NOTIFICATION_ID_OFFSET = 1_000_000

        private val VIBRATION_PATTERN = longArrayOf(0, 300, 200, 300)
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val floatingWindow = FloatingReminderWindow(context)
    private val soundPlayer = SoundPlayer(context)

    /** 创建全部渠道（幂等）并清理不再使用的旧渠道。 */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 渠道不再启用震动：振动统一由 showReminder 里直接调 Vibrator 负责（可绕过系统通知震动设置）
        createChannel(
            CHANNEL_VIBRATION,
            context.getString(R.string.notification_channel_vibration),
            NotificationManager.IMPORTANCE_HIGH, null
        )
        createChannel(
            CHANNEL_SILENT,
            context.getString(R.string.notification_channel_silent),
            NotificationManager.IMPORTANCE_HIGH, null
        )
        createChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW, null,
            context.getString(R.string.notification_channel_service_desc)
        )
        REMOVED_CHANNELS.forEach { notificationManager.deleteNotificationChannel(it) }
    }

    private fun createChannel(
        id: String,
        name: String,
        importance: Int,
        vibration: LongArray?,
        description: String? = null
    ) {
        val channel = NotificationChannel(id, name, importance).apply {
            setSound(null, null) // 显式静音：不依赖系统默认音
            if (vibration != null) {
                enableVibration(true)
                setVibrationPattern(vibration)
            } else {
                enableVibration(false)
            }
            description?.let { this.description = it }
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** 按提醒的振动开关二选一渠道（纯函数，可单测）。 */
    fun channelFor(reminder: Reminder): String =
        if (reminder.vibrationEnabled) CHANNEL_VIBRATION else CHANNEL_SILENT

    /**
     * 弹出到点提醒的横幅通知，并按提醒设置触发强制震动 / 顶部悬浮窗。
     *
     * 通知设为常驻（ongoing）：不可滑动清除，也不受通知栏「一键清空」批量移除，
     * 避免用户忙碌未处理时消息被直接清掉；点击通知进入 App 后自动消失（autoCancel）。
     * 通知附带「完成」「再隔 1 小时提醒」两个操作按钮，由 [ActionReceiver] 响应。
     */
    fun showReminder(reminder: Reminder) {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, channelFor(reminder))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.name)
            .setContentText(reminderContentText(reminder))
            .setContentIntent(openAppIntent(reminder.id))
            .setAutoCancel(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_action_done,
                    context.getString(R.string.notification_action_complete),
                    actionIntent(reminder.id, ActionReceiver.ACTION_COMPLETE)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_action_snooze,
                    context.getString(R.string.notification_action_snooze),
                    actionIntent(reminder.id, ActionReceiver.ACTION_SNOOZE)
                )
            )
        notificationManager.notify(NOTIFICATION_ID_OFFSET + reminder.id.toInt(), builder.build())

        // 强制震动：直接调 Vibrator，不依赖通知渠道 / 系统通知震动设置（Flyme 等 ROM 压渠道震动也照样震）
        if (reminder.vibrationEnabled) vibrateDirect()
        // 铃声：直接播放，不依赖通知渠道声音设置（与强制震动同一思路）
        if (reminder.soundEnabled) {
            soundPlayer.play(
                reminder.soundUri?.toUri(),
                reminder.soundVolume,
                reminder.soundDurationSeconds
            )
        }
        // 顶部悬浮窗：仅当该提醒开启且已授予「显示在其他应用上层」权限
        if (reminder.overlayEnabled && permissionManager.hasOverlayPermission()) {
            floatingWindow.show(reminder)
        }
    }

    /**
     * 弹出系统日历日程到点提醒，按**全局设置**触发强制震动 / 铃声 / 顶部悬浮窗。
     *
     * 与间隔提醒的差异：日程是一次性提醒，横幅通知不常驻（autoCancel）、无「完成/再隔
     * 1 小时」操作按钮，点击仅打开 App。
     */
    fun showCalendarEvent(event: CalendarEvent, settings: CalendarSettings) {
        ensureChannels()
        val channel = if (settings.vibrationEnabled) CHANNEL_VIBRATION else CHANNEL_SILENT
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(eventSummaryText(event))
            .setContentIntent(openAppIntent(-1L))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
        notificationManager.notify(
            EVENT_NOTIFICATION_ID_OFFSET + event.id.toInt(),
            builder.build()
        )

        // 强制震动 / 铃声 / 顶部悬浮窗：与间隔提醒同一套「绕过系统通知设置」的逻辑
        if (settings.vibrationEnabled) vibrateDirect()
        if (settings.soundEnabled) {
            soundPlayer.play(
                settings.soundUri?.toUri(),
                settings.soundVolume,
                settings.soundDurationSeconds
            )
        }
        if (settings.overlayEnabled && permissionManager.hasOverlayPermission()) {
            floatingWindow.showEvent(event)
        }
    }

    /** 取消某条日程的到点通知（从本 App 删除日程时调用）。 */
    fun cancelEventNotification(eventId: Long) {
        notificationManager.cancel(EVENT_NOTIFICATION_ID_OFFSET + eventId.toInt())
    }

    /** 日程通知正文：全天显示日期，否则显示起止时间，均附地点（有则加）。 */
    private fun eventSummaryText(event: CalendarEvent): String {
        val timeText = if (event.allDay) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(event.startAt))
        } else {
            val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
            timeFormat.format(Date(event.startAt)) + " - " + timeFormat.format(Date(event.endAt))
        }
        return event.location?.takeIf { it.isNotBlank() }?.let { "$timeText · $it" } ?: timeText
    }

    /** 停止当前提醒铃声（通知「完成 / 再隔 1 小时」时调用）。 */
    fun stopSound() = soundPlayer.stop()

    /** 收起顶部悬浮窗（通知栏「完成 / 再隔 1 小时」、手动关闭等任一关闭路径时调用）。 */
    fun dismissOverlay() = floatingWindow.dismiss()

    /** 直接调 Vibrator 强制震动，绕过系统通知震动设置。 */
    private fun vibrateDirect() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
    }

    /**
     * 取消某提醒的到点通知（删除 / 停用提醒时调用）。
     * 常驻通知无法手动清除，若不取消会残留在通知栏。
     */
    fun cancelReminder(reminderId: Long) {
        notificationManager.cancel(NOTIFICATION_ID_OFFSET + reminderId.toInt())
    }

    /** 前台服务的常驻通知（低重要性、不可滑动）。 */
    fun serviceNotification(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(context.getString(R.string.service_notification_text))
            .setContentIntent(openAppIntent(-1L))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 通知正文：优先自定义提示语，空白/未设置时回退默认文案。 */
    private fun reminderContentText(reminder: Reminder): String =
        reminder.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_content)

    /** 通知操作按钮的广播 PendingIntent（「完成」与「再隔 1 小时提醒」用不同 requestCode）。 */
    private fun actionIntent(id: Long, action: String): PendingIntent {
        val requestCode = if (action == ActionReceiver.ACTION_COMPLETE) {
            id.toInt()
        } else {
            id.toInt() + ActionReceiver.ACTION_REQUEST_OFFSET
        }
        val intent = Intent(context, ActionReceiver::class.java).apply {
            this.action = action
            putExtra(ActionReceiver.EXTRA_REMINDER_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (id > 0) putExtra(MainActivity.EXTRA_REMINDER_ID, id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
