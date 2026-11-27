package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.90 FXN R 经典负片「富士暖黄调」指纹回归。
 *
 * 1.6.83 版本是「减红加蓝」的青冷影调（gcast 蓝最高、阴影 +0.105 蓝、
 * lift 加红减绿），中灰 (110,107,115) 偏青蓝、绿植 R-B≈-12 泛青。用户反馈
 * 「红调不对，要恰到好处的黄调实现富士色彩」——问题本质是红+蓝=洋红青冷，
 * 缺富士标志性的暖黄。按 5 张 Dazz 成片量化：肤色 R-B≈+60、G-B≈+25（暖黄
 * 而非洋红），白物 R-B/G-B≈+5/+5，绿植 G-B≈+12，中性 G-B≈+4（全局绿>蓝）。
 *
 * 本测试锁定「中性暖黄 + 肤色暖而不洋红 + 绿植黄绿 + 影调不塌」指纹，
 * 防止后续调参重新做回青冷/洋红方向：
 *  - 中灰由青冷翻为暖黄（G-B 为正、B-R 为负）；
 *  - 白墙暖黄不泛青；
 *  - 肤色保持暖调、G-B 明显为正（黄绿而非洋红），且不泛红过头；
 *  - 阴影不再强青蓝；
 *  - 蓝天冷色偏收敛，不泛艳蓝。
 */
class FxnRetuneFingerprintTest {

    private fun sample(r: Int, g: Int, b: Int): IntArray {
        val packed = DazzFilmLut.sample(DazzFilmLut.get(DazzFilmLut.LOOK_FXN_R)!!, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    private fun luma(c: IntArray) = c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f

    @Test
    fun midGrayIsWarmYellowNotCoolTeal() {
        val out = sample(128, 128, 128)
        // 1.6.83 是 RB=-5/GB=-8（偏青蓝），1.6.90 翻正为 RB≈+4/GB≈+5（暖黄）。
        assertTrue("中灰应暖黄：G-B=${out[1] - out[2]}，应 >0", out[1] - out[2] > 0)
        assertTrue("中灰应不泛蓝：B-R=${out[2] - out[0]}，应 <0", out[2] - out[0] < 0)
        // 影调不动，中灰亮度保持（原 128 压暗到约 112），不塌成灰蒙蒙。
        assertTrue("中灰亮度应保持层次（luma=${luma(out)}，应 >105）", luma(out) > 105f)
    }

    @Test
    fun highlightsAreWarmYellowNotCool() {
        val wall = sample(235, 230, 225)
        // 成片白物 R-B≈+5、G-B≈+5：暖黄而非洋红/青冷。
        assertTrue("白墙应暖黄：R-B=${wall[0] - wall[2]}，应 >0", wall[0] - wall[2] > 0)
        assertTrue("白墙应绿>蓝：G-B=${wall[1] - wall[2]}，应 >=0", wall[1] - wall[2] >= 0)
        assertTrue("白墙不应泛红过头：R-B=${wall[0] - wall[2]}，应 <=12", wall[0] - wall[2] <= 12)
    }

    @Test
    fun skinIsWarmYellowNotMagenta() {
        val out = sample(205, 168, 140)
        val rb = out[0] - out[2]
        val gb = out[1] - out[2]
        // 成片肤色 R-B≈+60、G-B≈+25：暖黄而非「加红减绿」的洋红粉。
        assertTrue("肤色应保持暖调（R-B=$rb，应 >=45）", rb >= 45)
        assertTrue("肤色应暖黄而非洋红（G-B=$gb，应 >=18）", gb >= 18)
        assertTrue("肤色不应泛红过头（R-B=$rb，应 <=70）", rb <= 70)
        val shift = maxOf(
            kotlin.math.abs(out[0] - 205),
            kotlin.math.abs(out[1] - 168),
            kotlin.math.abs(out[2] - 140)
        )
        assertTrue("肤色不应被大改（shift=$shift，应 <=26）", shift <= 26)
    }

    @Test
    fun shadowsAreNotStronglyTeal() {
        val out = sample(60, 70, 80)
        // 1.6.83 阴影强青蓝 B-R>=20；1.6.90 收敛到 B-R≈+12（仅微风偏冷，不再泛青）。
        assertTrue("阴影不应强青蓝（B-R=${out[2] - out[0]}，应 <=16）", out[2] - out[0] <= 16)
    }

    @Test
    fun skyCoolCastIsConstrained() {
        val out = sample(120, 170, 225)
        // 蓝天仍冷，但不再像 1.6.83 的 B-R≈105 那样艳蓝（1.6.90 ≈73）。
        assertTrue("蓝天冷色偏应收敛（B-R=${out[2] - out[0]}，应 <=85）", out[2] - out[0] <= 85)
    }
}
