package com.fujikon.app.nic

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.PresetSearch
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
 * 1.6.47 云创官方身份统一回归：缩写 NP3 ↔ 官方 recipe 一一对应后，
 * 预置使用官方全称与 `nic:<recipeId>`（和在线导入共用同一 contentHash），
 * 因此导入同名官方滤镜不再出现两个；覆盖安装迁移保留用户状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudOfficialIdentityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        OfficialCloudPresets.clearCache()
    }

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
        OfficialCloudPresets.clearCache()
    }

    @Test
    fun mappingTableCovers99OfficialRecipesAndIsInternallyConsistent() {
        val table = CloudOfficialNames.BY_SPEC_ID
        assertEquals(99, table.size)
        assertEquals(99, table.values.map { it.recipeId }.toSet().size)
        assertEquals(99, CloudOfficialNames.SPEC_BY_RECIPE_ID.size)
        // 每个代表 spec 都能对应到一个真实 catalog 条目与 asset。
        val catalogIds = CloudRecipeCatalog.ALL.map { it.id }.toSet()
        assertTrue(table.keys.all { it in catalogIds })
        assertTrue(table.values.all { it.specId == table.entries.first { e -> e.value == it }.key })
        // 6 个重复缩写变体都指向表中存在的代表。
        assertEquals(6, CloudOfficialNames.MERGED_INTO.size)
        assertTrue(CloudOfficialNames.MERGED_INTO.values.all { it in table })
        // 2 个无官方对应的文件保持独立。
        assertEquals(2, CloudOfficialNames.INDEPENDENT_IDS.size)
        assertTrue(CloudOfficialNames.INDEPENDENT_IDS.all { it !in table })
        assertTrue(CloudOfficialNames.INDEPENDENT_IDS.all {
            CloudOfficialNames.hashForSpec(it, "cloudrecipe:$it") == "cloudrecipe:$it"
        })
    }

    @Test
    fun officialPresetsUseOfficialNamesAndCloudImportHash() {
        val all = OfficialCloudPresets.all(context)
        val official = all.filter { it.contentHash.startsWith("nic:") }
        assertEquals(99, official.size)
        official.forEach { p ->
            val recipeId = p.contentHash.removePrefix("nic:")
            val identity = CloudOfficialNames.SPEC_BY_RECIPE_ID[recipeId]?.let {
                CloudOfficialNames.BY_SPEC_ID[it]
            }
            assertNotNull("每个 nic: 哈希都应来自官方映射表", identity)
            assertEquals("名称必须是官方全称", identity!!.officialName, p.name)
            assertTrue(
                "说明里必须保留缩写别名供搜索：${p.name}",
                p.description!!.contains(identity.aliases.first())
            )
        }
        // 与在线导入的 contentHash() 完全一致 —— 这是「不再出现两个」的关键。
        val sample = official.first { it.contentHash == "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK" }
        assertEquals(
            NikonCloudRecipe(id = "01JSBM30XWQ6GG4XK0KSZ3FQEK", name = sample.name).contentHash(),
            sample.contentHash
        )
    }

    @Test
    fun abbreviationsRemainSearchableThroughAliases() {
        val all = OfficialCloudPresets.all(context)
        // 用户仍可用旧缩写名搜到官方全称的预置。
        assertTrue(PresetSearch.filter(all, "vitality film pman").any {
            it.name == "Vitality Film by Peng Mango"
        })
        assertTrue(PresetSearch.filter(all, "textured docmntry").any {
            it.name == "Textured Documentary by MediaStorm"
        })
        assertTrue(PresetSearch.filter(all, "NEGA CYBU 01A").any {
            it.name == "NegativeFilm Cyan&Blue 01a"
        })
    }

    @Test
    fun mergedVariantsDoNotProduceDuplicatePresets() {
        val all = OfficialCloudPresets.all(context)
        // 每个官方 recipeId 只出现一次。
        val nicHashes = all.map { it.contentHash }.filter { it.startsWith("nic:") }
        assertEquals(nicHashes.size, nicHashes.toSet().size)
        // 被合并变体的缩写名仍作为别名保留在代表说明里（不丢搜索能力）。
        val glow = all.first { it.name == "Glow by Ian Harland" }
        assertTrue(glow.description!!.contains("GLOW LANHARLAND"))
        assertTrue(glow.description!!.contains("glow lan harland"))
        // 2 个无官方对应的独立文件仍在，且保持原 cloudrecipe 身份与缩写名。
        val independent = all.filter { it.contentHash.startsWith("cloudrecipe:") }
        assertEquals(2, independent.size)
        assertTrue(independent.any { it.name == "FLEXIBLECOLOR-07" })
        assertTrue(independent.any { it.name == "MSLT-Portra400-V1" })
    }

    @Test
    fun freshInstallSeedsOnePresetPerOfficialRecipe() = runBlocking {
        val repository = PresetRepository(context)
        repository.load()

        val presets = repository.presets.value
        // 同一 nic: 哈希只出现一次，且与 builtInCloudPresets 的旧在线内置不重复。
        val nicHashes = presets.map { it.contentHash }.filter { it.startsWith("nic:") }
        assertEquals(nicHashes.size, nicHashes.toSet().size)
        assertEquals(99, nicHashes.size)
        assertEquals(1, presets.count { it.name == "Vitality Film by Peng Mango" })
        // 导入同名官方滤镜会被 importOne 的 contentHash 去重命中。
        val vitalityHash = NikonCloudRecipe(
            id = "01JSBM30XWQ6GG4XK0KSZ3FQEK", name = "Vitality Film by Peng Mango"
        ).contentHash()
        assertEquals(1, presets.count { it.contentHash == vitalityHash })
    }

    @Test
    fun upgradeMigratesLegacyCloudrecipeToOfficialIdentityAndKeepsUserState() = runBlocking {
        val repository = PresetRepository(context)
        repository.load()

        // 模拟 1.6.46 覆盖安装：把 99 个官方方案的身份改回旧 cloudrecipe 形式、
        // 名称改回缩写，并重新插入 6 个被合并的重复变体 + 1 个旧在线内置重复项。
        val index = java.io.File(context.filesDir, "presets/index.json")
        val root = org.json.JSONObject(index.readText())
        val array = root.getJSONArray("presets")
        val byId = CloudOfficialNames.IDENTITY_FOR_ANY_SPEC
        val template = array.getJSONObject(0)

        // 追踪一个固定方案（Filmic by Fabio Oliveira）的用户状态，避免顺序依赖；
        // 该 recipeId 不与后面追加的重复项冲突，迁移后必然成为唯一胜者。
        val trackedRecipe = "01JSBM2ZVF6SX4VSEK76K1VGJQ"
        var trackedId: String? = null
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val hash = item.optString("contentHash")
            if (hash.startsWith("nic:") && hash.removePrefix("nic:") in CloudOfficialNames.SPEC_BY_RECIPE_ID) {
                val recipeId = hash.removePrefix("nic:")
                val specId = CloudOfficialNames.SPEC_BY_RECIPE_ID.getValue(recipeId)
                val identity = byId.getValue(specId)
                item.put("contentHash", "cloudrecipe:$specId")
                item.put("name", identity.aliases.first())
                item.put("sourceName", "legacy")
                if (recipeId == trackedRecipe) {
                    trackedId = item.optString("id")
                    item.put("favorite", true)
                    item.put("note", "别弄丢")
                }
            }
        }
        assertNotNull("测试基线里应有被追踪的官方方案", trackedId)
        // 追加 6 个重复变体（旧 cloudrecipe 身份）。
        CloudOfficialNames.MERGED_INTO.keys.forEach { specId ->
            array.put(
                org.json.JSONObject(template.toString())
                    .put("id", "legacy-$specId")
                    .put("contentHash", "cloudrecipe:$specId")
                    .put("name", "legacy alias $specId")
                    .put("isSample", true)
                    .put("fileName", "")
                    .put("fileSize", 0)
            )
        }
        // 追加一个旧的在线内置重复项（同一 nic: 身份，失真数据）。
        array.put(
            org.json.JSONObject(template.toString())
                .put("id", "legacy-online-vitality")
                .put("contentHash", "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK")
                .put("name", "Vitality Film by Peng Mango")
                .put("isSample", false)
                .put("sourceName", "Nikon Imaging Cloud")
                .put("fileName", "")
                .put("fileSize", 0)
        )
        index.writeText(root.toString())

        val reopened = PresetRepository(context)
        reopened.load()
        val presets = reopened.presets.value

        // 旧身份彻底消失。
        assertTrue("旧 cloudrecipe 官方条目应全部迁移",
            presets.none { it.contentHash.startsWith("cloudrecipe:") &&
                it.contentHash.removePrefix("cloudrecipe:") !in CloudOfficialNames.INDEPENDENT_IDS })
        assertTrue("6 个重复变体应退役", presets.none { it.id.startsWith("legacy-recipe_") })
        // 2 个独立文件保留旧身份。
        assertEquals(2, presets.count { it.contentHash.startsWith("cloudrecipe:") })
        // 每个官方 recipeId 只有一个条目。
        val nicHashes = presets.map { it.contentHash }.filter { it.startsWith("nic:") }
        assertEquals(nicHashes.size, nicHashes.toSet().size)
        assertEquals(99, nicHashes.size)
        // 用户状态保留（收藏 / 备注 / id）。
        val tracked = presets.firstOrNull { it.id == trackedId }
        assertNotNull("迁移不能换掉用户条目的 id", tracked)
        assertTrue("迁移必须保留收藏", tracked!!.favorite)
        assertEquals("别弄丢", tracked.note)
        assertEquals("nic:$trackedRecipe", tracked.contentHash)
        assertEquals("Filmic by Fabio Oliveira", tracked.name)

        // 幂等：再开一次不产生重复、不改写索引。
        val third = PresetRepository(context)
        third.load()
        val thirdNic = third.presets.value.map { it.contentHash }.filter { it.startsWith("nic:") }
        assertEquals(99, thirdNic.size)
        assertEquals(thirdNic.size, thirdNic.toSet().size)
        assertEquals(presets.map { it.id }.sorted(), third.presets.value.map { it.id }.sorted())
    }

    @Test
    fun userImportedDistortedRecipeIsUpgradedToRealNp3Data() = runBlocking {
        val repository = PresetRepository(context)
        repository.load()

        // 模拟用户此前在线导入的失真条目：官方名 + nic: 身份，但曲线/参数是简化值，
        // sourceName 是 Nikon Imaging Cloud，且带收藏与备注。
        val index = java.io.File(context.filesDir, "presets/index.json")
        val root = org.json.JSONObject(index.readText())
        val array = root.getJSONArray("presets")
        val recipeId = "01JD1PCXH2HKADD09AZZ80CN30" // Hidamari Color by Haruka Koharu
        val template = array.getJSONObject(0)
        array.put(
            org.json.JSONObject(template.toString())
                .put("id", "user-cloud-import")
                .put("contentHash", "nic:$recipeId")
                .put("name", "Hidamari Color by Haruka Koharu")
                .put("isSample", false)
                .put("sourceName", "Nikon Imaging Cloud")
                .put("favorite", true)
                .put("note", "手工标注")
                .put("curve", null)
                .put("fileName", "")
                .put("fileSize", 0)
        )
        index.writeText(root.toString())

        val reopened = PresetRepository(context)
        reopened.load()
        val upgraded = reopened.presets.value.first { it.id == "user-cloud-import" }
        assertEquals("用户导入条目的显示名应保留", "Hidamari Color by Haruka Koharu", upgraded.name)
        assertTrue("收藏必须保留", upgraded.favorite)
        assertEquals("手工标注", upgraded.note)
        // 升级为真实 NP3 数据。
        assertEquals(CloudRecipeCatalog.SOURCE_NAME, upgraded.sourceName)
        val official = OfficialCloudPresets.all(context).first { it.contentHash == "nic:$recipeId" }
        assertEquals(official.contrast, upgraded.contrast)
        assertEquals(official.highlights, upgraded.highlights)
        assertEquals(official.shadows, upgraded.shadows)
        assertEquals(official.colorBlender.size, upgraded.colorBlender.size)
        assertNull("该官方 NP3 无独立曲线", upgraded.curve)
        // 且不会产生第二个同 hash 条目。
        assertEquals(1, reopened.presets.value.count { it.contentHash == "nic:$recipeId" })
    }
}
