package com.fujikon.app.nic

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.PictureControlParser
import com.fujikon.app.render.CubeLut
import com.fujikon.app.render.CubeLutProvider
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.44 起只预置用户指定的 4 个精选云创官方真实 NP3，其余云创滤镜由用户在
 * 尼康云创官方滤镜自行下载导入。这里验证：
 *  - 预置集合恰好是这 4 个，且全部由官方 NP3 零拟合解析；
 *  - 完整 catalog / LUT 能力数据仍保留（供用户自行导入与在线校准使用）；
 *  - 3D LUT 解码、JSON 往返与亮度锚定渲染能力仍可用，只是不再预置；
 *  - 覆盖安装退役用的白名单 hash 集合正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfficialCloudPresetsTest {

    private lateinit var context: Context

    private val curatedNames = setOf(
        "Vitality Film Pman",
        "Nature Links",
        "Textured Docmntry",
        "Ht Harmony Hana"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OfficialCloudPresets.clearCache()
        CubeLutProvider.clearCache()
    }

    @After
    fun tearDown() {
        OfficialCloudPresets.clearCache()
        CubeLutProvider.clearCache()
        CubeLutProvider.install { _: String -> null }
    }

    @Test
    fun onlyFourCuratedOfficialPresetsAreSeeded() {
        val all = OfficialCloudPresets.all(context)
        assertEquals(4, all.size)
        assertEquals(curatedNames, all.map { it.name }.toSet())
        assertEquals("contentHash 必须唯一", all.size, all.map { it.contentHash }.toSet().size)
        assertTrue(all.all { it.isSample })
        assertTrue(all.all { it.contentHash.startsWith("cloudrecipe:") })
        assertTrue("预置里不应再有 3D LUT 方案", all.all { it.cubeLutAsset == null })
        assertEquals(
            OfficialCloudPresets.PRESET_CONTENT_HASHES,
            all.map { it.contentHash }.toSet()
        )
    }

    @Test
    fun curatedRecipesCarryOfficialNp3Parameters() {
        val byName = OfficialCloudPresets.all(context).associateBy { it.name }

        val vitality = byName.getValue("Vitality Film Pman")
        assertNull("vitality film pman.NP3 无独立曲线", vitality.curve)
        assertEquals(-4, vitality.contrast)
        assertEquals(54, vitality.highlights)
        assertEquals(-17, vitality.shadows)
        assertEquals(0, vitality.whites)
        assertEquals(-3, vitality.blacks)
        assertEquals(0, vitality.saturation)

        val nature = byName.getValue("Nature Links")
        assertNotNull("nature links.NP3 含官方 256 点真实曲线", nature.curve)
        assertEquals(256, nature.curve!!.size)
        assertEquals(18, nature.saturation)
        assertEquals(0, nature.contrast)

        val textured = byName.getValue("Textured Docmntry")
        assertNull(textured.curve)
        assertEquals(100, textured.contrast)
        assertEquals(54, textured.highlights)
        assertEquals(33, textured.shadows)
        assertEquals(-6, textured.blacks)

        val harmony = byName.getValue("Ht Harmony Hana")
        assertNull(harmony.curve)
        assertEquals(8, harmony.contrast)
        assertEquals(-27, harmony.highlights)
        assertEquals(13, harmony.shadows)
        assertEquals(-10, harmony.blacks)
        assertEquals(10, harmony.saturation)

        assertTrue(byName.values.all { it.colorBlender.size == 8 })
        assertTrue(byName.values.all { it.hasColorBlender })
        // 官方 NP3 的 Color Grading 并非每个方案都启用：vitality film pman
        // 三分区全有，textured docmntry 仅阴影分区有值，ht harmony hana 与
        // nature links 在官方文件里本就三分区全零。零拟合要求「不虚构数据」，
        // 因此按文件原值逐一断言，而不是一律要求 hasColorGrading。
        assertTrue(vitality.hasColorGrading)
        assertEquals(184, vitality.colorGrading.highlights.hue)
        assertEquals(348, vitality.colorGrading.shadows.hue)
        assertTrue(textured.hasColorGrading)
        assertEquals(340, textured.colorGrading.shadows.hue)
        assertEquals(0, textured.colorGrading.highlights.hue)
        assertEquals(0, textured.colorGrading.midtones.hue)
        assertFalse("ht harmony hana 官方 NP3 未启用三分区调色", harmony.hasColorGrading)
        assertFalse("nature links 官方 NP3 未启用三分区调色", nature.hasColorGrading)
        assertTrue(byName.values.all { it.description!!.contains("官方") })
    }

    @Test
    fun fullCatalogRemainsAvailableForUserImports() {
        // 不再预置 ≠ 删除能力：107 个真实 NP3 + 9 个 3D LUT 的目录与 assets
        // 仍在，用户自行下载导入 / 在线云创校准都依赖它们。
        assertEquals(107, CloudRecipeCatalog.ALL.size)
        assertEquals(9, CloudLutCatalog.ALL.size)
        assertEquals(31, CloudRecipeCatalog.ALL.count { it.hasCurve })
        assertTrue(CloudRecipeCatalog.ALL.map { it.name }.containsAll(curatedNames))
        CloudRecipeCatalog.ALL.forEach { spec ->
            val bytes = context.assets.open(spec.asset).use { it.readBytes() }
            val control = PictureControlParser.parse(bytes, spec.name)
            assertEquals("${spec.name} 应带官方 8 分区真实数据", 8, control.colorBlender.size)
        }
    }

    @Test
    fun retiredOfficialHashesAreExposedForUpgradeCleanup() {
        assertEquals(4, OfficialCloudPresets.PRESET_CONTENT_HASHES.size)
        assertTrue(
            "cloudlut: 前缀已全部退役",
            OfficialCloudPresets.PRESET_CONTENT_HASHES.none { it.startsWith("cloudlut:") }
        )
        assertFalse(
            "非精选 NP3 应被判定为退役",
            "cloudrecipe:recipe_001" in OfficialCloudPresets.PRESET_CONTENT_HASHES
        )
        assertTrue("cloudrecipe:recipe_103" in OfficialCloudPresets.PRESET_CONTENT_HASHES)
        assertEquals(6, OfficialCloudLooks.BY_RECIPE_ID.size)
    }

    @Test
    fun compiledOfficialLooksMatchTheSixMappedRecipes() {
        assertEquals(6, OfficialCloudLooks.BY_RECIPE_ID.size)
        val vitality = OfficialCloudLooks.BY_RECIPE_ID["01JSBM30XWQ6GG4XK0KSZ3FQEK"]!!
        assertNull(vitality.curve)
        assertEquals(-4, vitality.contrast)
        assertEquals(54, vitality.highlights)
        assertEquals(-17, vitality.shadows)
        assertEquals(-3, vitality.blacks)
        val hidamari = OfficialCloudLooks.BY_RECIPE_ID["01JD1PCXH2HKADD09AZZ80CN30"]!!
        assertNull(hidamari.curve)
        assertEquals(50, hidamari.contrast)
        assertEquals(90, hidamari.shadows)
        val warm = OfficialCloudLooks.BY_RECIPE_ID["01J6VDR8901GMQMP1JA12XZ2SX"]!!
        assertNotNull(warm.curve)
        assertEquals(256, warm.curve!!.size)
    }

    @Test
    fun lutAssetsDecodeTo33CubeAndLoadThroughProvider() {
        CubeLutProvider.clearCache()
        CloudLutCatalog.ALL.forEach { spec ->
            val bytes = context.assets.open(spec.asset).use { it.readBytes() }
            val lut = CubeLut.decode(bytes)
            assertNotNull("${spec.name} 的 FKL1 应能解码", lut)
            assertEquals(33, lut!!.size)
            assertEquals(33 * 33 * 33 * 3, lut.data.size)
        }
        CubeLutProvider.install { asset ->
            runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
        }
        val asset = CloudLutCatalog.ALL.first().asset
        val loaded = CubeLutProvider.get(asset)
        assertNotNull("渲染器应能通过 asset 路径加载 3D LUT", loaded)
        assertEquals(33, loaded!!.size)
    }

    @Test
    fun cubeLutAssetSurvivesJsonRoundTrip() {
        val p = Preset.none().copy(
            name = "LUT 往返",
            cubeLutAsset = CloudLutCatalog.ALL.first().asset
        )
        val restored = Preset.fromJson(p.toJson())
        assertEquals(p.cubeLutAsset, restored.cubeLutAsset)
        assertNull(
            "清空 3D LUT 路径后不应残留",
            Preset.fromJson(p.copy(cubeLutAsset = null).toJson()).cubeLutAsset
        )
    }

    @Test
    fun rendererStillApplies3dLutWithBrightnessAnchor() = runBlocking {
        val preset = Preset.none().copy(
            name = "LUT 渲染",
            cubeLutAsset = CloudLutCatalog.ALL.first().asset
        )
        CubeLutProvider.install { asset ->
            runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
        }

        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.rgb(128, 128, 128))
        source.setPixel(1, 0, Color.rgb(200, 120, 60))
        val output = ImageRenderer().render(source, preset, forExport = false)

        val gray = output.getPixel(0, 0)
        val colorPixel = output.getPixel(1, 0)
        // 中性灰严格恒等：LUT 不得提亮 / 发灰 / 偏色。
        assertEquals(128, (gray shr 16) and 0xFF)
        assertEquals(128, (gray shr 8) and 0xFF)
        assertEquals(128, gray and 0xFF)
        // 彩色像素应被官方 LUT 改变（证明 3D LUT 分支确实生效）。
        val changed = (colorPixel and 0xFFFFFF) != Color.rgb(200, 120, 60)
        assertTrue("官方 LUT 应改变彩色像素", changed)
        // 亮度被锚定，不能整体过曝。
        val lin = 0.299f * 200 + 0.587f * 120 + 0.114f * 60
        val r = (colorPixel shr 16) and 0xFF
        val g = (colorPixel shr 8) and 0xFF
        val b = colorPixel and 0xFF
        val lout = 0.299f * r + 0.587f * g + 0.114f * b
        assertTrue(
            "渲染后亮度应被锚定（±12，含其它画质算子），实际 $lout vs $lin",
            kotlin.math.abs(lout - lin) <= 12f
        )

        output.recycle()
        source.recycle()
    }
}
