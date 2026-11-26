package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.83 FXN R 经典负片量化拟合指纹回归。
 *
 * 用户反馈初版 FXN-R「无法比拟 Dazz 成片」：偏亮、偏暖、青蓝影调不足。
 * 用原图—成片配对做色彩指纹拟合后，重做了整张 3D LUT。本测试锁定拟合目标：
 *  - 阴影压暗且偏青蓝（这是「青灰影调」的来源）；
 *  - 中性灰被整体压暗（初版几乎不压暗，是「偏亮不通透」的根因）；
 *  - 高光/白墙保持中性不泛暖（初版 high 偏暖，方向恰好相反）；
 *  - 肤色仍保持暖调清透，不被青蓝影调染脏。
 * 防止后续调参再次把这些方向调反。
 */
class FxnRetuneFingerprintTest {

    private fun sample(r: Int, g: Int, b: Int): IntArray {
        val packed = DazzFilmLut.sample(DazzFilmLut.get(DazzFilmLut.LOOK_FXN_R)!!, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    private fun luma(c: IntArray) = c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f

    @Test
    fun shadowsAreDeepAndTeal() {
        val out = sample(60, 70, 80)
        val br = out[2] - out[0]
        assertTrue("阴影应偏青蓝（B-R=$br，应 >=20）", br >= 20)
        assertTrue("阴影应压暗不死灰（luma=${luma(out)}，应 <58）", luma(out) < 58f)
    }

    @Test
    fun midGrayIsPressedDownForDepth() {
        val out = sample(128, 128, 128)
        assertTrue("中灰应整体压暗（luma=${luma(out)}，原 128，应 <118）", luma(out) < 118f)
    }

    @Test
    fun highlightsStayNeutralNotWarm() {
        val wall = sample(235, 230, 225)
        assertTrue("白墙应保持中性不偏暖（R-B=${wall[0] - wall[2]}，应 <=8）", wall[0] - wall[2] <= 8)
        val nearWhite = sample(220, 220, 220)
        assertTrue("高光不应泛暖发红（R-B=${nearWhite[0] - nearWhite[2]}，应 <=6）",
            nearWhite[0] - nearWhite[2] <= 6)
    }

    @Test
    fun skinStaysWarmAndCleanNotMuddy() {
        val out = sample(205, 168, 140)
        val rb = out[0] - out[2]
        assertTrue("肤色应保持暖调（R-B=$rb，应 >=45）", rb >= 45)
        val shift = maxOf(kotlin.math.abs(out[0] - 205), kotlin.math.abs(out[1] - 168), kotlin.math.abs(out[2] - 140))
        assertTrue("肤色不应被大改（shift=$shift，应 <=24）", shift <= 24)
    }

    @Test
    fun skyKeepsStrongCoolCast() {
        val out = sample(120, 170, 225)
        assertTrue("蓝天应保持强冷色偏（B-R=${out[2] - out[0]}，应 >=80）", out[2] - out[0] >= 80)
    }
}
