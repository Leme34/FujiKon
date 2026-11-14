package com.fujikon.app.nic

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * contentHash 幂等、官方说明与别名、以及编译官方 look 与曲线一致性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfficialCloudPresetsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OfficialCloudPresets.clearCache()
    }

    @After
    fun tearDown() {
        OfficialCloudPresets.clearCache()
    }

    @Test
    fun allOfficialPresetsAreParsedZeroFitting() {
        val all = OfficialCloudPresets.all(context)
        // 1.6.60：移除 9 个 3D LUT 方案后，仅余 101 个官方 NP3。
        assertEquals(101, all.size)
        assertEquals(101, all.count { it.sourceName == CloudRecipeCatalog.SOURCE_NAME })
        assertEquals(99, all.count { it.contentHash.startsWith("nic:") })
        assertEquals(2, all.count { it.contentHash.startsWith("cloudrecipe:") })
        assertEquals("contentHash 必须唯一", all.size, all.map { it.contentHash }.toSet().size)
        assertTrue(all.all { it.isSample })
        assertTrue(
            all.all {
                it.contentHash.startsWith("nic:") ||
                    it.contentHash.startsWith("cloudrecipe:")
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

}
