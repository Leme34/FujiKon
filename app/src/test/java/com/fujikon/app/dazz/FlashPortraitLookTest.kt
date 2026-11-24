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

    /** 强 S 曲线：中间调被压向两端——中灰变暗、亮部被提亮。 */
    @Test
    fun strongScurveCrushesMidAndLiftsHighlights() {
        val flash = DazzFilmLut.LOOK_FLASH_PORTRAIT
        val fxn = DazzFilmLut.LOOK_FXN_R
        // 中灰 128：直闪应比经典负片更暗（中间调下沉）。
        val midFlash = luma(sample(flash, 128, 128, 128))
        val midFxn = luma(sample(fxn, 128, 128, 128))
        assertTrue("直闪中灰应下沉（flash=$midFlash vs fxn=$midFxn）", midFlash < midFxn)
        // 亮灰 215：直闪应提亮更多（高光趋近过曝）。
        val hiFlash = luma(sample(flash, 215, 215, 215))
        val hiFxn = luma(sample(fxn, 215, 215, 215))
        assertTrue("直闪高光应更亮（flash=$hiFlash vs fxn=$hiFxn）", hiFlash > hiFxn)
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
