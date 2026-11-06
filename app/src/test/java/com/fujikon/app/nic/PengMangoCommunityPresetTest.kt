package com.fujikon.app.nic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 社区近似预置的参数边界与列表接入。 */
class PengMangoCommunityPresetTest {

    private val preset = PengMangoCommunityPreset.toPreset()

    @Test
    fun identityIsStableAndNonOfficial() {
        assertEquals("pmango_style_approx", PengMangoCommunityPreset.ID)
        assertEquals("local:pmango_style_approx", PengMangoCommunityPreset.CONTENT_HASH)
        assertTrue(preset.name.contains("社区近似"))
        assertTrue(preset.description?.contains("非官方") == true)

        val listed = builtInCommunityPresets()
        assertEquals(1, listed.size)
        assertTrue(listed.any { it.contentHash == PengMangoCommunityPreset.CONTENT_HASH })
        // 与官方条目各自独立，不共享内容哈希。
        assertFalse(listed.any { it.id == "01JSBM30XWQ6GG4XK0KSZ3FQEK" })
    }

    @Test
    fun curveIsMonotonicAndNotClipped() {
        val curve = preset.curve
        assertTrue("tone 曲线必须是 256 点", curve != null && curve.size == 256)
        val table = curve!!
        for (i in 1 until table.size) {
            assertTrue("tone 曲线必须单调不减", table[i] >= table[i - 1])
        }
        assertTrue("曲线必须在 0..32767 内", table.all { it in 0..32767 })
        assertTrue("曲线不能整体压成黑", table.last() > 20000)
    }

    @Test
    fun shoulderNeverLiftsHighlights() {
        // 0.85 以上必须单调压缩：斜率 <= 1（导出单位下即每级 +128.5，取整后 129）。
        // 白点收敛到 0.94 附近，最右端绝不能等效抬升到 1.0，否则高光会被推爆。
        val table = preset.curve!!
        val shoulderStart = 217          // 0.85 * 255
        val maxStep = 129                // ceil(32767 / 255)
        for (i in shoulderStart + 1 until table.size) {
            val step = table[i] - table[i - 1]
            assertTrue("高光肩部出现抬升：index=$i step=$step", step in 0..maxStep)
        }
        val whitePoint = table.last() / 32767.0
        assertTrue("白点不得高于 0.94，实际 $whitePoint", whitePoint <= 0.94)
    }

    @Test
    fun saturationAndHueStayInVisualSafeBounds() {
        // 拟合把色相限制在 ±18°，避免人像肤色偏绿；Preset.hue = 度数 / 0.3。
        assertTrue("饱和度越界", preset.saturation in -40..35)
        assertTrue("色相越界", preset.hue in -60..60)
    }

    @Test
    fun colorBlenderIsDisabledAfterVisualReview() {
        assertEquals(8, preset.colorBlender.size)
        assertTrue("分区必须全零", preset.colorBlender.all {
            it.hue == 0 && it.chroma == 0 && it.brightness == 0
        })
        assertFalse(preset.hasColorBlender)
    }

    @Test
    fun toneAdjustmentsAreBakedIntoCurve() {
        assertEquals(0, preset.contrast)
        assertEquals(0, preset.brightness)
        assertEquals(0, preset.highlights)
        assertEquals(0, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(0, preset.blacks)
    }
}
