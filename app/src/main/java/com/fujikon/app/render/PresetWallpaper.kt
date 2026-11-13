package com.fujikon.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

/**
 * 首页「我的预设」列表的固定缩略图源：一张从互联网平台（Unsplash）抓取的
 * 4K（3840×2560）高清风景照壁纸，随 APK 打包在 assets 中，完全离线可用。
 *
 * 1.6.53 起主列表的每个预设缩略图都套用在这张**同一张**风景照上，让不同
 * 预设之间可以直接横向对比，而不再随用户最近选的照片或内置合成样张变化。
 *
 * 源图是 4K，但缩略图只需很小尺寸：这里按最长边 [SOURCE_DIMENSION] 下采样
 * 解码并单例缓存，**只解码一次、所有预设共享一份 source**（约 3MB），避免
 * 每张卡片都解一张 4K 图造成内存与耗时爆炸。
 */
object PresetWallpaper {

    const val ASSET_NAME = "preset_wallpaper_4k.jpg"

    /** 缓存键前缀：所有预设共用同一张壁纸，靠 visualSignature 区分效果。 */
    const val SOURCE_KEY = "wallpaper4k:v1"

    /** 壁纸源解码到的最长边：远高于任何缩略图目标尺寸，画质足够、内存可控。 */
    private const val SOURCE_DIMENSION = 960

    @Volatile
    private var cached: Bitmap? = null
    private val lock = Any()

    /**
     * 返回可复用的壁纸源 bitmap（永远同一实例，非 null 且未回收）。
     * 渲染器在 forExport=false 下只读 source、不回收，因此可安全被多个预设
     * 反复套用。
     */
    fun get(context: Context): Bitmap? {
        cached?.let { if (!it.isRecycled) return it }
        return synchronized(lock) {
            cached?.let { if (!it.isRecycled) return it }
            val decoded = decode(context)
            cached = decoded
            decoded
        }
    }

    private fun decode(context: Context): Bitmap? = runCatching {
        val app = context.applicationContext
        // 先只读 bounds，算出 inSampleSize，避免一次性把 4K 全图解码进内存。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.assets.open(ASSET_NAME).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > SOURCE_DIMENSION * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = app.assets.open(ASSET_NAME).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        // 再精确缩到目标最长边（双线性，带 filter），不同设备结果一致。
        BitmapLoader.scaleForPreview(decoded, SOURCE_DIMENSION)
            .also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()
}
