package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFM R 理光负片指纹回归（1.6.84 量化重做，1.6.85 去「富士红」再优化）。
 *
 * 用户提供了 10 张 Dazz OFM R 成片（泰国街景/庭院/花店/白裙人像等），
 * 白色锚点法量化：成片白物 R-B≈+4、中灰≈+3（近中性微暖）、肤色仅 +21（很淡）、
 * 整体 sat≈0.218。1.6.84 虽降饱和但高光/全局仍「加红减蓝」偏富士红、肤色 +37 偏红，
 * 1.6.85 把 high/gcast 收敛到近中性、sat 降到 0.50、肤色压淡到 +49。
 * 本测试锁定理光负片「淡暖灰」指纹，防止调回富士红/青蓝/浓郁方向：
 *  - 阴影去青（B-R 较小、近中性微暖）；
 *  - 中灰整体压暗（褪色/低对比来源）；
 *  - 白墙/白物近中性、不泛青、不过暖发红；
 *  - 低饱和：蓝天/绿植色偏明显收敛；
 *  - 肤色保持清透暖调但不过红（理光淡感），不被染灰染脏。
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
        // 1.6.85 去富士红：白墙 R-B 由 1.6.84 的 +9 收敛到 +6，上限收紧到 8。
        assertTrue("白墙不应泛富士红（R-B=${wall[0] - wall[2]}，应 <=8）", wall[0] - wall[2] <= 8)
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
        // 1.6.85 理光淡肤色：R-B 由 1.6.84 的 +53 压淡到 +49，仍明显暖调不发青。
        assertTrue("肤色应保持清透暖调（R-B=$rb，应在 44..56）", rb in 44..56)
    }
}
