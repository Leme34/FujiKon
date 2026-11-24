package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 胶片直闪人像 look 的风格指纹回归。
 *
 * 直闪（机顶硬闪）胶片的公认视觉语言：
 *  ① 强 S 曲线——暗部压黑、高光提亮到近过曝，反差明显硬于常规胶片 look；
 *  ② 阴影偏青绿、高光偏暖奶油（teal-shadow / warm-highlight 分离）；
 *  ③ 肤色保持暖调通透，不被青绿染色。
 *
 * 这些断言确保新 look 真正复刻了直闪质感，而非只是又一张相近的胶片 LUT。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashPortraitLookTest {

    private fun sample(look: Int, r: Int, g: Int, b: Int): IntArray {
        val packed = DazzFilmLut.sample(DazzFilmLut.get(look)!!, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    private fun luma(c: IntArray): Float =
        c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f

    /**
     * 1.6.76 影调优化：抬黑 + 压白——暗部不死黑、高光不死白，但中段仍保留
     * 比经典负片更强的反差，维持「主体从背景跳出来」的直闪特征。
     */
    @Test
    fun liftsShadowsAndRollsOffHighlightsWithoutCrushing() {
        val flash = DazzFilmLut.LOOK_FLASH_PORTRAIT
        // 偏蓝暗部：直闪应明显抬亮，阴影有细节（旧版 luma≈33，新版≈48）。
        val shadow = luma(sample(flash, 40, 50, 66))
        assertTrue("暗部应抬亮、不死黑（shadow=$shadow）", shadow > 44f)
        // 高光中性 215：应回收到近白而非过曝（旧版≈233，新版≈221）。
        val hi = luma(sample(flash, 215, 215, 215))
        assertTrue("高光应回收、不死白（hi=$hi）", hi < 228f)
        // 纯白 255：压到近白（≈245）而非溢出死白（255）。
        val white = luma(sample(flash, 255, 255, 255))
        assertTrue("纯白应压到近白（white=$white）", white in 238f..250f)
        // 动态范围仍然拉开，画面不发灰：高光与暗部跨度足够大。
        assertTrue("高光与暗部应拉开动态范围（hi-shadow=${hi - shadow}）", hi - shadow > 150f)
    }

    /** 色彩分离：偏蓝的环境暗部被进一步染成青绿（G、B 相对 R 抬升）。 */
    @Test
    fun shadowsLeanTeal() {
        // 用真实的偏蓝阴影色样，避开肤色椭圆（纯中性灰会被肤色识别误判）。
        val c = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 40, 50, 66)
        assertTrue("阴影应偏青：G 应高于 R（R=${c[0]} G=${c[1]}）", c[1] > c[0])
        assertTrue("阴影应偏青蓝：B 应高于 R（R=${c[0]} B=${c[2]}）", c[2] > c[0])
    }

    /** 高光偏暖奶油：中性亮部 R 相对 B 抬升。 */
    @Test
    fun highlightsLeanWarmCream() {
        val c = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 215, 215, 215)
        assertTrue("高光应偏暖：R 应高于 B（R=${c[0]} B=${c[2]}）", c[0] > c[2])
    }

    /** 肤色保持暖调通透：R-B 为正且不泛青。 */
    @Test
    fun skinStaysWarmAndClean() {
        val c = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 205, 168, 140)
        assertTrue("肤色应保持暖调（R-B=${c[0] - c[2]}）", c[0] - c[2] > 30)
        assertTrue("肤色应被提亮通透（G=${c[1]} vs 原 168）", c[1] >= 160)
    }
}
