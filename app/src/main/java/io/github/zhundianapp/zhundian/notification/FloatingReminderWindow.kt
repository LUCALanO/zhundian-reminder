package io.github.zhundianapp.zhundian.notification

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.zhundianapp.zhundian.MainActivity
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.data.CalendarEvent
import io.github.zhundianapp.zhundian.data.Reminder
import kotlin.math.abs

/**
 * 屏幕上方悬浮提醒窗（类似 Flyme「场景助手」的顶部提示）。
 *
 * 到点提醒若开启了「顶部弹窗」，就在屏幕顶部弹一个可点击的小卡片：
 * 数秒后自动消失，点击则移除并打开 App。无「显示在其他应用上层」权限时静默跳过，
 * 权限引导在编辑页 / 日程设置页完成。
 *
 * 手势：**右滑关闭、上滑挂起**，两者都只收起本悬浮窗，不影响通知栏的常驻提醒
 * （那条可点「完成 / 再隔 1 小时」的通知保持原样，仍可正常操作）；轻点卡片打开 App。
 * 触摸穿透：沿用 v0.9 验证过的可靠配置（NOT_TOUCH_MODAL + 不启用背景模糊），触摸不挡其它应用。
 * 手势区在内容行（图标 + 文字），右滑 / 上滑 / 轻点分别触发关闭 / 挂起 / 打开 App。
 *
 * 视觉走素净路线：半透明暖白背景（HSV 17°/3%/100%，接近米白），黑色文字，
 * 铃铛图标放在浅暖色圆底上（像 QQ 应用图标一样的圆底托），仅图标 + 标题 + 正文，无冗余元素。
 * 高度自适应，但内容（图标、字号、内边距）整体放大一号，比初版更饱满。
 * 不使用系统背景模糊（FLAG_BLUR_BEHIND 在部分设备上会让
 * 窗口被误判为全屏模态、整屏触摸失效），用半透明底色保证「不挡操作」。
 * 窗口顶到屏幕最顶（覆盖状态栏，y=0，像 QQ 消息横幅）、全宽横幅，卡片左右各留 12dp 边距。
 *
 * **关闭可靠性是本类的第一原则**：部分系统上 FLAG_BLUR_BEHIND 会让 View 动画回调丢失，
 * 因此消失不依赖任何动画回调——淡出 / 滑出纯用 ValueAnimator 属性动画，并始终带一个
 * 定时兜底强制移除窗口，保证自动消失 / 点击 / 手势 / 通知栏操作任何路径都能关闭。
 *
 * 间隔提醒与系统日历日程共用此窗，用「类型前缀 + id」作为幂等键，两者可同时弹、互不覆盖。
 * 触发路径在 IO 协程，所有窗口操作都经主线程 Handler 执行。
 */
class FloatingReminderWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** 当前正在显示窗口的幂等键（无则 null），用于去重。 */
    private var showingKey: String? = null
    private var view: View? = null

    /** 弹窗存活时长（毫秒）。 */
    private val dismissDelayMillis = 5_000L

    /** 离场淡出时长（毫秒），兜底移除在其后触发。 */
    private val fadeOutDuration = 180L

    // —— 手势拖动状态 ——
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dismissThresholdX = dp(120f) // 右滑超过此距离触发关闭
    private val dismissThresholdY = dp(56f)  // 上滑超过此距离触发挂起
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false
    /** 锁定拖动轴：true=水平（右滑关闭），false=垂直（上滑挂起）。 */
    private var dragLockedHorizontal = false

    /** dp → px 辅助（类级，供布局与手势共用）。 */
    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    fun show(reminder: Reminder) {
        val message = reminder.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_content)
        showInternal("r_${reminder.id}", reminder.name, message, reminder.id)
    }

    fun showEvent(event: CalendarEvent) {
        // 日程的正文直接用事件标题（与通知正文一致的简明展示），地点已含在通知正文里
        showInternal("e_${event.id}", event.title, event.title, -1L)
    }

    private fun showInternal(key: String, title: String, message: String?, openId: Long) {
        if (!Settings.canDrawOverlays(context)) return
        if (showingKey == key) return

        handler.post {
            if (showingKey == key) return@post
            removeExisting()
            val overlay = buildView(title, message, openId)
            showingKey = key
            view = overlay
            try {
                windowManager.addView(overlay, layoutParams())
                handler.postDelayed({ dismiss() }, dismissDelayMillis)
            } catch (_: Exception) {
                // 受限 ROM / 运行时被拒：静默跳过，不打断提醒本身
                removeExisting()
            }
        }
    }

    /**
     * 关闭弹窗：淡出 + 上滑后移除。全程不依赖系统动画回调，
     * 定时兜底确保任何情况下窗口都会被移除。
     */
    fun dismiss() {
        handler.post {
            val v = view ?: return@post
            // 淡出 + 上滑（纯属性动画，直接改 alpha / translationY）
            val fade = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = fadeOutDuration
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    v.alpha = f
                    v.translationY = (1f - f) * -dp(120f)
                }
                start()
            }
            // 兜底：即使动画异常也保证移除，绝不残留
            handler.postDelayed({ removeExisting() }, fadeOutDuration + 60)
        }
    }

    private fun buildView(title: String, message: String?, openId: Long): View {
        // 根容器仅负责左右留边（12dp）
        val root = FrameLayout(context)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(20f).toInt(),
                dp(24f).toInt(),
                dp(20f).toInt(),
                dp(18f).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(20f) // 四角统一圆角
                // 半透明暖白（HSV 17°/3%/100% ≈ #FFF9F7，不透明度 95%）：素净配色，模糊由系统背景模糊承担
                setColor(0xF2FFF9F7.toInt())
                // 极淡黑描边（5%）收边：比初版更淡，去掉浅色背景上的"贴图边"廉价感
                setStroke(dp(1f).toInt(), 0x0D000000.toInt())
            }
            elevation = dp(8f)
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12f).toInt()
                rightMargin = dp(12f).toInt()
            }
        )

        // 内容行：卡片内的手势交互区。窗口矩形已被物理限制为卡片本体（状态栏下方、略窄于屏幕），
        // 矩形外的触摸在系统层直接穿透，不干扰其它应用操作与状态栏下拉。
        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        card.addView(
            contentRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 铃铛图标：浅暖圆底 + 白色铃铛，像 QQ 应用图标那样的圆底托，醒目又不艳
        val iconWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // 暖米色圆底（HSV 接近 30°/25%/98%），与暖白卡片同色系、略深一点托住铃铛
                setColor(0xFFF4E3D3.toInt())
            }
        }
        iconWrap.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_notification)
                // 白色铃铛（原始 vector 已带白色 tint，这里再强制一次保证清晰）
                setColorFilter(0xFFFFFFFF.toInt())
            },
            FrameLayout.LayoutParams(
                dp(22f).toInt(),
                dp(22f).toInt(),
                Gravity.CENTER
            )
        )
        contentRow.addView(
            iconWrap,
            LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        // 文本列：粗黑标题（单行省略）+ 深黑正文（双行省略，空则隐藏）
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                text = title
                setTextColor(0xFF000000.toInt())
                textSize = 19f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        textColumn.addView(
            TextView(context).apply {
                text = message ?: ""
                setTextColor(0xCC000000.toInt())
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4f).toInt(), 0, 0)
                visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        contentRow.addView(
            textColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14f).toInt()
                rightMargin = dp(4f).toInt()
            }
        )

        // 手势：右滑关闭 / 上滑挂起 / 轻点打开 App（监听在内容行，拖动移动整卡）。
        // 滑动只收起本悬浮窗，不触碰通知栏常驻提醒（那由「完成 / 再隔 1 小时」独立管理）。
        contentRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    dragging = false
                    dragLockedHorizontal = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!dragging) {
                        val horizontal = abs(dx) > touchSlop && abs(dx) >= abs(dy)
                        val upward = abs(dy) > touchSlop && dy < 0 && abs(dy) > abs(dx)
                        when {
                            horizontal -> {
                                dragging = true
                                dragLockedHorizontal = true
                                card.translationX = dx
                            }
                            upward -> {
                                dragging = true
                                dragLockedHorizontal = false
                                card.translationY = dy
                            }
                        }
                    }
                    if (dragging) {
                        if (dragLockedHorizontal) card.translationX = dx
                        else card.translationY = dy.coerceAtMost(0f)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val dx = card.translationX
                        val dy = card.translationY
                        when {
                            dragLockedHorizontal && dx > dismissThresholdX ->
                                swipeDismiss(card, dp(520f), 0f)          // 右滑关闭
                            !dragLockedHorizontal && dy < -dismissThresholdY ->
                                swipeDismiss(card, 0f, -dp(560f))          // 上滑挂起
                            else -> {
                                // 未达阈值：回弹原位
                                card.animate().translationX(0f).translationY(0f).setDuration(160).start()
                            }
                        }
                        dragging = false
                    } else {
                        // 轻点：收起弹窗并打开 App
                        dismiss()
                        openApp(openId)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        card.animate().translationX(0f).translationY(0f).setDuration(160).start()
                        dragging = false
                    }
                    true
                }
                else -> false
            }
        }

        return root
    }

    /** 手势滑出：沿指定方向滑出并移除。关闭 / 挂起都不影响通知栏常驻提醒。 */
    private fun swipeDismiss(v: View, endX: Float, endY: Float) {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                v.translationX = endX * t
                v.translationY = endY * t
                v.alpha = 1f - t
            }
            start()
        }
        // 兜底：动画异常也保证移除，绝不残留
        handler.postDelayed({ removeExisting() }, 280)
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        // 全宽横幅（左右各 12dp 边距由卡片内边距让出）。
        // y = 0 顶到屏幕最顶（覆盖状态栏），像 QQ 消息横幅一样贴顶弹出。
        // 注意：不启用 FLAG_BLUR_BEHIND —— 在部分设备上模糊窗口会被输入系统误判为全屏模态，
        // 导致整个屏幕的触摸失效（「没弹窗的地方也点不动」），与「不挡操作」直接冲突。
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 0
    }

    private fun removeExisting() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        showingKey = null
    }

    private fun openApp(reminderId: Long) {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (reminderId > 0) putExtra(MainActivity.EXTRA_REMINDER_ID, reminderId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}
