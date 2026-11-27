package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFM R 理光负片指纹回归（1.6.84 量化重做 → 1.6.85 去富士红 → 1.6.86 清冷白净）。
 *
 * 用户 1.6.86 提供 4 张清冷人像成片并指出 1.6.85「仍偏暖偏红、色差大」。
 * 白色锚点法量化：成片肤色 R-B≈+21（1.6.85 渲染 +32 偏红）、整体 sat≈0.189
 * （1.6.85 渲染 0.244 偏浓）、阴影 G-B≈+10 偏青、白物 R-B≈-1.5 中性略冷。
 * 1.6.86 把色偏改为「减红加蓝绿」、sat 降到 0.44、肤色提亮配比改中性偏冷，
 * 肤色色样 R-B 由 +49 压到 +34（仍明显暖调、不发青），中灰亮度保持 ~121（不塌）。
 * 本测试锁定「清冷白净、但通透不发灰」指纹，防止调回暖红/青蓝过度/灰蒙蒙：
 *  - 阴影转青（B-R 为正、不再暖褐）；
 *  - 中灰亮度保持（不清冷过头塌成灰蒙蒙）；
 *  - 白墙中性略冷、不泛黄不泛红；
 *  - 低饱和：蓝天冷色偏收敛；
 *  - 肤色白净清冷但不发青发脏（R-B 明显为正）。
 */
class OfmRetuneFingerprintTest {

    private fun sample(r: Int, g: Int, b: Int): IntArray {
        val packed = DazzFilmLut.sample(DazzFilmLut.get(DazzFilmLut.LOOK_OFM_R)!!, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    private fun luma(c: IntArray) = c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f

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
        assertTrue("蓝天冷色偏应明显收敛（B-R=${sky[2] - sky[0]}，应 <=70）", sky[2] - sky[0] <= 70)
    }

    @Test
    fun skinStaysCleanAndCool() {
        val out = sample(205, 168, 140)
        val rb = out[0] - out[2]
        // 1.6.86 清冷白净：R-B 由 1.6.85 的 +49 压到 +34（成片锚点 +21），
        // 仍明显暖调（>28）不发青发脏，保留人像血色。
        assertTrue("肤色应白净清冷但不发青（R-B=$rb，应在 28..42）", rb in 28..42)
    }
}
