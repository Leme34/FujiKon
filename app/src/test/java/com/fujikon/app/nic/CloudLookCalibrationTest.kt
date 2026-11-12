package com.fujikon.app.nic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLookCalibrationTest {

    private fun detail(id: String, saturation: String = "-10") = """
        {
          "recipe": {
            "id": "$id",
            "name": "Calibration Probe",
            "creatorName": "Nikon",
            "pictureControlName": "Probe",
            "recipeOutline": "",
            "recipeDetail": "probe",
            "pictureControl": {
              "settingName": "Probe",
              "baseName": "FLEXIBLE COLOR",
              "sharpening": "+0.5",
              "middleRangeSharp": "0",
              "clarity": "0",
              "contrast": "gamma",
              "saturation": "$saturation",
              "highlight": "gamma",
              "shadow": "gamma",
              "whiteLevel": "gamma",
              "blackLevel": "gamma"
            }
          }
        }
    """.trimIndent()

    private fun presetFor(id: String, saturation: String = "-10"): com.fujikon.app.data.Preset {
        val recipe = NikonCloudRecipe(id = id, name = "Calibration Probe")
        return recipe.toPreset(parseCloudDetail(detail(id, saturation)))
    }

    @Test
    fun calibratedRecipeUsesOfficialNp3Data() {
        val preset = presetFor("01JD1PCXH2HKADD09AZZ80CN30")

        // Hidamari Color 命中「云创色彩预设.zip」的 hidanaei hanuka.NP3（392 字节）：
        // 官方以参数 + 分区调色定义影调，没有独立色调曲线。绝不能用拟合 / 合成
        // S 曲线顶替，否则又回到「与官方效果图天壤之别」。
        assertNull("官方真实 NP3 无独立曲线", preset.curve)

        // 官方 NP3 原值（±100 口径），不再归零；detail 里的 saturation=-10 被覆盖。
        assertEquals(10, preset.saturation)
        assertEquals(0, preset.hue)
        assertEquals(50, preset.contrast)
        assertEquals(-5, preset.highlights)
        assertEquals(90, preset.shadows)
        assertEquals(-5, preset.whites)
        assertEquals(30, preset.blacks)

        // 官方 8 分区 Color Blender + 三分区 Color Grading 真实数据。
        assertEquals(8, preset.colorBlender.size)
        assertTrue("官方分区调色应带真实数据", preset.hasColorBlender)
        assertTrue("官方三分区 Grading 应带真实数据", preset.hasColorGrading)

        // 真实数据路径不覆盖锐化三档，仍沿用 toPictureControl 已按 0.30 缩放的
        // 结果，避免把官方未缩放档位（2.0f）直接喂给画质算子造成失配。
        assertEquals(0.15f, preset.sharpening, 1e-4f)
    }

    @Test
    fun officialRecipeWithRealCurveKeeps256PointToneTable() {
        // Warm Portrait <- warmportrait y2.NP3（978 字节，含官方 256 点真实曲线）
        val preset = presetFor("01J6VDR8901GMQMP1JA12XZ2SX")
        val curve = preset.curve
        assertNotNull("官方 NP3 应保留 256 点真实曲线", curve)
        assertEquals(256, curve!!.size)
        for (i in 1 until curve.size) {
            assertTrue("官方真实曲线单调不减", curve[i] >= curve[i - 1])
        }
        assertTrue("曲线顶端应在 0..32767 内", curve.last() in 1..32767)
        // 有独立曲线者，五项影调参数本就为 0；色彩风格来自曲线 + 分区。
        assertEquals(0, preset.contrast)
        assertEquals(0, preset.highlights)
        assertEquals(0, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(0, preset.blacks)
        assertEquals(8, preset.saturation)
        assertTrue("官方三分区 Grading 应带真实数据", preset.hasColorGrading)
    }

    @Test
    fun recipeWithoutOfficialNp3StillUsesFittedFallback() {
        // Catgrain：附件中无对应真实 NP3，保留 1.6.43 之前的统计拟合兜底，
        // 确保「真实数据改造」没有破坏老 recipe 的既有观感。
        val preset = presetFor("01K7JQEDMX051ZFVR4GW77D1DW")
        val curve = preset.curve
        assertNotNull("拟合兜底应提供 256 点曲线", curve)
        assertEquals(256, curve!!.size)
        for (i in 1 until curve.size) {
            assertTrue("拟合曲线单调不减", curve[i] >= curve[i - 1])
        }
        assertEquals(-10, preset.saturation)
        assertEquals(-8, preset.hue)
        assertEquals(8, preset.colorBlender.size)
        // 拟合曲线已含影调，五项仍归零。
        assertEquals(0, preset.contrast)
        assertEquals(0, preset.highlights)
        assertEquals(0, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(0, preset.blacks)
    }

    @Test
    fun unknownRecipeIsLeftUntouched() {
        val untouched = presetFor("UNKNOWN-RECIPE", saturation = "-5")
        val control = parseCloudDetail(detail("UNKNOWN-RECIPE", saturation = "-5"))
            .control
            .toPictureControl("Probe", "probe")

        assertEquals(-5, untouched.saturation)
        assertEquals(0, untouched.hue)
        // 云创锐化全局缩放：发布值 +0.5 -> 0.15（0.30 倍）
        assertEquals(0.15f, untouched.sharpening, 1e-4f)
        assertEquals(control.curve!!.toList(), untouched.curve!!.toList())
        assertTrue("未标定 recipe 不应注入分区校准", untouched.colorBlender.isEmpty())
    }
}
