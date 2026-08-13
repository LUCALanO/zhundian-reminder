package io.github.zhundianapp.zhundian.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * 到点铃声播放器：用 [MediaPlayer] 直接播放铃声，绕过系统通知声音设置。
 *
 * 与「直接调 Vibrator 强制震动」同一思路——不依赖通知渠道的 setSound（国产 ROM 常压制
 * 通知声音，不可预期），而是用 MediaPlayer 在闹钟流（USAGE_ALARM）上直接播放：DND 默认
 * 放行闹钟音、且只受闹钟音量控制。循环播放，最长响 [durationSeconds] 秒后自动停止，
 * 用户点通知「完成 / 再隔 1 小时」或软件内停用/删除提醒时由 [stop] 提前结束。
 *
 * 相比 [android.media.Ringtone]，MediaPlayer 支持 [MediaPlayer.setVolume] 做应用内音量
 * 缩放（0~1，相对闹钟流音量），解决「系统通知音量不可控 / 声音太大」的问题。
 *
 * MediaPlayer 回调依赖线程 Looper，因此所有播放操作都经主线程 Handler 执行，从 IO 协程
 * 调用也安全。用代际计数保证旧的自动停止回调不会误停新铃声。
 */
class SoundPlayer(private val context: Context) {

    companion object {
        /** 响铃时长可选档位（秒）。 */
        val DURATION_OPTIONS_SECONDS = listOf(3, 5, 15, 30)

        /** 默认响铃时长（秒）。 */
        const val DEFAULT_DURATION_SECONDS = 5

        /** 默认响铃音量（0~1，相对闹钟流音量）。 */
        const val DEFAULT_VOLUME = 0.7f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** 代际计数：每次开始/停止递增，使已排队的回调因代际不匹配而空转。 */
    private var generation = 0

    /**
     * 开始播放。uri 为空或为 `content://settings/...` 符号 URI 时解析为真实系统默认铃声；
     * 无法解析或创建失败时静默跳过。若已有铃声在响会先停止再重播。
     */
    fun play(uri: Uri?, volume: Float, durationSeconds: Int) {
        val soundUri = resolveUri(uri) ?: return
        handler.post { startOnMain(soundUri, volume.coerceIn(0f, 1f), durationSeconds) }
    }

    /** 停止当前铃声并释放资源（幂等）。 */
    fun stop() {
        handler.post { stopOnMain() }
    }

    /**
     * 解析真实播放 URI：
     * - null 或 `content://settings/...`（符号 URI）→ 解析为系统默认闹钟/通知/铃声的真实 URI。
     *   符号 URI 不能直接喂给 MediaPlayer.setDataSource（会抛 status=0x80000000）。
     * - 其余（content://media/...、SAF document URI）原样返回。
     */
    private fun resolveUri(uri: Uri?): Uri? {
        val symbolic = uri == null || (uri.scheme == "content" && uri.authority == "settings")
        if (!symbolic) return uri
        val type = when (uri?.lastPathSegment?.lowercase()) {
            "notification_sound" -> RingtoneManager.TYPE_NOTIFICATION
            "ringtone" -> RingtoneManager.TYPE_RINGTONE
            else -> RingtoneManager.TYPE_ALARM
        }
        return RingtoneManager.getActualDefaultRingtoneUri(context, type)
            ?: RingtoneManager.getDefaultUri(type)
    }

    private fun startOnMain(uri: Uri, volume: Float, durationSeconds: Int) {
        val gen = ++generation
        releaseActive()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // 深睡/Doze 下也尽量保住播放（WAKE_LOCK 为 normal 权限，自动授予）
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            // onPrepared 前先赋值 player（同主线程，onPrepared 异步派发一定晚于赋值）
            player = mp
            mp.setOnPreparedListener { prepared ->
                if (generation == gen) prepared.start()
            }
            mp.setOnErrorListener { failed, _, _ ->
                // prepare/播放失败：若仍是当前代际则停止并释放，避免崩溃
                if (generation == gen) {
                    generation++
                    if (player === failed) player = null
                }
                runCatching { failed.release() }
                true
            }
            mp.prepareAsync()
            if (durationSeconds > 0) {
                handler.postDelayed({ if (generation == gen) stopOnMain() }, durationSeconds * 1000L)
            }
        } catch (e: Exception) {
            // setDataSource/prepareAsync 失败：静默跳过，不打断提醒本身
            if (player === mp) player = null
            runCatching { mp.release() }
        }
    }

    private fun stopOnMain() {
        generation++
        releaseActive()
    }

    /** 释放当前播放器。stop() 在 Preparing/Idle 态会抛 IllegalStateException，故都包 runCatching。 */
    private fun releaseActive() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}
