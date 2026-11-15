package com.fujikon.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

/**
 * 首页「我的预设」列表的固定缩略图源：一张随 APK 打包在 assets 中的
 * 4K（3840×2560）高清风景照壁纸，完全离线可用。
 *
 * 1.6.53 起主列表的每个预设缩略图都套用在这张**同一张**风景照上，让不同
 * 预设之间可以直接横向对比，而不再随用户最近选的照片或内置合成样张变化。
 *
 * 1.6.61 滑动性能修复：卡片预览实际只有约 150dp 高，旧实现却把共享源解码到
 * 960 最长边，于是**每个预设**都要对 960×640 跑一遍完整多-pass 渲染管线，
 * 再缩到 480 —— 上百个预设在滑动时排队渲染、把 CPU 占满，造成严重掉帧。
 * 现在直接把共享源下采样到缩略图目标尺寸 [SOURCE_DIMENSION]：渲染像素量
 * 从约 61 万降到约 8.6 万（约 7 倍），且仍然只解码一次、所有预设共享同一
 * 份 source；最终缩略图尺寸不变，观感一致。
 */
object PresetWallpaper {

    const val ASSET_NAME = "preset_wallpaper_4k.jpg"

    /** 缓存键前缀：所有预设共用同一张壁纸，靠 visualSignature 区分效果。
     *  v2：共享源尺寸变更，避免与旧尺寸渲染出的缓存条目混淆。 */
    const val SOURCE_KEY = "wallpaper4k:v2"

    /** 壁纸源解码到的最长边：与首页缩略图目标尺寸对齐，画质足够、内存可控。 */
    private const val SOURCE_DIMENSION = 360

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
