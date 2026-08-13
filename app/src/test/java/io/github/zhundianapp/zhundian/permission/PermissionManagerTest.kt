package io.github.zhundianapp.zhundian.permission

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionManagerTest {

    private lateinit var permissionManager: PermissionManager
    private lateinit var origManufacturer: String
    private lateinit var origBrand: String

    @Before
    fun setUp() {
        permissionManager = PermissionManager(ApplicationProvider.getApplicationContext<Context>())
        origManufacturer = Build.MANUFACTURER
        origBrand = Build.BRAND
    }

    @After
    fun tearDown() {
        // 恢复 Build 静态字段，避免影响其他测试
        ShadowBuild.setManufacturer(origManufacturer)
        ShadowBuild.setBrand(origBrand)
    }

    @Test
    fun romKey_identifiesEachVendor() {
        assertEquals("xiaomi", romKeyOf("Xiaomi", "Xiaomi"))
        assertEquals("xiaomi", romKeyOf("Redmi", "redmi"))
        assertEquals("honor", romKeyOf("HONOR", "HONOR"))
        assertEquals("huawei", romKeyOf("HUAWEI", "HUAWEI"))
        assertEquals("oppo", romKeyOf("OPPO", "OPPO"))
        assertEquals("oppo", romKeyOf("realme", "realme"))
        assertEquals("oppo", romKeyOf("OnePlus", "OnePlus"))
        assertEquals("vivo", romKeyOf("vivo", "vivo"))
        assertEquals("vivo", romKeyOf("iQOO", "iQOO"))
        assertEquals("samsung", romKeyOf("samsung", "samsung"))
        assertEquals("meizu", romKeyOf("Meizu", "Meizu"))
    }

    @Test
    fun romKey_fallsBackToOtherForUnknownVendor() {
        assertEquals("other", romKeyOf("unknown", "unknown"))
        assertEquals("other", romKeyOf("", ""))
    }

    /** 同时设置 Build.MANUFACTURER / Build.BRAND 并返回 romKey。 */
    private fun romKeyOf(manufacturer: String, brand: String): String {
        ShadowBuild.setManufacturer(manufacturer)
        ShadowBuild.setBrand(brand)
        return permissionManager.romKey()
    }
}
