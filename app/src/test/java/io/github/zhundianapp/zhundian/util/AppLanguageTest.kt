package io.github.zhundianapp.zhundian.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLanguageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun options_hasAllLanguages() {
        assertEquals(
            listOf(null, "zh-CN", "zh-TW", "en", "ja", "ru", "fr", "es", "de"),
            AppLanguage.options().map { it.tag }
        )
    }

    @Test
    fun saveTag_getTag_roundTrips() {
        assertNull("默认应跟随系统", AppLanguage.getTag(context))
        AppLanguage.saveTag(context, "fr")
        assertEquals("fr", AppLanguage.getTag(context))
        AppLanguage.saveTag(context, "zh-TW")
        assertEquals("zh-TW", AppLanguage.getTag(context))
        AppLanguage.saveTag(context, null)
        assertNull("null 表示恢复跟随系统", AppLanguage.getTag(context))
    }

    @Test
    fun currentTag_readsSavedTagBelowApi33() {
        AppLanguage.saveTag(context, "de")
        assertEquals("de", AppLanguage.currentTag(context))
        AppLanguage.saveTag(context, null)
        assertNull(AppLanguage.currentTag(context))
    }
}
