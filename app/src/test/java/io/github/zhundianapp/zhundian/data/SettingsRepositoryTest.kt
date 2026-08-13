package io.github.zhundianapp.zhundian.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repo = SettingsRepository(context)

    @Test
    fun load_returnsDefaults() {
        val settings = repo.load()
        assertTrue("声音默认应开启", settings.soundEnabled)
        assertNull("默认无自定义铃声", settings.soundUri)
        assertEquals("默认音量应为 0.7", 0.7f, settings.soundVolume, 0.0001f)
        assertEquals("默认响铃时长应为 3 秒", 3, settings.soundDurationSeconds)
        assertTrue("振动默认应开启", settings.vibrationEnabled)
        assertTrue("顶部弹窗默认应开启", settings.overlayEnabled)
        assertEquals("默认开始时间提醒（提前量 0）", 0, settings.leadMinutes)
        assertTrue("默认自动同步应开启", settings.autoSyncEnabled)
        assertEquals(0L, settings.lastSyncAt)
    }

    @Test
    fun update_roundTripsChangedFields() = runTest {
        repo.update {
            it.copy(
                soundEnabled = false,
                soundUri = "content://media/1001",
                soundVolume = 0.3f,
                soundDurationSeconds = 15,
                vibrationEnabled = false,
                overlayEnabled = false,
                leadMinutes = 10,
                autoSyncEnabled = false,
                lastSyncAt = 12345L
            )
        }
        val loaded = repo.load()
        assertFalse(loaded.soundEnabled)
        assertEquals("content://media/1001", loaded.soundUri)
        assertEquals(0.3f, loaded.soundVolume, 0.0001f)
        assertEquals(15, loaded.soundDurationSeconds)
        assertFalse(loaded.vibrationEnabled)
        assertFalse(loaded.overlayEnabled)
        assertEquals(10, loaded.leadMinutes)
        assertFalse(loaded.autoSyncEnabled)
        assertEquals(12345L, loaded.lastSyncAt)
    }

    @Test
    fun update_preservesFieldsNotTouched() = runTest {
        repo.update { it.copy(soundDurationSeconds = 30) }
        val loaded = repo.load()
        assertEquals(30, loaded.soundDurationSeconds)
        assertTrue("未修改的字段应保持默认", loaded.soundEnabled)
        assertTrue("未修改的字段应保持默认", loaded.vibrationEnabled)
    }

    @Test
    fun setLastSyncAt_updatesOnlySyncTime() {
        repo.setLastSyncAt(9999L)
        val loaded = repo.load()
        assertEquals(9999L, loaded.lastSyncAt)
        assertTrue("只更新同步时间，其他字段不受影响", loaded.soundEnabled)
        assertEquals("默认响铃时长不受影响", 3, loaded.soundDurationSeconds)
    }

    @Test
    fun enabledCalendarIds_unconfiguredIsNull() {
        assertNull("未配置时应为 null（走默认策略）", repo.load().enabledCalendarIds)
    }

    @Test
    fun enabledCalendarIds_roundTripsExplicitSet() = runTest {
        repo.update { it.copy(enabledCalendarIds = setOf(1L, 2L, 5L)) }
        assertEquals(setOf(1L, 2L, 5L), repo.load().enabledCalendarIds)
    }

    @Test
    fun enabledCalendarIds_emptySetIsDistinctFromUnconfigured() = runTest {
        // 显式清空 → 不同步任何日历；与 null（默认策略：仅可编辑日历）区分
        repo.update { it.copy(enabledCalendarIds = emptySet()) }
        assertEquals(emptySet<Long>(), repo.load().enabledCalendarIds)
        // 再改回非空集合后仍可恢复
        repo.update { it.copy(enabledCalendarIds = setOf(9L)) }
        assertEquals(setOf(9L), repo.load().enabledCalendarIds)
    }
}
