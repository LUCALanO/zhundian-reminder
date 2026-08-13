package io.github.zhundianapp.zhundian.util

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import io.github.zhundianapp.zhundian.R
import java.util.Locale

/**
 * 应用内语言切换。
 *
 * 字符串资源已内置 7 种语言（zh 默认 / en / ja / ru / fr / es / de），默认跟随系统语言自动生效；
 * 本类提供应用内手动切换：Android 13+ 走官方 LocaleManager（系统持久化并自动重启页面），
 * Android 8–12（minSdk 26）写入本地偏好并在 attachBaseContext 里覆盖资源配置，切换后 recreate() 即时生效。
 */
object AppLanguage {

    /** 语言选项；tag 为 null 表示跟随系统。 */
    data class Option(val tag: String?, val labelRes: Int)

    fun options(): List<Option> = listOf(
        Option(null, R.string.lang_follow_system),
        Option("zh-CN", R.string.lang_zh),
        Option("zh-TW", R.string.lang_zh_tw),
        Option("en", R.string.lang_en),
        Option("ja", R.string.lang_ja),
        Option("ru", R.string.lang_ru),
        Option("fr", R.string.lang_fr),
        Option("es", R.string.lang_es),
        Option("de", R.string.lang_de)
    )

    /** 当前生效的语言 tag；null 表示跟随系统。 */
    fun currentTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = LocaleManagerCompat.getApplicationLocales(context)
            if (locales.isEmpty) null else locales[0]?.toLanguageTag()
        } else {
            getTag(context)
        }

    /** 切换语言并立即生效；tag 为 null 表示恢复跟随系统。 */
    fun apply(context: Context, tag: String?) {
        saveTag(context, tag)
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+：交给系统 LocaleManager 持久化并自动重启页面应用新语言
            applyViaLocaleManager(context, tag)
        } else {
            // Android 8–12：recreate() 重走 attachBaseContext，读取新偏好生效
            context.findActivity()?.recreate()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyViaLocaleManager(context: Context, tag: String?) {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)
    }

    /**
     * 在 Application / MainActivity 的 attachBaseContext 中调用：
     * Android 8–12 上把已保存的语言覆盖到资源配置（Android 13+ 走系统 LocaleManager，无需处理）。
     * Robolectric 下为 no-op，避免测试环境的资源模拟被覆盖。
     */
    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        if (Build.FINGERPRINT.contains("robolectric")) return base
        val tag = getTag(base) ?: return base
        val override = Configuration(base.resources.configuration)
        override.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(override)
    }

    /** 读取已保存的语言 tag（同步，供 attachBaseContext 使用）。 */
    fun getTag(context: Context): String? =
        prefs(context).getString(KEY_LANG, null)

    /** 保存语言 tag；null 表示跟随系统（清除保存值）。 */
    fun saveTag(context: Context, tag: String?) {
        prefs(context).edit().putString(KEY_LANG, tag).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private const val PREFS_NAME = "app_language"
    private const val KEY_LANG = "lang"
}
