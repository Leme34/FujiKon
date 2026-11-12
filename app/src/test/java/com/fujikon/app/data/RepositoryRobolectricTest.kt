package com.fujikon.app.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
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
 * JVM smoke tests for the Android-facing pieces: asset seeding, JSON persistence
 * and the CPU renderer. Robolectric supplies the Android runtime and assets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: PresetRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        repository = PresetRepository(context)
    }

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
    }

    /**
     * 首次启动播种总数 = 4 个 sample assets + 10 个原有内置 + 用户指定的
     * 4 个精选云创官方真实 NP3（按实际解析结果计，避免以后 assets 增减时
     * 硬编码数字失真）。其余云创滤镜不再预置，由用户自行下载导入。
     */
    private fun seededCount(): Int =
        4 + com.fujikon.app.nic.builtInCloudPresets().size +
            com.fujikon.app.nic.OfficialCloudPresets.all(context).size

    @Test
    fun seedsSamplesAndBuiltInCloudPresetOnFirstLaunch() = runBlocking {
        repository.load()

        assertTrue(repository.ready.value)
        // 4 sample assets + 10 原有内置 + 4 个精选官方真实 NP3。
        assertEquals(seededCount(), repository.presets.value.size)
        val official = repository.presets.value.filter {
            it.contentHash.startsWith("cloudrecipe:") ||
                it.contentHash.startsWith("cloudlut:")
        }
        assertEquals("只预置 4 个精选云创滤镜", 4, official.size)
        assertEquals(
            setOf(
                "Vitality Film Pman",
                "Nature Links",
                "Textured Docmntry",
                "Ht Harmony Hana"
            ),
            official.map { it.name }.toSet()
        )
        assertTrue("不应预置 3D LUT 方案", repository.presets.value.none {
            !it.cubeLutAsset.isNullOrBlank()
        })
        assertTrue(repository.presets.value.any { it.name == "Agfa Optima" })
        assertTrue(repository.presets.value.any { it.name == "NC 400" })
        assertFalse(repository.presets.value.any { it.name == "NC 400 MATCH" })
        assertTrue(repository.presets.value.any { it.format == "NP3" })
        assertTrue(repository.presets.value.all { it.isSample })
        // 新增的 7 个官方预置随首次启动一起播种。
        assertTrue(repository.presets.value.any { it.name == "Canon 5d M2" })
        assertTrue(repository.presets.value.any { it.name == "Kodak Portra 160NC" })
        assertTrue(repository.presets.value.any { it.name == "st_g1.47" })
    }

    @Test
    fun upgradeDropsRetiredBuiltInSampleButKeepsUserImports() = runBlocking {
        repository.load()
        // 伪造旧版索引：两条内置条目（原样 / 已重命名）+ 两条用户导入（同名 / 同字节）
        val retiredHash = "925f3ee0a142e3642df7c67e911e0d99169b11322af3c61801c9bf16db7efb16"
        val index = java.io.File(context.filesDir, "presets/index.json")
        val root = org.json.JSONObject(index.readText())
        val array = root.getJSONArray("presets")
        val template = array.getJSONObject(0)
        fun legacy(id: String, name: String, isSample: Boolean) {
            array.put(
                org.json.JSONObject(template.toString())
                    .put("id", id)
                    .put("name", name)
                    .put("isSample", isSample)
                    .put("contentHash", retiredHash)
                    .put("fileName", "")
                    .put("fileSize", 0)
            )
        }
        legacy("legacy-retired-match", "NC 400 MATCH", true)
        legacy("legacy-renamed-match", "我的胶片", true)
        legacy("user-import-match", "NC 400 MATCH", false)
        legacy("user-import-same-bytes", "导入副本", false)
        index.writeText(root.toString())

        val reopened = PresetRepository(context)
        reopened.load()

        assertTrue(reopened.presets.value.none { it.id == "legacy-retired-match" })
        assertTrue("重命名过的内置条目也应被清理", reopened.presets.value.none { it.id == "legacy-renamed-match" })
        assertNotNull(reopened.presets.value.firstOrNull { it.id == "user-import-match" })
        assertNotNull(reopened.presets.value.firstOrNull { it.id == "user-import-same-bytes" })
        // seededCount 内置/示例 + 4 伪造 - 2 退役
        assertEquals(seededCount() + 2, reopened.presets.value.size)
    }

    @Test
    fun upgradeRetiresNonCuratedOfficialPresetsButKeepsUserImports() = runBlocking {
        repository.load()
        // 模拟从 1.6.43 覆盖安装：旧索引里额外有 3 个不再预置的官方方案
        //（2 个 NP3 + 1 个 3D LUT），以及 2 条用户自行导入 / 调整出的副本。
        val index = java.io.File(context.filesDir, "presets/index.json")
        val root = org.json.JSONObject(index.readText())
        val array = root.getJSONArray("presets")
        val template = array.getJSONObject(0)
        fun legacy(id: String, hash: String, isSample: Boolean) {
            array.put(
                org.json.JSONObject(template.toString())
                    .put("id", id)
                    .put("name", "旧官方方案 $id")
                    .put("isSample", isSample)
                    .put("contentHash", hash)
                    .put("fileName", "")
                    .put("fileSize", 0)
                    .put("cubeLutAsset", "")
            )
        }
        legacy("legacy-np3-001", "cloudrecipe:recipe_001", true)
        legacy("legacy-np3-050", "cloudrecipe:recipe_050", true)
        legacy("legacy-lut-01", "cloudlut:lut_01", true)
        legacy("user-import-np3", "cloudrecipe:recipe_001", false)
        legacy("user-edited-lut", "cloudlut:lut_01", false)
        index.writeText(root.toString())

        val reopened = PresetRepository(context)
        reopened.load()

        val kept = com.fujikon.app.nic.OfficialCloudPresets.PRESET_CONTENT_HASHES
        assertTrue("非精选官方预置应被退役", reopened.presets.value.none {
            it.id in setOf("legacy-np3-001", "legacy-np3-050", "legacy-lut-01")
        })
        assertTrue("不应残留任何已退役的 app-seeded 官方条目", reopened.presets.value.none {
            it.isSample &&
                (it.contentHash.startsWith("cloudrecipe:") ||
                    it.contentHash.startsWith("cloudlut:")) &&
                it.contentHash !in kept
        })
        assertNotNull("用户自行导入的条目不受影响",
            reopened.presets.value.firstOrNull { it.id == "user-import-np3" })
        assertNotNull("用户调整出的副本不受影响",
            reopened.presets.value.firstOrNull { it.id == "user-edited-lut" })
        assertEquals("4 个精选官方预置必须保留",
            4, reopened.presets.value.count { it.contentHash in kept })
        // seededCount + 2 条用户态条目
        assertEquals(seededCount() + 2, reopened.presets.value.size)

        // 幂等：再开一次不会重新播种已退役方案。
        val third = PresetRepository(context)
        third.load()
        assertEquals(seededCount() + 2, third.presets.value.size)
        assertEquals(4, third.presets.value.count { it.contentHash in kept })
    }

    @Test
    fun builtInPengMangoPresetIsListedWithParsedFields() = runBlocking {
        repository.load()

        val preset = repository.presets.value.first { it.name == "Vitality Film by Peng Mango" }
        assertEquals("nic:01JSBM30XWQ6GG4XK0KSZ3FQEK", preset.contentHash)
        assertEquals("Nikon Imaging Cloud", preset.sourceName)
        assertEquals("Flexible Color", preset.baseProfileName)
        assertTrue("内置云创预设没有二进制文件", preset.isDerived)
        assertEquals(0, preset.fileSize)
        // 官方 pictureControl 数值经全局 0.30 缩放：+3.50 / +1.50 / −1.00。
        assertEquals(1.05f, preset.sharpening, 1e-4f)
        assertEquals(0.45f, preset.midRangeSharpening, 1e-4f)
        assertEquals(-0.30f, preset.clarity, 1e-4f)
        // 1.6.43：该 recipeId 命中「云创色彩预设.zip」的 vitality film pman.NP3。
        // 官方 392 字节文件以「参数 + 分区调色」定义影调，无独立曲线，因此
        // 不再有拟合 256 点 LUT，曲线由官方参数 / Blender / Grading 取代。
        assertNull("官方真实 NP3 无独立色调曲线", preset.curve)
        assertEquals(0, preset.saturation)
        assertEquals(0, preset.hue)
        // 官方 NP3 原值（±100 口径），五项不再归零。
        assertEquals(-4, preset.contrast)
        assertEquals(54, preset.highlights)
        assertEquals(-17, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(-3, preset.blacks)
        // 官方 8 分区 Color Blender 与三分区 Color Grading 均为文件内真实数据。
        assertEquals(8, preset.colorBlender.size)
        assertTrue("应带官方 8 分区真实数据", preset.hasColorBlender)
        assertTrue("应带官方三分区 Grading 真实数据", preset.hasColorGrading)
        assertTrue(preset.description!!.contains("胶片"))
    }

    @Test
    fun builtInCommunityApproxPresetIsListedWithParsedFields() = runBlocking {
        repository.load()

        val preset = repository.presets.value.first { it.name == "Peng Mango 生命力风格（社区近似）" }
        assertEquals("local:pmango_style_approx", preset.contentHash)
        assertEquals("社区拟合（非官方）", preset.sourceName)
        assertEquals("NP3", preset.format)
        assertEquals("Flexible Color", preset.baseProfileName)
        assertEquals(256, preset.curve?.size)
        assertTrue("社区近似预设没有二进制文件", preset.isDerived)
        assertEquals(0, preset.fileSize)
        // 与官方条目使用同一 0.30 锐化缩放。
        assertEquals(1.05f, preset.sharpening, 1e-4f)
        assertEquals(0.45f, preset.midRangeSharpening, 1e-4f)
        assertEquals(-0.30f, preset.clarity, 1e-4f)
        // 影调已写进拟合 LUT，五项归零；饱和度偏移 −8 来自拟合。
        assertEquals(0, preset.contrast)
        assertEquals(0, preset.brightness)
        assertEquals(0, preset.highlights)
        assertEquals(0, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(0, preset.blacks)
        assertEquals(-8, preset.saturation)
        assertEquals(0, preset.hue)
        assertEquals(8, preset.colorBlender.size)
        val description = preset.description!!
        assertTrue(description.contains("非官方"))
        assertTrue(description.contains("分布相似度"))
        // 与官方 Vitality 条目相互独立，不能共用内容哈希。
        assertTrue(preset.contentHash != "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK")
        assertEquals("Peng Mango 生命力风格（社区近似）",
            com.fujikon.app.nic.PengMangoCommunityPreset.NAME)
    }

    @Test
    fun builtInContentHashesAreStableAcrossBuilds() {
        val first = com.fujikon.app.nic.builtInCloudPresets().map { it.contentHash }
        val second = com.fujikon.app.nic.builtInCloudPresets().map { it.contentHash }
        assertEquals(first, second)
        assertEquals(10, first.size)
        assertEquals(
            listOf(
                "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK",
                "local:pmango_style_approx",
                "local:workstyle16_tonal_v2",
                "nikonpc:canon_5d_m2",
                "nikonpc:fuji_provia",
                "nikonpc:fuji_sreala_skin",
                "nikonpc:fuji_velvia_neutral",
                "nikonpc:kodak_portra_160nc",
                "nikonpc:pt_mode2",
                "nikonpc:st_g1_47"
            ),
            first
        )
    }

    @Test
    fun upgradeReplacesLegacyWorkStylePresetWithV2() = runBlocking {
        repository.load()
        // 伪造旧版索引：把内置的「通透胶片」改回 v1 的 hash 与「无 lumaToneLut」
        // 形态，并保留用户态（收藏 + 备注），模拟从 1.6.32 覆盖安装上来的用户。
        val index = java.io.File(context.filesDir, "presets/index.json")
        val root = org.json.JSONObject(index.readText())
        val array = root.getJSONArray("presets")
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optString("contentHash") == "local:workstyle16_tonal_v2") {
                item.put("contentHash", "local:workstyle16_tonal_v1")
                item.put("favorite", true)
                item.put("note", "我最爱的滤镜")
                item.remove("lumaToneLut")
            }
        }
        index.writeText(root.toString())

        val reopened = PresetRepository(context)
        reopened.load()

        val upgraded = reopened.presets.value.firstOrNull {
            it.contentHash == "local:workstyle16_tonal_v2"
        }
        assertNotNull("升级后应存在 v2 通透胶片", upgraded)
        assertTrue("旧 v1 hash 应被清除",
            reopened.presets.value.none { it.contentHash == "local:workstyle16_tonal_v1" })
        assertNotNull("v2 必须带保色 LUT", upgraded!!.lumaToneLut)
        assertNull("v2 不应再有逐通道 curve", upgraded.curve)
        assertTrue("用户收藏应保留", upgraded.favorite)
        assertEquals("我最爱的滤镜", upgraded.note)
        assertEquals("条目总数不应变化", seededCount(), reopened.presets.value.size)
    }

    @Test
    fun upgradeSeedsNewlyAddedNikonPcPresetsOnExistingInstall() = runBlocking {
        repository.load()
        // 模拟从「还没有 7 个 nikonpc 官方预置」的旧版本覆盖安装：
        // 把 index 里这 7 个条目删掉，留下一份旧版索引。
        val index = java.io.File(context.filesDir, "presets/index.json")
        val root = org.json.JSONObject(index.readText())
        val array = root.getJSONArray("presets")
        val kept = org.json.JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (!item.optString("contentHash").startsWith("nikonpc:")) kept.put(item)
        }
        root.put("presets", kept)
        index.writeText(root.toString())

        val reopened = PresetRepository(context)
        reopened.load()

        // 升级后 7 个新预置应被补齐，且总数回到 seededCount（与全新安装一致）。
        val nikonpc = reopened.presets.value.filter { it.contentHash.startsWith("nikonpc:") }
        assertEquals(7, nikonpc.size)
        assertEquals(seededCount(), reopened.presets.value.size)
        assertTrue(nikonpc.all { it.isSample })

        // 幂等：再开一次不会重复播种。
        val third = PresetRepository(context)
        third.load()
        assertEquals(seededCount(), third.presets.value.size)
        assertEquals(7, third.presets.value.count { it.contentHash.startsWith("nikonpc:") })
    }

    @Test
    fun secondLaunchDoesNotDuplicateSamples() = runBlocking {
        repository.load()
        val firstIds = repository.presets.value.map { it.id }.sorted()

        val reopened = PresetRepository(context)
        reopened.load()

        assertEquals(firstIds, reopened.presets.value.map { it.id }.sorted())
        assertEquals(seededCount(), reopened.presets.value.size)
    }

    @Test
    fun importsDownloadedBinariesInOneBatchAndDeduplicates() = runBlocking {
        repository.load()
        val original = javaClass.classLoader!!.getResourceAsStream("nc400.ncp")!!.readBytes()
        val bytes = original.copyOf().apply {
            val name = "Remote Test".toByteArray(Charsets.US_ASCII)
            for (index in 0 until 20) {
                this[0x10 + index] = if (index < name.size) name[index] else 0
            }
        }

        val results = repository.importBinaries(
            listOf(
                PresetRepository.IncomingPreset(
                    key = "first",
                    displayName = "Remote Test",
                    bytes = bytes,
                    sourceName = "B&W/PICCON16.NCP"
                ),
                PresetRepository.IncomingPreset(
                    key = "second",
                    displayName = "Remote Test copy",
                    bytes = bytes.copyOf()
                ),
                PresetRepository.IncomingPreset(
                    key = "broken",
                    displayName = "Broken",
                    bytes = byteArrayOf(1, 2, 3)
                )
            )
        )

        val first = results["first"]
        assertTrue(first is PresetRepository.BinaryImportResult.Imported)
        val imported = (first as PresetRepository.BinaryImportResult.Imported).preset
        assertEquals("Remote Test", imported.name)
        assertEquals("B&W/PICCON16.NCP", imported.sourceName)
        // seededCount 内置/示例 + 1 成功导入
        assertEquals(seededCount() + 1, repository.presets.value.size)
        assertTrue(repository.fileFor(imported).exists())

        assertTrue(results["second"] is PresetRepository.BinaryImportResult.Duplicate)
        val failed = results["broken"]
        assertTrue(failed is PresetRepository.BinaryImportResult.Failed)
        assertTrue((failed as PresetRepository.BinaryImportResult.Failed).message.contains("Broken"))

        val reopened = PresetRepository(context)
        reopened.load()
        val persisted = reopened.presets.value.firstOrNull { it.id == imported.id }
        assertNotNull(persisted)
        assertEquals("B&W/PICCON16.NCP", persisted!!.sourceName)
    }

    @Test
    fun rendersNcpAndNp3AtOriginalPreviewSize() = runBlocking {
        repository.load()
        val ncp = repository.presets.value.first { it.name == "Agfa Optima" }
        val np3 = repository.presets.value.first { it.format == "NP3" && it.name.contains("CYAN", true) }

        val source = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(120, 90, 60))

        val ncpOut = ImageRenderer().render(source, ncp, forExport = false)
        assertEquals(24, ncpOut.width)
        assertEquals(16, ncpOut.height)
        assertFalse(source.isRecycled)

        val np3Out = ImageRenderer().render(ncpOut, np3, forExport = false)
        assertEquals(24, np3Out.width)
        assertEquals(16, np3Out.height)

        np3Out.recycle()
        ncpOut.recycle()
        source.recycle()
    }

    @Test
    fun exportRecyclesSourceBitmap() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first()
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val output = ImageRenderer().render(source, preset, forExport = true)

        assertTrue(source.isRecycled)
        assertEquals(8, output.width)
        output.recycle()
    }

    @Test
    fun agfaCurveRaisesTheBlackPoint() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first { it.name == "Agfa Optima" }
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.BLACK)

        val output = ImageRenderer().render(source, preset, forExport = false)
        val red = (output.getPixel(0, 0) shr 16) and 0xFF

        assertTrue("Agfa's curve lifts black to a small non-zero value", red > 0)
        output.recycle()
        source.recycle()
    }

    @Test
    fun toneCurveSurvivesJsonRoundTrip() = runBlocking {
        repository.load()
        val base = repository.presets.value.first()
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.3f, 0.2f),
                ToneCurvePoint(0.7f, 0.85f),
                ToneCurvePoint(1f, 1f)
            )
        )

        val decoded = Preset.fromJson(base.copy(toneCurve = curve).toJson())
        assertNotNull(decoded.toneCurve)
        assertEquals(4, decoded.toneCurve!!.normalizedPoints.size)
        assertEquals(0.2f, decoded.toneCurve!!.apply(0.3f), 0.0005f)
        assertTrue(decoded.hasToneCurve)

        val legacy = base.toJson().apply { remove("toneCurve") }
        assertNull(Preset.fromJson(legacy).toneCurve)
    }

    @Test
    fun toneCurveIsAppliedAfterTheNikonLook() = runBlocking {
        repository.load()
        val base = repository.presets.value.first { it.name == "Agfa Optima" }
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.rgb(128, 128, 128))

        val inverted = base.copy(
            toneCurve = ToneCurve(
                listOf(
                    ToneCurvePoint(0f, 1f),
                    ToneCurvePoint(1f, 1f)
                )
            )
        )
        val white = ImageRenderer().render(source, inverted, forExport = false)
        assertEquals(255, (white.getPixel(0, 0) shr 16) and 0xFF)
        assertEquals(255, (white.getPixel(0, 0) shr 8) and 0xFF)
        assertEquals(255, white.getPixel(0, 0) and 0xFF)

        val crushed = base.copy(
            toneCurve = ToneCurve(
                listOf(
                    ToneCurvePoint(0f, 0f),
                    ToneCurvePoint(1f, 0f)
                )
            )
        )
        val black = ImageRenderer().render(source, crushed, forExport = false)
        assertEquals(0, (black.getPixel(0, 0) shr 16) and 0xFF)

        white.recycle()
        black.recycle()
        source.recycle()
    }

    @Test
    fun curveCopyIsPersistedAndDeleteKeepsOriginalFile() = runBlocking {
        repository.load()
        // 显式选带二进制文件的 sample：列表里还有无 fileName 的内置预置，
        // 不能依赖 first() 的隐含顺序。
        val base = repository.presets.value.first { it.fileName.isNotBlank() }
        val originalFile = repository.fileFor(base)
        assertTrue(originalFile.exists())
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.5f, 0.7f),
                ToneCurvePoint(1f, 1f)
            )
        )

        val saved = repository.add(
            base.copy(
                name = base.name,
                fileName = "",
                fileSize = 0,
                sourceName = "由 ${base.name} 调整",
                toneCurve = curve,
                isSample = false
            )
        )

        assertTrue(saved.id != base.id)
        assertTrue(saved.name.startsWith(base.name))
        assertTrue(saved.isDerived)
        assertTrue(saved.hasToneCurve)

        val reopened = PresetRepository(context)
        reopened.load()
        val persisted = reopened.presets.value.firstOrNull { it.id == saved.id }
        assertNotNull(persisted)
        assertEquals(curve.normalizedPoints, persisted!!.toneCurve?.normalizedPoints)

        reopened.delete(saved.id)
        assertTrue("deleting a curve copy must not delete the source NCP", originalFile.exists())
        assertTrue(reopened.presets.value.none { it.id == saved.id })
    }
}
