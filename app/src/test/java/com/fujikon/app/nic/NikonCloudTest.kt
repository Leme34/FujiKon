package com.fujikon.app.nic

import com.fujikon.app.ncp.BaseProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NikonCloudTest {

    private val catalogJson = """
        {
          "recipe": [
            {
              "id": "RECIPE-1",
              "name": "Mate Film by Choi matae",
              "picture_controll_name": "MateFilm_Choi matae",
              "registration_date": "2026-10-14T04:54:42+00:00",
              "is_new": true,
              "img": { "url": "https://example.com/thumb1.jpg" }
            },
            {
              "id": "",
              "name": "Broken entry"
            },
            {
              "id": "RECIPE-2",
              "name": "CineBias BleachBypass by RED",
              "picture_controll_name": "CineBias_RED",
              "registration_date": "2026-09-02T00:00:00+00:00",
              "img": { "url": "" }
            }
          ]
        }
    """.trimIndent()

    private val detailJson = """
        {
          "authority": {},
          "recipe": {
            "id": "RECIPE-1",
            "name": "Mate Film by Choi matae",
            "creatorName": "Nikon",
            "pictureControlName": "MateFilm_Choi matae",
            "recipeOutline": "",
            "recipeDetail": "创作者的说明\n这款色彩方案基于我的个人摄影风格而打造，突出绿色调、红色调与柔美肤色。它将呈现如胶片质感般柔和细腻的渐变效果。后续还有更多说明文字不应出现在列表里。",
            "camera": "NIKON Z f",
            "lens": "NIKKOR Z 50mm f/1.4",
            "thumbnail": "https://example.com/detail.jpg",
            "pictureControl": {
              "settingName": "MateFilm_Choi matae",
              "baseName": "FLEXIBLE COLOR",
              "quickSharp": "-",
              "sharpening": "+0.75",
              "middleRangeSharp": "+0.25",
              "clarity": "-0.50",
              "contrast": "gamma",
              "saturation": "-7",
              "highlight": "gamma",
              "shadow": "gamma",
              "whiteLevel": "gamma",
              "blackLevel": "gamma"
            }
          }
        }
    """.trimIndent()

    @Test
    fun catalogParsingSkipsInvalidEntries() {
        val catalog = parseCloudCatalog(catalogJson)

        assertEquals(2, catalog.size)
        assertEquals("RECIPE-1", catalog[0].id)
        assertEquals("MateFilm_Choi matae", catalog[0].pictureControlName)
        assertTrue(catalog[0].isNew)
        assertEquals("https://example.com/thumb1.jpg", catalog[0].thumbnailUrl)
        assertNull(catalog[1].thumbnailUrl)
        assertEquals(0, parseCloudCatalog("{}").size)
    }

    @Test
    fun detailParsingMapsPublicPictureControlFields() {
        val detail = parseCloudDetail(detailJson)

        assertEquals("NIKON Z f", detail.camera)
        assertEquals("NIKKOR Z 50mm f/1.4", detail.lens)
        assertEquals("https://example.com/detail.jpg", detail.thumbnailUrl)
        assertEquals("FLEXIBLE COLOR", detail.control.baseName)
        assertEquals(0.75f, detail.control.sharpening, 0.001f)
        assertEquals(0.25f, detail.control.middleRangeSharp, 0.001f)
        assertEquals(-0.50f, detail.control.clarity, 0.001f)
        assertEquals(-7, detail.control.saturation)
        assertNull(detail.control.contrast)
        assertNull(detail.control.highlight)
        assertEquals(
            "这款色彩方案基于我的个人摄影风格而打造，突出绿色调、红色调与柔美肤色。",
            detail.brief
        )
        assertTrue(detail.note.startsWith("创作者的说明"))
    }

    @Test
    fun recipeBecomesAnImportedPreset() {
        val recipes = parseCloudCatalog(catalogJson)
        val detail = parseCloudDetail(detailJson)
        val preset = recipes[0].toPreset(detail)

        assertEquals("Mate Film by Choi matae", preset.name)
        assertEquals("nic:RECIPE-1", preset.contentHash)
        assertEquals(BaseProfiles.FLEXIBLE_COLOR, preset.baseProfile)
        assertEquals(0.75f, preset.sharpening, 0.001f)
        assertEquals(-7, preset.saturation)
        assertEquals(0, preset.contrast)
        assertTrue("云创预设应生成模拟色调曲线", preset.hasCurve)
        assertTrue(preset.isDerived)
        assertTrue(preset.description!!.contains("胶片质感"))
    }

    @Test
    fun briefPrefersOutlineAndShortensLongText() {
        assertEquals("官方简介", cloudBrief(" 官方简介 ", "创作者的说明\n正文"))
        val longText = "创作者的说明\n" + "很长的说明文字".repeat(30)
        val brief = cloudBrief("", longText)
        assertTrue(brief.length <= 97)
        assertTrue(brief.endsWith("…"))
    }
}
