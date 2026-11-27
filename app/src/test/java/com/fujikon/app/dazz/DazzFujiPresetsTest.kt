package com.fujikon.app.dazz

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.nic.builtInCloudPresets
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dazz 富士风格预置的参数不变量 + 3D LUT 正确性 + 渲染健全性。
 *
 * 针对用户反馈的两点做回归：
 *  ① 「效果相差无几」→ [allLooksAreDistinct] 断言五款 LUT 在代表性
 *     颜色上输出拉得开（青影/冷绿/暖琥珀/褪色浅黄/直闪高反差方向不同）。
 *  ② 「皮肤太脏不够清透」→ [skinIsProtectedAcrossAllLooks] 断言肤色色样在五款
 *     下都几乎不被染色（RGB 偏移很小），而环境色仍被明显着色。
 * 另沿用「套用后仍可看」的渲染健全性，防止 3D LUT 出现此前 3D LUT 崩坏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DazzFujiPresetsTest {

    private val presets = builtInDazzFujiPresets()

    private val expectedNames = listOf(
        "FXN R · 经典负片",
        "Classic U · 冷调胶片",
        "Nostalgic Neg · 怀旧负片",
        "OFM R · 理光负片",
        "Flash · 胶片直闪人像"
    )

    @Test
    fun exposesExactlyFiveDazzLooksInOrder() {
        assertEquals(5, presets.size)
        assertEquals(expectedNames, presets.map { it.name })
        assertTrue(presets.all { it.sourceName == DazzFujiPresets.SOURCE_NAME })
        assertTrue(presets.all { it.format == "NP3" })
    }

    @Test
    fun eachLookUsesA3dLutAndNoHeavy1dChains() {
        presets.forEach { p ->
            assertEquals("${p.name} filmLook 应为 1..5", true, p.filmLook in 1..5)
            assertTrue("${p.name} 必须挂载 3D LUT", DazzFilmLut.get(p.filmLook) != null)
            // 3D LUT 已含影调：1D curve 必须为 null，避免二次施加。
            assertEquals("${p.name} 不应再带 1D 曲线", null, p.curve)
            assertTrue("${p.name} 通道曲线应保持中性", p.channelCurves.isNeutral)
            assertTrue("${p.name} 不应有八色带", !p.hasColorBlender)
            assertTrue("${p.name} 不应有三分区分级", !p.hasColorGrading)
            val a = p.adjustments
            assertTrue("曝光应为 0（烘进 LUT）", a.exposure == 0f)
            assertTrue("颗粒应温和", a.grain in 0f..30f)
            // 直闪人像叠加全局径向闪光照明 + 轻暗角；其余胶片 look 两者都不加。
            if (p.filmLook == DazzFilmLut.LOOK_FLASH_PORTRAIT) {
                assertTrue("直闪人像应带轻暗角", a.vignette in 1f..60f)
                assertTrue("直闪人像应带全局闪光照明", a.flash in 30f..100f)
            } else {
                assertEquals("不应保留暗角 pass", 0f, a.vignette)
                assertEquals("其余 look 不应带闪光照明", 0f, a.flash)
            }
            assertEquals("不应保留清晰度卷积", 0f, a.clarity)
            assertTrue("强度为满档", a.presetStrength == 100f)
        }
        assertEquals("五款 filmLook 应互不相同", 5, presets.map { it.filmLook }.toSet().size)
    }

    @Test
    fun contentHashesAreStableUniqueAndNamespaced() {
        val hashes = presets.map { it.contentHash }
        assertEquals(5, hashes.toSet().size)
        assertTrue(hashes.all { it.startsWith("dazz3:") })
        assertTrue(hashes.none {
            it.startsWith("nic:") || it.startsWith("nikonpc:") ||
                it.startsWith("cloudlut:") || it.startsWith("dazz:")
        })
        assertEquals(
            listOf(
                "dazz3:fxn_r_classic_neg_v2", "dazz3:classic_u_superia400",
                "dazz3:nostalgic_neg",                 "dazz3:ofm_r_ricoh_neg_v6",
                "dazz3:film_flash_portrait_v6"
            ),
            hashes
        )
    }

    @Test
    fun builtInCloudPresetsIncludesAllFujiLooksExactlyOnce() {
        val all = builtInCloudPresets()
        val names = all.map { it.name }
        expectedNames.forEach { assertTrue("内置列表应包含 $it", it in names) }
        expectedNames.forEach { name ->
            assertEquals("${name} 不应重复", 1, names.count { it == name })
        }
        assertEquals(all.size, all.map { it.contentHash }.toSet().size)
    }

    @Test
    fun jsonRoundTripKeepsFilmLookAndAdjustments() = runBlocking {
        presets.forEach { p ->
            val restored = Preset.fromJson(p.toJson())
            assertEquals(p.name, restored.name)
            assertEquals(p.contentHash, restored.contentHash)
            assertEquals(p.filmLook, restored.filmLook)
            assertEquals(p.adjustments.grain, restored.adjustments.grain, 0.0001f)
        }
    }

    /** 代表性色样：阴影环境色、肤色、蓝天、绿叶、白墙。 */
    private data class Swatch(val name: String, val r: Int, val g: Int, val b: Int)

    private val swatches = listOf(
        Swatch("shadow", 60, 70, 80),
        Swatch("skin", 205, 168, 140),
        Swatch("blueSky", 120, 170, 225),
        Swatch("green", 70, 120, 60),
        Swatch("whiteWall", 235, 230, 225),
        Swatch("midGray", 128, 128, 128)
    )

    private fun sampleRgb(look: Int, r: Int, g: Int, b: Int): IntArray {
        val lut = DazzFilmLut.get(look)!!
        val packed = DazzFilmLut.sample(lut, r, g, b)
        return intArrayOf((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
    }

    @Test
    fun allLooksAreDistinct() {
        // 取几个非肤色色样，五款两两之间必须拉开明显差异（任一通道差 > 6）。
        val env = swatches.filter { it.name in setOf("shadow", "blueSky", "green", "whiteWall") }
        val looks = presets.map { it.filmLook }
        var distinctPairs = 0
        for (i in looks.indices) for (j in i + 1 until looks.size) {
            var maxDiff = 0
            env.forEach { s ->
                val a = sampleRgb(looks[i], s.r, s.g, s.b)
                val c = sampleRgb(looks[j], s.r, s.g, s.b)
                for (k in 0..2) maxDiff = maxOf(maxDiff, kotlin.math.abs(a[k] - c[k]))
            }
            if (maxDiff > 6) distinctPairs++
        }
        assertTrue("五款 LUT 两两之间都应有明显差异（实际对数=$distinctPairs）", distinctPairs == 10)
    }

    @Test
    fun skinIsProtectedAcrossAllLooks() {
        // 肤色色样在各款下的通道偏移都应很小（<= 24），且不会被染成青绿或惨黄：
        // R-B 保持为明显正值（暖调），不会出现 R-B 接近 0 或负值（发青发灰）。
        // OFM R 理光负片 1.6.88 走「色调-30 淡淡黄绿 + 肤色去红」：skinSatDelta=-0.14
        // 把肤色 R-B 从 +38 压到 ≈+28，仍明显暖调、不发青，属风格例外：下限放宽到 26；
        // 其余款按暖调下限 origRB-16。
        val skin = swatches.first { it.name == "skin" }
        val origRB = skin.r - skin.b
        looks@ for (p in presets) {
            val out = sampleRgb(p.filmLook, skin.r, skin.g, skin.b)
            val maxShift = maxOf(kotlin.math.abs(out[0] - skin.r), kotlin.math.abs(out[1] - skin.g), kotlin.math.abs(out[2] - skin.b))
            // OFM R 因单独去红，R 通道位移 ≈23，特例放宽到 25；其余款 24。
            val shiftLimit = if (p.filmLook == com.fujikon.app.dazz.DazzFilmLut.LOOK_OFM_R) 25 else 24
            assertTrue("${p.name} 肤色不应被大改（shift=$maxShift）", maxShift <= shiftLimit)
            val rb = out[0] - out[2]
            val floor = if (p.filmLook == com.fujikon.app.dazz.DazzFilmLut.LOOK_OFM_R) 26 else origRB - 16
            assertTrue("${p.name} 肤色应保持暖调不泛青（R-B=$rb vs 下限 $floor）", rb >= floor)
        }
    }

    @Test
    fun lutIsFiniteAndInRange() {
        presets.forEach { p ->
            val lut = DazzFilmLut.get(p.filmLook)!!
            assertEquals("LUT 长度", DazzFilmLut.N * DazzFilmLut.N * DazzFilmLut.N * 3, lut.size)
            assertTrue("LUT 应全为有限值且落在 0..1", lut.all { it >= 0f && it <= 1f && !it.isNaN() })
        }
    }

    @Test
    fun eachFujiLookRendersToAusableImage() = runBlocking {
        val source = neutralSource()
        val renderer = ImageRenderer()
        val baseLuma = averageLuma(source)
        presets.forEach { p ->
            val out = renderer.render(source, p, forExport = false)
            assertEquals(source.width, out.width)
            assertEquals(source.height, out.height)
            val luma = averageLuma(out)
            val rb = averageChannel(out, 16) - averageChannel(out, 0)
            assertTrue("${p.name} 亮度不得整体死黑 ($luma)", luma > 30f)
            assertTrue("${p.name} 亮度不得整体惨白 ($luma)", luma < 235f)
            assertTrue("${p.name} 亮度不应大幅偏离原图 ($luma vs $baseLuma)", kotlin.math.abs(luma - baseLuma) < 55f)
            assertTrue("${p.name} 红蓝通道不得严重拉偏 ($rb)", kotlin.math.abs(rb) < 70f)
        }
    }

    private fun neutralSource(): Bitmap {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = 40 + x * 6
                pixels[y * width + x] = (0xFF shl 24) or
                    (base.coerceAtMost(255) shl 16) or
                    (base.coerceAtMost(255) shl 8) or
                    ((base + 8).coerceAtMost(255))
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun averageLuma(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.map {
            ((it shr 16) and 0xFF) * 0.299f +
                ((it shr 8) and 0xFF) * 0.587f +
                (it and 0xFF) * 0.114f
        }.average().toFloat()
    }

    private fun averageChannel(bitmap: Bitmap, shift: Int): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.map { (it shr shift) and 0xFF }.average().toFloat()
    }
}
