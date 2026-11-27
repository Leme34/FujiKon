package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFM R 理光负片指纹回归（1.6.85 去富士红 → 1.6.86 清冷白净 → 1.6.87 主体保色）。
 *
 * 用户 1.6.87 指出 1.6.86「色调-30 但套用后色彩全无」，并给出橘子静物成片。
 * 成片量化：暖色主体 sat≈0.40、R-B≈+60（鲜亮）、冷背景 sat≈0.10、R-B≈-4
 * （去色青灰）、整体 sat≈0.227——即「主体保色 + 环境清冷」，而非全局去色。
 * 1.6.86 的 sat=0.44 把暖主体也压到 0.362，正是「色彩全无」根因。
 * 1.6.87 把 sat 提到 0.58、skinSatDelta 降到 0.02：暖主体 sat 回升到 ≈0.40、
 * 整体 ≈0.228，精准命中成片，同时肤色不泛富士红、环境保持青绿冷调。
 * 本测试锁定「主体有色彩 + 环境清冷、但不发灰不泛红」指纹：
 *  - 暖色主体（橘子/花/黄果）应恢复明显色彩（sat 不再被压没）；
 *  - 阴影转青（B-R 为正、不再暖褐）；
 *  - 中灰亮度保持（不清冷过头塌成灰蒙蒙）；
 *  - 白墙中性略冷、不泛黄不泛红；
 *  - 蓝天冷色偏仍收敛（不过艳发蓝）；
 *  - 肤色白净清透但不发青发脏（R-B 明显为正）。
 */
class OfmRetuneFingerprintTest {

    private fun sample(r: Int, g: Int, b: Int): IntArray {
        val packed = DazzFilmLut.sample(DazzFilmLut.get(DazzFilmLut.LOOK_OFM_R)!!, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    private fun luma(c: IntArray) = c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f

    private fun saturation(c: IntArray): Float {
        val mx = maxOf(c[0], c[1], c[2])
        val mn = minOf(c[0], c[1], c[2])
        return if (mx > 0) (mx - mn).toFloat() / mx else 0f
    }

    @Test
    fun shadowsAreCoolNotWarmBrown() {
        val out = sample(60, 70, 80)
        val br = out[2] - out[0]
        // 1.6.86 清冷：阴影转青（B-R≈+11），不再像 1.6.85 的暖褐 B-R≈+7 偏小。
        assertTrue("阴影应转青冷（B-R=$br，应 >=8）", br >= 8)
        assertTrue("阴影不应过冷发蓝（B-R=$br，应 <=20）", br <= 20)
    }

    @Test
    fun midGrayKeepsBrightnessNotMuddy() {
        val out = sample(128, 128, 128)
        // 清冷不能塌成 1.6.79 的灰蒙蒙：中灰亮度保持 118..124。
        assertTrue("中灰应保持亮度不清冷塌灰（luma=${luma(out)}，应 >=118）", luma(out) >= 118f)
        assertTrue("中灰仍应比原图略压暗（luma=${luma(out)}，应 <=124）", luma(out) <= 124f)
    }

    @Test
    fun whitesStayNeutralCool() {
        val wall = sample(235, 230, 225)
        // 清冷白物：R-B≈+4，泛青不泛黄红，但不过度发青（>=-2）。
        assertTrue("白墙应近中性略冷（R-B=${wall[0] - wall[2]}，应 >=-2）", wall[0] - wall[2] >= -2)
        assertTrue("白墙不应泛暖发红（R-B=${wall[0] - wall[2]}，应 <=8）", wall[0] - wall[2] <= 8)
    }

    @Test
    fun saturationIsReduced() {
        val sky = sample(120, 170, 225)
        // 1.6.87 提饱和后蓝天 B-R≈+62（1.6.86≈+46），仍收敛不发艳蓝，上限放宽到 70。
        assertTrue("蓝天冷色偏应明显收敛（B-R=${sky[2] - sky[0]}，应 <=70）", sky[2] - sky[0] <= 70)
    }

    @Test
    fun warmSubjectsKeepTheirColor() {
        // 1.6.87 核心修复：1.6.86 的 sat=0.44 把橘子/花/黄果等暖色主体也去色，
        // 「色彩全无」。本版 sat=0.58 让主体饱和度回到 ≈0.40（成片锚点）。
        // 暖色样本饱和度下限设 0.38（1.6.87 ≈0.39~0.45、1.6.86 ≈0.33~0.39）。
        val orange = sample(220, 140, 70)
        val flower = sample(210, 90, 90)
        val fruit = sample(220, 180, 60)
        val so = saturation(orange); val sf = saturation(flower); val sy = saturation(fruit)
        assertTrue("橘子主体应保留色彩（sat=$so，应 >=0.38）", so >= 0.38f)
        assertTrue("红花主体应保留色彩（sat=$sf，应 >=0.36）", sf >= 0.36f)
        assertTrue("黄果主体应保留色彩（sat=$sy，应 >=0.38）", sy >= 0.38f)
    }

    @Test
    fun skinStaysCleanAndCool() {
        val out = sample(205, 168, 140)
        val rb = out[0] - out[2]
        // 1.6.87 提饱和但把 skinSatDelta 从 0.10 降到 0.02，肤色 R-B≈+38，
        // 仍明显暖调、不发青发脏，也不泛富士红（<=44），保留人像血色。
        assertTrue("肤色应白净清透但不发青发红（R-B=$rb，应在 28..44）", rb in 28..44)
    }
}
