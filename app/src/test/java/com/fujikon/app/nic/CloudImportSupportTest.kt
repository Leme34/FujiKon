package com.fujikon.app.nic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线「尼康官方云创滤镜」导入准入的单一真值源回归测试。
 *
 * 只有资料库附件「云创色彩预设.zip」里已落地官方真实 NP3 数据的 6 个 recipe
 * 允许导入；其余（拟合假 LUT / 合成 S 曲线 / 官方后续新增）一律「暂不支持导入」。
 */
class CloudImportSupportTest {

    @Test
    fun onlyRecipesWithOfficialRealNp3AreSupported() {
        assertEquals(6, CloudImportSupport.supportedIds.size)
        assertEquals(OfficialCloudLooks.BY_RECIPE_ID.keys, CloudImportSupport.supportedIds)
        listOf(
            "01JD1PCXH2HKADD09AZZ80CN30", // Hidamari Color <- hidanaei hanuka.NP3
            "01J6VDR8901GMQMP1JA12XZ2SX", // Warm Portrait <- warmportrait y2.NP3
            "01JSBM2ZVF6SX4VSEK76K1VGJQ", // Filmic <- filmic fabio.NP3
            "01JAWAKHQ8B1J3TXXQSNTQGC5H", // Textured Documentary <- textured docmntry.NP3
            "01JAWAKHJ0DG7KCBY6BNAW4271", // Nature <- nature links.NP3
            "01JSBM30XWQ6GG4XK0KSZ3FQEK"  // Vitality Film <- vitality film pman.NP3
        ).forEach { id ->
            assertTrue("$id 应支持导入", CloudImportSupport.isSupported(id))
        }
    }

    @Test
    fun fittedOrSyntheticRecipesAreNotSupported() {
        // Catgrain：只有 1.6.43 之前的统计拟合假 LUT，附件里没有对应真实 NP3。
        assertFalse(CloudImportSupport.isSupported("01K7JQEDMX051ZFVR4GW77D1DW"))
        // 在线目录里任意其它 recipe / 未知 id / 空 id。
        assertFalse(CloudImportSupport.isSupported("RECIPE-1"))
        assertFalse(CloudImportSupport.isSupported("UNKNOWN-RECIPE"))
        assertFalse(CloudImportSupport.isSupported(""))
        assertFalse(CloudImportSupport.isSupported("  "))
    }

    @Test
    fun supportMatchesTheCalibrationRealDataCondition() {
        // 准入判定必须与 CloudLookCalibration 使用真实 NP3 数据的条件严格一致，
        // 否则会出现「允许导入但套用假曲线」或「有真实数据却禁用导入」。
        CloudImportSupport.supportedIds.forEach { id ->
            assertTrue("$id 必须命中官方真实数据表", id in OfficialCloudLooks.BY_RECIPE_ID)
        }
        OfficialCloudLooks.BY_RECIPE_ID.keys.forEach { id ->
            assertTrue("$id 必须允许导入", CloudImportSupport.isSupported(id))
        }
        assertEquals("暂不支持导入", CloudImportSupport.UNSUPPORTED_LABEL)
        assertTrue(CloudImportSupport.UNSUPPORTED_HINT.contains("官方真实色彩数据"))
    }

    @Test
    fun futureCatalogEntriesAreUnsupportedByDefault() {
        // 官方云创目录动态新增、附件里没有对应真实 NP3 的方案自动落入
        // 「暂不支持」，无需维护黑名单。
        val futureIds = listOf(
            "01NEW00000000000000000001",
            "01JZZZZZZZZZZZZZZZZZZZZZZZ",
            "01K7JQEDMX051ZFVR4GW77D1DX"
        )
        assertTrue(futureIds.none { CloudImportSupport.isSupported(it) })
    }
}
