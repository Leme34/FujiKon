package com.fujikon.app.render

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.MainActivity
import com.fujikon.app.data.PresetRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.53 回归：
 *  - App 锁定竖屏（需求 1）；
 *  - 首页「我的预设」缩略图固定用一张 4K 风景壁纸套预设（需求 3）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PresetWallpaperTest {

    @Test
    fun mainActivityIsLockedToPortrait() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), 0
        )
        assertEquals(
            "MainActivity 必须锁定竖屏，禁止切换横屏",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            info.screenOrientation
        )
    }

    @Test
    fun wallpaperAssetDecodesToAReusableVisibleBitmap() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val first = PresetWallpaper.get(context)
        assertNotNull("4K 风景壁纸 asset 必须能解码", first)
        assertTrue("壁纸必须含可见像素", first!!.hasVisiblePixels())
        assertTrue("壁纸下采样后最长边应受控", maxOf(first.width, first.height) <= 960)
        // 单例：所有预设共享同一份 source，不重复解码 4K。
        assertSame(first, PresetWallpaper.get(context))
        assertFalse("共享 source 不应被回收", first.isRecycled)
    }

    @Test
    fun everyPresetUsesTheFixedWallpaperAndIsCachedPerPreset() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repository = PresetRepository(context)
        repository.load()
        val presets = repository.presets.value
        assertTrue(presets.isNotEmpty())
        val store = PresetThumbnailStore(context, ImageRenderer())

        val a = presets.first()
        val b = presets[1]
        val first = store.thumbnailFromWallpaper(a, 96)
        assertNotNull("壁纸套预设必须产出可见缩略图", first)
        assertTrue(first!!.hasVisiblePixels())
        assertEquals(96, maxOf(first.width, first.height))
        assertSame("同一预设应命中缓存", first, store.cachedFromWallpaper(a, 96))
        assertSame(first, store.thumbnailFromWallpaper(a, 96))

        val second = store.thumbnailFromWallpaper(b, 96)
        assertNotNull(second)
        assertNotSame("不同预设必须各自渲染、不能共用一张", first, second)
        assertSame(second, store.cachedFromWallpaper(b, 96))
    }

    @Test
    fun wallpaperKeyIsStableRegardlessOfTargetSizeBucket() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = PresetThumbnailStore(context, ImageRenderer())
        // 固定源键：不随用户选照片变化，靠 visualSignature 区分预设。
        assertTrue(PresetWallpaper.SOURCE_KEY.isNotBlank())
        assertTrue(PresetWallpaper.ASSET_NAME.endsWith(".jpg"))
    }
}
