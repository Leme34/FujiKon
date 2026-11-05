package com.fujikon.app.nic

import com.fujikon.app.ncp.BaseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NikonCloudContrastTest {

    private val catalogJson = """
        {
          "recipe": [
            {
              "id": "RECIPE-CONTRAST",
              "name": "High Contrast",
              "picture_controll_name": "HighContrast",
              "registration_date": "2026-10-14T04:54:42+00:00",
              "is_new": false,
              "img": { "url": "https://example.com/thumb1.jpg" }
            }
          ]
        }
    """.trimIndent()

    private val detailJson = """
        {
          "authority": {},
          "recipe": {
            "id": "RECIPE-CONTRAST",
            "name": "High Contrast",
            "creatorName": "Nikon",
            "pictureControlName": "HighContrast",
            "recipeOutline": "",
            "recipeDetail": "创作者的说明\n这款色彩方案强调强烈的对比度效果。",
            "camera": "NIKON Z f",
            "lens": "NIKKOR Z 50mm f/1.4",
            "thumbnail": "https://example.com/detail.jpg",
            "pictureControl": {
              "settingName": "HighContrast",
              "baseName": "FLEXIBLE COLOR",
              "quickSharp": "+",
              "sharpening": "+1.0",
              "middleRangeSharp": "+0.5",
              "clarity": "-0.5",
              "contrast": "gamma",
              "saturation": "-5",
              "highlight": "gamma",
              "shadow": "gamma",
              "whiteLevel": "gamma",
              "blackLevel": "gamma"
            }
          }
        }
    """.trimIndent()

    @Test
    fun testCloudContrastGeneratesVisibleEffects() {
        val recipes = parseCloudCatalog(catalogJson)
        val detail = parseCloudDetail(detailJson)
        val preset = recipes[0].toPreset(detail)

        // Should have a visible contrast effect (not zero)
        assertEquals("High Contrast", preset.name)
        assertEquals("nic:RECIPE-CONTRAST", preset.contentHash)
        assertEquals(BaseProfiles.FLEXIBLE_COLOR, preset.baseProfile)
        assertEquals(-5, preset.saturation)
        // The contrast should now be 0 (since "gamma" maps to null)
        assertEquals(0, preset.contrast)
        assertTrue("云创预设应生成模拟色调曲线", preset.hasCurve)
        assertTrue(preset.description!!.contains("强烈的对比度效果"))
    }
}