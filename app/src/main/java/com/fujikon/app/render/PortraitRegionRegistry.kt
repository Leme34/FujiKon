package com.fujikon.app.render

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/**
 * 1.6.57 阶段一：把一键优化的美颜限定在识别到的人像区域。
 *
 * 设计要点（刻意「默认不启用」，把回归风险降到 0）：
 *  - 只有当 UI 明确 [install] 了某张照片的 person 蒙版时才生效；模型不可用、
 *    无人体、纯 JVM 单测、用户手动美颜等情形注册表为空，渲染走既有路径。
 *  - 用与 [MlSegmentation] 相同的**归一化 24 点指纹**做键，因此预览（降采样）、
 *    闭环 probe（再降采样）、导出（全尺寸）虽分辨率不同仍命中同一份 mask，
 *    预览与导出画质完全一致。
 *  - 单条缓存、进程级、弱一致；指纹不匹配时返回 null（降级为原行为），
 *    绝不把上一张照片的蒙版错用到下一张。
 */
object PortraitRegionRegistry {

    private data class Entry(val key: String, val mask: PortraitRegionMask)
    private val current = AtomicReference<Entry?>(null)

    /** 由 UI 在确认「有人体」后安装；无人体/模型不可用时直接 clear。 */
    fun install(source: Bitmap?, mask: PortraitRegionMask?) {
        if (source == null || mask == null) {
            current.set(null)
            return
        }
        val (px, sw, sh) = MlPersonDetector.sample(source, 64) ?: run { current.set(null); return }
        current.set(Entry(fingerprint(px, sw, sh), mask))
    }

    fun clear() { current.set(null) }

    /** 渲染期查询；指纹不匹配返回 null（保持既有 skinMask 行为）。 */
    fun forPixels(pixels: IntArray, width: Int, height: Int): PortraitRegionMask? {
        val e = current.get() ?: return null
        return if (e.key == fingerprint(pixels, width, height)) e.mask else null
    }

    /**
     * 分辨率无关的 24 点归一化指纹，与 [MlSegmentation] 的取样点一致：
     * 相对坐标量化到 4 bit，缩略图与全尺寸落在同一相对位置时结果相同。
     */
    private fun fingerprint(pixels: IntArray, sw: Int, sh: Int): String {
        val sb = StringBuilder(72)
        for (k in 0 until 24) {
            val u = ((k * 7 + 3) % 24) / 24f
            val v = ((k * 13 + 5) % 24) / 24f
            val x = (u * (sw - 1)).toInt().coerceIn(0, sw - 1)
            val y = (v * (sh - 1)).toInt().coerceIn(0, sh - 1)
            val c = pixels[(y * sw + x).coerceIn(0, pixels.size - 1)]
            sb.append(((c shr 16) and 0xFF) shr 4)
                .append(((c shr 8) and 0xFF) shr 4)
                .append((c and 0xFF) shr 4)
        }
        return sb.toString()
    }
}
