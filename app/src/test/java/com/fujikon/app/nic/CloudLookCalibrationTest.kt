package com.fujikon.app.nic

import org.junit.Assert.assertEquals
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
    fun calibratedRecipeUsesFittedLook() {
        val preset = presetFor("01JD1PCXH2HKADD09AZZ80CN30")

        val curve = preset.curve
        assertTrue("校准曲线必须是 256 点", curve != null && curve.size == 256)
        val table = curve!!
        for (i in 1 until table.size) {
            assertTrue("tone 曲线必须单调不减", table[i] >= table[i - 1])
        }
        // 拟合约束不允许把高光压到纯白，避免裁死。
        assertTrue("曲线顶端不得裁剪到 32767", table.last() <= 32767 - 200)
        assertTrue("曲线必须是有效映射", table.last() > 0)

        // 拟合约束：饱和度/色相不得越界（物理上合理，不靠极端参数刷分）。
        assertTrue("饱和度越界", preset.saturation in -50..35)
        assertTrue("色相越界", preset.hue in -33..33)
        assertEquals(8, preset.colorBlender.size)
        assertTrue("分区色相越界", preset.colorBlender.all { it.hue in -60..60 })
        assertTrue("分区彩度越界", preset.colorBlender.all { it.chroma in -45..45 })
        assertTrue("分区亮度越界", preset.colorBlender.all { it.brightness in -25..25 })
        // 曲线已是最终 LUT，五项影调必须在 LUT 之后归零。
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
