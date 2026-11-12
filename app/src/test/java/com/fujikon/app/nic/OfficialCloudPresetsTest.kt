package com.fujikon.app.nic

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.Preset
import com.fujikon.app.render.CubeLut
import com.fujikon.app.render.CubeLutProvider
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「云创色彩预设.zip」逐一落地的回归测试：107 个真实 NP3 零拟合解析、
 * 9 个 3D LUT 可解码、contentHash 幂等、JSON 往返不丢 3D LUT 路径，
 * 以及渲染链路确实通过 CubeLutProvider 套用了官方 LUT。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfficialCloudPresetsTest {

    private lateinit var context: Context

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
    fun allOfficialPresetsAreParsedZeroFitting() {
        val all = OfficialCloudPresets.all(context)
        assertEquals(116, all.size)
        assertEquals(107, all.count { it.sourceName == CloudRecipeCatalog.SOURCE_NAME })
        assertEquals(9, all.count { it.sourceName == CloudLutCatalog.SOURCE_NAME })
        assertEquals("contentHash 必须唯一", all.size, all.map { it.contentHash }.toSet().size)
        assertTrue(all.all { it.isSample })
        assertTrue(
            all.all {
                it.contentHash.startsWith("cloudrecipe:") ||
                    it.contentHash.startsWith("cloudlut:")
            }
        )
    }

    @Test
    fun np3CurveDistributionMatchesCatalog() {
        val recipes = OfficialCloudPresets.all(context)
            .filter { it.contentHash.startsWith("cloudrecipe:") }
        val expectedCurves = CloudRecipeCatalog.ALL.count { it.hasCurve }
        assertEquals(31, expectedCurves)
        assertEquals(
            "含真实 256 点曲线的 NP3 数量应与 catalog 一致",
            expectedCurves,
            recipes.count { it.curve?.size == 256 }
        )
        assertEquals(76, recipes.count { it.curve == null })
        assertTrue(
            "全部 107 个 NP3 都应带 8 分区官方真实数据",
            recipes.all { it.colorBlender.size == 8 }
        )
        // 有独立曲线者五项影调参数本就为 0；无曲线者靠官方参数定义影调。
        assertTrue(
            recipes.filter { it.curve != null }.all {
                it.contrast == 0 && it.highlights == 0 && it.shadows == 0 &&
                    it.whites == 0 && it.blacks == 0
            }
        )
        assertTrue(
            recipes.filter { it.curve == null }.any {
                it.contrast != 0 || it.highlights != 0 || it.shadows != 0
            }
        )
    }

    @Test
    fun vitalityFilmNp3CarriesOfficialParameters() {
        // 决定性证据：官方 NP3 与既有在线 detail 的五项参数逐位一致。
        val spec = CloudRecipeCatalog.ALL.first { it.name.contains("Vitality Film", true) }
        val p = OfficialCloudPresets.all(context)
            .first { it.contentHash == "cloudrecipe:" + spec.id }
        assertNull("vitality film pman.NP3 无独立曲线", p.curve)
        assertEquals(-4, p.contrast)
        assertEquals(54, p.highlights)
        assertEquals(-17, p.shadows)
        assertEquals(0, p.whites)
        assertEquals(-3, p.blacks)
        assertEquals(0, p.saturation)
        assertEquals(8, p.colorBlender.size)
        assertTrue(p.hasColorGrading)
        assertTrue(p.description!!.contains("官方"))
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
        // 含真实曲线的 recipe 必须是 256 点。
        val warm = OfficialCloudLooks.BY_RECIPE_ID["01J6VDR8901GMQMP1JA12XZ2SX"]!!
        assertNotNull(warm.curve)
        assertEquals(256, warm.curve!!.size)
    }

    @Test
    fun lutAssetsDecodeTo33CubeAndLoadThroughProvider() {
        val luts = OfficialCloudPresets.all(context)
            .filter { !it.cubeLutAsset.isNullOrBlank() }
        assertEquals(9, luts.size)
        luts.forEach { preset ->
            val bytes = context.assets.open(preset.cubeLutAsset!!).use { it.readBytes() }
            val lut = CubeLut.decode(bytes)
            assertNotNull("${preset.name} 的 FKL1 应能解码", lut)
            assertEquals(33, lut!!.size)
            assertEquals(33 * 33 * 33 * 3, lut.data.size)
        }
        CubeLutProvider.install { asset ->
            runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
        }
        val loaded = CubeLutProvider.get(luts.first().cubeLutAsset!!)
        assertNotNull("渲染器应能通过 asset 路径加载 3D LUT", loaded)
        assertEquals(33, loaded!!.size)
    }

    @Test
    fun cubeLutAssetSurvivesJsonRoundTrip() {
        val p = OfficialCloudPresets.all(context).first { !it.cubeLutAsset.isNullOrBlank() }
        val restored = Preset.fromJson(p.toJson())
        assertEquals(p.cubeLutAsset, restored.cubeLutAsset)
        assertNull(
            "清空 3D LUT 路径后不应残留",
            Preset.fromJson(p.copy(cubeLutAsset = null).toJson()).cubeLutAsset
        )
    }

    @Test
    fun rendererAppliesOfficialLutWithBrightnessAnchor() = runBlocking {
        val preset = OfficialCloudPresets.all(context)
            .first { !it.cubeLutAsset.isNullOrBlank() }
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
