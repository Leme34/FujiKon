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
 *  ① 抬黑压白、暗部有细节、高光不死白，但反差仍硬于常规胶片 look；
 *  ② 阴影偏青绿、高光中性偏奶油（teal-shadow / neutral-highlight 分离）；
 *  ③ 肤色清透健康、不过分发红发橙，也不被青绿染色。
 *
 * 1.6.77 修正整体发红：中性高光不再刻意染暖，肤色提亮改中性三通道配比。
 * 这些断言确保新 look 复刻直闪质感且不泛红。
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

    /** 1.6.77：中性高光不泛红——去暖后 R 与 B 基本持平（不再偏橙黄）。 */
    @Test
    fun highlightsStayNeutralNotWarm() {
        val c = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 215, 215, 215)
        assertTrue(
            "中性高光不应偏暖发红（R-B=${c[0] - c[2]}，应 |R-B|<=6）",
            kotlin.math.abs(c[0] - c[2]) <= 6
        )
    }

    /** 中性物与白墙不应泛红——1.6.77 去掉全局暖偏后整体回归中性。 */
    @Test
    fun neutralsDontTurnRed() {
        val gray = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 120, 118, 112)
        assertTrue("中性灰不应泛红（R-B=${gray[0] - gray[2]}）", gray[0] - gray[2] <= 6)
        val wall = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 220, 218, 214)
        assertTrue("白墙不应泛红（R-B=${wall[0] - wall[2]}）", wall[0] - wall[2] <= 10)
    }

    /** 肤色保持健康暖调但不橙红：R-B 适中且不泛青，G 被提亮通透。 */
    @Test
    fun skinStaysCleanWithoutExcessiveRed() {
        val c = sample(DazzFilmLut.LOOK_FLASH_PORTRAIT, 205, 168, 140)
        assertTrue("肤色应保持暖调（R-B=${c[0] - c[2]}）", c[0] - c[2] > 30)
        assertTrue("肤色不应过度泛红/橙（R-B=${c[0] - c[2]}，应 <62）", c[0] - c[2] < 62)
        assertTrue("肤色应被提亮通透（G=${c[1]} vs 原 168）", c[1] >= 160)
        // 去红配比应把 R-G 从旧的 ~51 收到 ~36，肤色不再橙。
        assertTrue("肤色 R-G 应收敛（R-G=${c[0] - c[1]}，应 <45）", c[0] - c[1] < 45)
    }

    /**
     * 1.6.80 清透富士色彩：撤回 1.6.79 的「抬黑 matte + 降饱和 + 全局青蓝」，
     * 那套让白位压到 242、画面灰蒙蒙。现在白/中性区通透干净、环境色浓郁鲜艳，
     * 同时肤色/白墙仍不泛红。
     */
    @Test
    fun filmLooksClearAndSaturated() {
        val flash = DazzFilmLut.LOOK_FLASH_PORTRAIT
        // 清透：白墙/纯白比 1.6.79 灰蒙蒙版（222/242）明显更亮、不发灰。
        val wall = sample(flash, 220, 218, 214)
        assertTrue("白墙应通透（luma=${luma(wall)}，应 >226）", luma(wall) > 226f)
        val white = sample(flash, 255, 255, 255)
        assertTrue("纯白应清透不发灰（luma=${luma(white)}，应 >244）", luma(white) > 244f)
        // 浓郁富士色彩：绿色/蓝天饱和度明显高于灰蒙蒙版（草绿旧版 sat≈70）。
        val grass = sample(flash, 120, 150, 60)
        assertTrue("绿色应浓郁（sat=${grass.maxOrNull()!! - grass.minOrNull()!!}，应 >72）",
            grass.maxOrNull()!! - grass.minOrNull()!! > 72)
        val sky = sample(flash, 120, 160, 210)
        assertTrue("蓝天应通透鲜艳（B-R=${sky[2] - sky[0]}，应 >=85）", sky[2] - sky[0] >= 85)
        // 干净中性区：不残留全局青蓝染色（中灰 R-B 绝对值应很小）。
        val gray = sample(flash, 128, 128, 128)
        assertTrue("中灰应干净不发青（|R-B|=${kotlin.math.abs(gray[0] - gray[2])}，应 <=8）",
            kotlin.math.abs(gray[0] - gray[2]) <= 8)
        // 关键约束：清透/提艳不得让肤色或白墙泛红。
        val skin = sample(flash, 205, 168, 140)
        assertTrue("肤色仍不泛红（R-B=${skin[0] - skin[2]}）", skin[0] - skin[2] < 62)
        assertTrue("白墙仍不泛红（R-B=${wall[0] - wall[2]}）", wall[0] - wall[2] <= 10)
    }

    /**
     * 1.6.81 白布中性化：白裙/白衣等暖白布料不应被肤色逻辑染成暖粉，
     * 应与同画面中性白物一致；肤色与蓝色碎花不受影响。
     */
    @Test
    fun whiteClothStaysNeutralWithoutTintingSkin() {
        val flash = DazzFilmLut.LOOK_FLASH_PORTRAIT
        // 真实白裙色（暖白 237,228,226）：暖粉偏色应被中和（R-B 从 10 收敛到 <=5）。
        val skirt = sample(flash, 237, 228, 226)
        assertTrue("白裙不应偏暖粉（R-B=${skirt[0] - skirt[2]}，应 <=5）",
            skirt[0] - skirt[2] <= 5)
        // 中性白栏杆保持中性（不被反向染色）。
        val rail = sample(flash, 240, 241, 240)
        assertTrue("白色栏杆应保持中性（|R-B|=${kotlin.math.abs(rail[0] - rail[2])}，应 <=3）",
            kotlin.math.abs(rail[0] - rail[2]) <= 3)
        // 关键：肤色与蓝色碎花不受白布中性化影响。
        val skin = sample(flash, 205, 168, 140)
        assertTrue("肤色应保持暖调（R-B=${skin[0] - skin[2]}，应 >=50）", skin[0] - skin[2] >= 50)
        val blueFlower = sample(flash, 190, 205, 235)
        assertTrue("蓝色碎花应保蓝（B-R=${blueFlower[2] - blueFlower[0]}，应 >=38）",
            blueFlower[2] - blueFlower[0] >= 38)
    }
}
