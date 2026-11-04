package com.fujikon.app.nikonpc

import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NikonPcParsingTest {

    @Test
    fun parsesCatalogueOptionsAndDecodesEntities() {
        val html = """
            <html><body>
            <select id="selFileList">
              <option value="Agfa/PICCON01.NCP" title="Agfa Optima; Agfa/PICCON01.NCP">[01] Agfa Optima</option>
              <option value="B&amp;W/PICCON16.NCP" title="B&amp;W Contrast; B&amp;W/PICCON16.NCP">[16] B&amp;W Contrast</option>
              <option value="readme.txt" title="Not a preset">README</option>
              <option value="VH PseudoLog/ARRi LOGC - Neutral.NCP" title="ARRi LOGC - Neutral; VH PseudoLog/ARRi LOGC - Neutral.NCP">[OG] ARRi LOGC - Neutral</option>
            </select>
            </body></html>
        """.trimIndent()

        val presets = NikonPcCatalogParser.parse(html)

        assertEquals(3, presets.size)
        assertEquals("Agfa/PICCON01.NCP", presets[0].path)
        assertEquals("Agfa Optima", presets[0].name)
        assertEquals("Agfa", presets[0].category)
        assertEquals("01", presets[0].slot)
        assertEquals("B&W/PICCON16.NCP", presets[1].path)
        assertEquals("B&W Contrast", presets[1].name)
        assertEquals("B&W", presets[1].category)
        assertEquals("ARRi LOGC - Neutral", presets[2].name)
        assertEquals("VH PseudoLog", presets[2].category)
        assertEquals("OG", presets[2].slot)
    }

    @Test
    fun parsesAgfaMetadataAndBuildsTheSameFormAsTheWebsite() {
        val metadata = NikonPcMetadataParser.parse(agfaMetadata)

        assertEquals("Agfa Optima", metadata.name)
        assertEquals("Neutral", metadata.picCtrl)
        assertEquals("0", metadata.reserved02)
        assertTrue(metadata.customCurve)
        assertEquals("2", metadata.sharpening)
        assertEquals("0", metadata.saturation)
        assertEquals("0", metadata.hue)
        assertEquals("1.00", metadata.gamma)
        assertEquals(
            listOf(0 to 0, 38 to 36, 75 to 85, 176 to 194, 221 to 222, 255 to 255),
            metadata.points
        )

        val remote = NikonPcPreset(
            path = "Agfa/PICCON01.NCP",
            name = "Agfa Optima",
            slot = "01",
            category = "Agfa"
        )
        val form = NikonPcFormBuilder.build(metadata, remote, remote.name)

        assertEquals("Neutral", form["PicCtrlItem"])
        assertEquals("0", form["reserved02"])
        assertEquals("01", form["fileName"])
        assertEquals("Agfa Optima", form["RegistrationName"])
        assertEquals("0,0,38,36,75,85,176,194,221,222,255,255", form["points"])
        assertEquals("1", form["customCurveFlag"])
        assertEquals("1.00", form["gamma"])
        assertEquals("2", form["sharpening"])
        assertEquals("0", form["saturation"])
        assertEquals("0", form["hue"])
        assertEquals("80", form["selFilterEffects"])
        assertEquals("80", form["selToningType"])
        assertFalse(form.containsKey("toning"))
    }

    @Test
    fun monochromeMetadataKeepsFilterAndToningButDropsColourFields() {
        val metadata = NikonPcMetadataParser.parse(monochromeMetadata)
        val remote = NikonPcPreset(
            path = "B&W/PICCON16.NCP",
            name = "B&W Contrast",
            slot = "16",
            category = "B&W"
        )

        val form = NikonPcFormBuilder.build(metadata, remote, remote.name)

        assertEquals("Monochrome", form["PicCtrlItem"])
        assertEquals("83", form["selFilterEffects"])
        assertEquals("81", form["selToningType"])
        assertEquals("1", form["toning"])
        assertFalse(form.containsKey("saturation"))
        assertFalse(form.containsKey("hue"))
    }

    @Test
    fun handlesFalsePicCtrlAndApostrophesInNames() {
        val metadata = NikonPcMetadataParser.parse(apostropheAndFalseMetadata)
        val remote = NikonPcPreset(
            path = "PICCON12.NCP",
            name = "Photographio's_Cineflat",
            slot = "12",
            category = "NikonPC"
        )

        val form = NikonPcFormBuilder.build(metadata, remote, remote.name)

        assertEquals("Photographio's_Cineflat", metadata.name)
        assertEquals("false", metadata.picCtrl)
        assertEquals("false", form["PicCtrlItem"])
        assertEquals("12", form["fileName"])
        assertEquals("Photographio's_Cineflat", form["RegistrationName"])
        assertEquals("0", form["saturation"])
        assertEquals("0", form["hue"])
    }

    @Test
    fun autoFieldsKeepTheFallbackValuesTheWebsiteSubmits() {
        val metadata = NikonPcMetadataParser.parse(autoMetadata)
        val form = NikonPcFormBuilder.build(metadata)

        assertFalse(form.containsKey("customCurveFlag"))
        assertEquals("1", form["autoContrast"])
        assertEquals("0", form["contrast"])
        assertEquals("1", form["brightness"])
        assertEquals("1", form["autoSharpening"])
        assertEquals("0", form["sharpening"])
        assertEquals("1", form["autoSaturation"])
        assertEquals("0", form["saturation"])
        assertEquals("2", form["hue"])
    }

    @Test
    fun downloadSummaryPrefersAnEmbeddedDescription() {
        val control = sampleControl(baseProfile = BaseProfiles.NEUTRAL)
            .copy(description = "  Creator's   note  ")

        assertEquals("Creator's note", presetSummary(control))
    }

    @Test
    fun downloadSummaryDescribesTheDecodedParameters() {
        val control = sampleControl(baseProfile = BaseProfiles.NEUTRAL).copy(
            curve = IntArray(256) { it * 128 },
            sharpening = 2f,
            contrast = -5,
            saturation = -7,
            hue = 3
        )

        val summary = presetSummary(control)

        assertTrue(summary.startsWith("基于 自然 Neutral"))
        assertTrue(summary.contains("自定义曲线"))
        assertTrue(summary.contains("锐化 +2"))
        assertTrue(summary.contains("对比 -5"))
        assertTrue(summary.contains("饱和 -7"))
        assertTrue(summary.contains("色相 +3"))
    }

    @Test
    fun previewPhotoUrlIsReadFromTheHiddenImageInput() {
        val html = """
            <input id="inpImageUrl" type="text" value="https://nikonpc.com/img/500px/npc36168885c78a08e69e4469ed48018880.jpg"/>
        """.trimIndent()

        assertEquals(
            "https://nikonpc.com/img/500px/npc36168885c78a08e69e4469ed48018880.jpg",
            NikonPcCatalogParser.parsePreviewImageUrl(html)
        )
    }

    @Test
    fun previewPhotoUrlFallsBackToThePreviewImage() {
        val html = """
            <img id="imgPreview" src="https://nikonpc.com/img/500px/sample.jpg" class="img-preview"/>
        """.trimIndent()

        assertEquals(
            "https://nikonpc.com/img/500px/sample.jpg",
            NikonPcCatalogParser.parsePreviewImageUrl(html)
        )
    }

    @Test
    fun metadataSummaryDescribesTheSiteMetadata() {
        val summary = presetSummary(NikonPcMetadataParser.parse(monochromeMetadata))

        assertTrue(summary.startsWith("基于 单色 Monochrome"))
        assertTrue(summary.contains("自定义曲线"))
        assertTrue(summary.contains("锐化 +3"))
        assertTrue(summary.contains("单色滤镜"))
        assertTrue(summary.contains("色调"))
    }

    @Test
    fun searchAlsoMatchesTheSummary() {
        val agfa = NikonPcPreset("Agfa/PICCON01.NCP", "Agfa Optima", "01", "Agfa")
        val portrait = NikonPcPreset("NikonPC/PICCON09.NCP", "Warm Portrait", "09", "NikonPC")
        val state = com.fujikon.app.ui.viewmodel.NikonPcViewModel.UiState(
            catalog = listOf(agfa, portrait),
            query = "自然",
            descriptions = mapOf(portrait.path to "基于 自然 Neutral · 自定义曲线")
        )

        assertEquals(listOf(portrait), state.filteredCatalog)
    }

    private fun sampleControl(baseProfile: Int) = PictureControl(
        format = PictureControlFormat.NCP,
        version = "1.0",
        name = "Sample",
        baseProfile = baseProfile,
        baseProfileName = BaseProfiles.nameOf(baseProfile),
        curve = null
    )

    private val agfaMetadata = """
        {'filename':'PICCON01.NCP',
        'name':'Agfa Optima',
        'picCtrl':'Neutral',
        'reserved02':'0',
        'customCurveFlag':'1',
        'sharpening':'2',
        'contrast':null,
        'brightness':null,
        'splain':{'points':[['0',
        '0'],
        ['38',
        '36'],
        ['75',
        '85'],
        ['176',
        '194'],
        ['221',
        '222'],
        ['255',
        '255']],
        'gamma':'1.00',
        'minInput':'0',
        'maxInput':'255',
        'minOutput':'0',
        'maxOutput':'255',
        'lutPoints':[[0,
        0]],
        'splinePoints':[[0,
        0]]},
        'lut':{'lut':[0.000000,
        255.000000]},
        'lutSource':'0 255',
        'saturation':'0',
        'hue':'0'}
    """.trimIndent()

    private val monochromeMetadata = """
        {'filename':'PICCON16.NCP',
        'name':'B&W Contrast',
        'picCtrl':'Monochrome',
        'reserved02':'0',
        'customCurveFlag':'1',
        'sharpening':'3',
        'contrast':null,
        'brightness':null,
        'splain':{'points':[['0',
        '0'],
        ['128',
        '119'],
        ['255',
        '255']],
        'gamma':'1.00',
        'minInput':'0',
        'maxInput':'255',
        'minOutput':'0',
        'maxOutput':'255',
        'toning':'1'},
        'lut':{'lut':[0.000000,
        255.000000]},
        'lutSource':'0 255',
        'toningType':'81',
        'toning':'1',
        'filterEffect':'83'}
    """.trimIndent()

    private val apostropheAndFalseMetadata = """
        {'filename':'PICCON12.NCP',
        'name':'Photographio's_Cineflat',
        'picCtrl':false,
        'reserved02':'2',
        'customCurveFlag':'1',
        'sharpening':'5',
        'contrast':null,
        'brightness':null,
        'splain':{'points':[['0',
        '0'],
        ['255',
        '255']],
        'gamma':'1.00',
        'minInput':'0',
        'maxInput':'255',
        'minOutput':'0',
        'maxOutput':'255',
        'saturation':'0',
        'hue':'0'},
        'lut':{'lut':[0.000000,
        255.000000]},
        'lutSource':'0 255'}
    """.trimIndent()

    private val autoMetadata = """
        {'filename':'PICCON01.NCP',
        'name':'Auto Example',
        'picCtrl':'Standard',
        'reserved02':'2',
        'customCurveFlag':'0',
        'sharpening':'auto',
        'contrast':'auto',
        'brightness':'1',
        'splain':{'points':[['0',
        '0'],
        ['255',
        '255']],
        'gamma':'1.00',
        'minInput':'0',
        'maxInput':'255',
        'minOutput':'0',
        'maxOutput':'255'},
        'lut':{'lut':[0.000000,
        255.000000]},
        'lutSource':'0 255',
        'saturation':'auto',
        'hue':'2'}
    """.trimIndent()
}
