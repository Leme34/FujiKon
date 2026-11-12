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
import org.junit.Assert.assertFalse
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
        // 1.6.47：99 个官方命名 NP3（nic:<recipeId>）+ 2 个独立 NP3 + 9 个 3D LUT。
        assertEquals(110, all.size)
        assertEquals(101, all.count { it.sourceName == CloudRecipeCatalog.SOURCE_NAME })
        assertEquals(9, all.count { it.sourceName == CloudLutCatalog.SOURCE_NAME })
        assertEquals(99, all.count { it.contentHash.startsWith("nic:") })
        assertEquals(2, all.count { it.contentHash.startsWith("cloudrecipe:") })
        assertEquals("contentHash 必须唯一", all.size, all.map { it.contentHash }.toSet().size)
        assertTrue(all.all { it.isSample })
        assertTrue(
            all.all {
                it.contentHash.startsWith("nic:") ||
                    it.contentHash.startsWith("cloudrecipe:") ||
                    it.contentHash.startsWith("cloudlut:")
            }
        )
    }

    @Test
    fun np3CurveDistributionMatchesCatalog() {
        // 99 个官方命名方案 + 2 个独立文件；按 sourceName 过滤，不受哈希前缀变化影响。
        val recipes = OfficialCloudPresets.all(context)
            .filter { it.sourceName == CloudRecipeCatalog.SOURCE_NAME }
        assertEquals(101, recipes.size)
        // 6 个重复缩写变体已合并（其中 1 个含曲线），代表优先保留含曲线者。
        val keptSpecs = CloudRecipeCatalog.ALL.filterNot {
            it.id in CloudOfficialNames.MERGED_INTO
        }
        assertEquals(101, keptSpecs.size)
        assertEquals(30, keptSpecs.count { it.hasCurve })
        assertEquals(
            "含真实 256 点曲线的 NP3 数量应与保留的 catalog 一致",
            keptSpecs.count { it.hasCurve },
            recipes.count { it.curve?.size == 256 }
        )
        assertEquals(71, recipes.count { it.curve == null })
        assertTrue(
            "全部保留的 NP3 都应带 8 分区官方真实数据",
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
        // 1.6.47 起该方案以官方名 + nic:<recipeId> 身份提供，与在线导入去重。
        val spec = CloudRecipeCatalog.ALL.first { it.name.contains("Vitality Film", true) }
        val hash = CloudOfficialNames.hashForSpec(spec.id, "cloudrecipe:" + spec.id)
        assertEquals("nic:01JSBM30XWQ6GG4XK0KSZ3FQEK", hash)
        val p = OfficialCloudPresets.all(context).first { it.contentHash == hash }
        assertEquals("Vitality Film by Peng Mango", p.name)
        assertTrue("说明里应保留缩写别名供搜索", p.description!!.contains("Vitality Film Pman"))
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
    fun officialMergedPresetsUseOfficialDescription() {
        val all = OfficialCloudPresets.all(context)
        // 命中官方映射的预置，说明应改用尼康官方接口的官方说明，而非本地生成的解析说明。
        val vitality = all.first { it.contentHash == "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK" }
        assertTrue("官方说明应包含官方描述内容", vitality.description!!.contains("模拟了胶片的风格"))
        assertFalse(
            "官方说明不应再带本地生成的 NP3 解析说明",
            vitality.description!!.contains("真实 NP3 导出")
        )
        // 缩写别名仍拼进说明尾，供旧缩写名搜索。
        assertTrue(vitality.description!!.contains("Vitality Film Pman"))
        // 2 个无官方对应的独立文件保留本地说明。
        val independent = all.filter { it.contentHash.startsWith("cloudrecipe:") }
        assertEquals(2, independent.size)
        independent.forEach { p ->
            assertTrue(
                "独立文件应在本地说明里保留 NP3 解析信息：${p.name}",
                p.description!!.contains("真实 NP3 导出")
            )
        }
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
