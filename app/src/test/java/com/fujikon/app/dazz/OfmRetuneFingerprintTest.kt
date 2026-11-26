package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.84 OFM R 理光负片量化重做指纹回归。
 *
 * 用户提供了 10 张 Dazz OFM R 成片（泰国街景/庭院/花店/白裙人像等），
 * 做白色锚点法量化后发现旧版三处方向偏差：阴影偏青蓝、降饱和不足、褪色压暗不够。
 * 按成片锚点（白物 R-B≈+4、阴影近中性微暖、整体 sat≈0.218）重做 3D LUT。
 * 本测试锁定理光负片指纹，防止后续调参再次调回青蓝/浓郁方向：
 *  - 阴影去青（B-R 较小、近中性微暖）；
 *  - 中灰整体压暗（褪色/低对比来源）；
 *  - 白墙/白物近中性、不泛青不泛粉；
 *  - 低饱和：蓝天/绿植色偏明显收敛；
 *  - 肤色保持清透暖调，不被染灰染脏。
 */
class OfmRetuneFingerprintTest {

    private fun sample(r: Int, g: Int, b: Int): IntArray {
        val packed = DazzFilmLut.sample(DazzFilmLut.get(DazzFilmLut.LOOK_OFM_R)!!, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    private fun luma(c: IntArray) = c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f

    @Test
    fun shadowsAreNeutralNotTeal() {
        val out = sample(60, 70, 80)
        val br = out[2] - out[0]
        assertTrue("阴影应去青、近中性微暖（B-R=$br，应 <=10）", br <= 10)
    }

    @Test
    fun midGrayIsFadedDown() {
        val out = sample(128, 128, 128)
        assertTrue("中灰应褪色压暗（luma=${luma(out)}，应 <124）", luma(out) < 124f)
    }

    @Test
    fun whitesStayNeutral() {
        val wall = sample(235, 230, 225)
        assertTrue("白墙应保持中性不发青（R-B=${wall[0] - wall[2]}，应 >=3）", wall[0] - wall[2] >= 3)
        assertTrue("白墙不应泛暖发红（R-B=${wall[0] - wall[2]}，应 <=12）", wall[0] - wall[2] <= 12)
    }

    @Test
    fun saturationIsReduced() {
        val sky = sample(120, 170, 225)
        assertTrue("蓝天冷色偏应明显收敛（B-R=${sky[2] - sky[0]}，应 <=70）", sky[2] - sky[0] <= 70)
    }

    @Test
    fun skinStaysCleanAndWarm() {
        val out = sample(205, 168, 140)
        val rb = out[0] - out[2]
        assertTrue("肤色应保持清透暖调（R-B=$rb，应在 48..60）", rb in 48..60)
    }
}
