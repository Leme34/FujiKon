package com.fujikon.app.dazz

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFM R 理光负片指纹回归（1.6.86 清冷白净 → 1.6.87 主体保色 → 1.6.88 真黄绿）。
 *
 * 用户 1.6.88 明确指出：反复多版「偏暖偏红」没达到要求，要的是「色调-30」带来的
 * 淡淡黄绿色。根因是此前把修图软件的「色调」(Tint，绿↔洋红) 负值误做成「加蓝青冷调」，
 * 又叠加肤色增粉嫩配比 (0.55,-0.15,0.45)，导致白墙 R>G>B、肤色 R-B=+38 始终偏红。
 * 1.6.88 按 Tint=-30 的正确语义重做：gcast/分离色调绿升蓝降、肤色配比中性偏绿、
 * skinSatDelta 负值单独去红，主体仍吃 sat=0.58 保持鲜亮。
 *
 * 本测试锁定「淡淡黄绿 + 肤色不发红 + 主体有色彩」指纹：
 *  - 中灰/白墙偏黄绿（G-B 为正、G≥R，B 不再最高）——「色调-30」核心标志；
 *  - 肤色去红但保留血色（R-B≈+28，不再 +38 泛富士红）；
 *  - 暖色主体（橘子/花/黄果）保持明显色彩（sat≈0.40，不回到「色彩全无」）；
 *  - 中灰亮度保持（不塌成灰蒙蒙）；
 *  - 蓝天冷色偏仍收敛、阴影不发暖褐。
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
    fun midGrayIsYellowGreenNotBlueCold() {
        // 「色调-30」核心：Tint 负 = 偏黄绿。中灰应 G 最高、B 不再最高
        // （1.6.88 G-B≈+3、B-R≈-1；旧版青冷是 G-B=-4、B-R=+4）。
        val out = sample(128, 128, 128)
        val gb = out[1] - out[2]
        val br = out[2] - out[0]
        assertTrue("中灰应偏黄绿而非青冷（G-B=$gb，应 >=1）", gb >= 1)
        assertTrue("中灰蓝通道不应最高（B-R=$br，应 <=0）", br <= 0)
    }

    @Test
    fun shadowsNotWarmBrown() {
        val out = sample(60, 70, 80)
        val br = out[2] - out[0]
        // 黄绿影调下阴影 B-R≈+8（仍非暖褐），下限放宽到 6 容纳绿升蓝降后的变化。
        assertTrue("阴影不应发暖褐（B-R=$br，应 >=6）", br >= 6)
        assertTrue("阴影不应过冷发蓝（B-R=$br，应 <=20）", br <= 20)
    }

    @Test
    fun midGrayKeepsBrightnessNotMuddy() {
        val out = sample(128, 128, 128)
        assertTrue("中灰应保持亮度不塌灰（luma=${luma(out)}，应 >=118）", luma(out) >= 118f)
        assertTrue("中灰仍应比原图略压暗（luma=${luma(out)}，应 <=124）", luma(out) <= 124f)
    }

    @Test
    fun whitesStayNeutralNotRed() {
        val wall = sample(235, 230, 225)
        // 白物中性略黄绿、不泛红（R-B≈+4），上界 8 防回到暖红。
        assertTrue("白墙不应泛暖发红（R-B=${wall[0] - wall[2]}，应 <=8）", wall[0] - wall[2] <= 8)
        assertTrue("白墙不应发青（R-B=${wall[0] - wall[2]}，应 >=0）", wall[0] - wall[2] >= 0)
    }

    @Test
    fun saturationIsReduced() {
        val sky = sample(120, 170, 225)
        assertTrue("蓝天冷色偏应收敛不发艳蓝（B-R=${sky[2] - sky[0]}，应 <=70）", sky[2] - sky[0] <= 70)
    }

    @Test
    fun warmSubjectsKeepTheirColor() {
        // 主体保色：sat=0.58 让橘子/花/黄果保持 ≈0.40，不回到「色彩全无」。
        val orange = sample(220, 140, 70)
        val flower = sample(210, 90, 90)
        val fruit = sample(220, 180, 60)
        val so = saturation(orange); val sf = saturation(flower); val sy = saturation(fruit)
        assertTrue("橘子主体应保留色彩（sat=$so，应 >=0.38）", so >= 0.38f)
        assertTrue("红花主体应保留色彩（sat=$sf，应 >=0.36）", sf >= 0.36f)
        assertTrue("黄果主体应保留色彩（sat=$sy，应 >=0.38）", sy >= 0.38f)
    }

    @Test
    fun skinIsDeReddenedButKeepsLife() {
        // 肤色去红：skinSatDelta=-0.14 把 R-B 从 +38 压到 ≈+28，
        // 仍明显暖调保留血色（>20），但不泛富士红（<34）。
        val out = sample(205, 168, 140)
        val rb = out[0] - out[2]
        assertTrue("肤色应去红不泛富士红（R-B=$rb，应 <34）", rb < 34)
        assertTrue("肤色应保留血色不发青（R-B=$rb，应 >20）", rb > 20)
    }
}
