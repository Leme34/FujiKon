package com.fujikon.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 预设列表备份 / 恢复回归。
 *
 * 用户诉求：导出把「全部 / 已收藏 / 已删除」都记下来，重装后导入恢复到备份时点，
 * 三个维度一模一样。这里分两层验证：
 *  1) [PresetBackup] 纯编解码往返：所有用户态字段与二进制都无损；
 *  2) [PresetRepository] 导出→改动→恢复：活跃列表、已删除列表、收藏态逐条一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PresetBackupTest {

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

    private fun samplePreset(
        id: String,
        name: String,
        favorite: Boolean = false,
        deleted: Boolean = false,
        deletedAt: Long = 0L,
        fileName: String = "",
        note: String = ""
    ) = Preset(
        id = id, name = name, format = "NP3", version = "", sourceName = name,
        fileName = fileName, fileSize = 123, baseProfile = 0x03C2,
        baseProfileName = "中性", curve = null, sharpening = 0f, clarity = 0f,
        midRangeSharpening = 0f, contrast = 0, brightness = 0, saturation = 0,
        hue = 0, highlights = 0, shadows = 0, whites = 0, blacks = 0,
        filter = 0, toning = 0, toningStrength = 0, colorBlender = emptyList(),
        colorGrading = com.fujikon.app.ncp.ColorGrading(), description = null,
        note = note, favorite = favorite, importedAt = 1000L, isSample = false,
        contentHash = "hash:$id", filmLook = 0, deleted = deleted, deletedAt = deletedAt
    )

    /** 造一条 App 内置预置条目（无二进制、isSample=true），用于验证恢复时的补种/升级。 */
    private fun builtInSample(
        id: String,
        name: String,
        hash: String,
        favorite: Boolean = false,
        deleted: Boolean = false,
        deletedAt: Long = 0L,
        note: String = ""
    ) = samplePreset(id, name, favorite = favorite, deleted = deleted, deletedAt = deletedAt, note = note).copy(
        isSample = true,
        contentHash = hash,
        fileName = "",
        fileSize = 0,
        sourceName = "nic"
    )

    @Test
    fun encodeDecodeRoundTripKeepsEveryUserStateAndBinary() {
        val presets = listOf(
            samplePreset("a", "活跃A", favorite = true, note = "收藏"),
            samplePreset("b", "活跃B"),
            samplePreset("c", "已删C", deleted = true, deletedAt = 9999L, note = "删过"),
            samplePreset("d", "已删收藏D", favorite = true, deleted = true, deletedAt = 8888L)
        )
        val binaries = mapOf("a.np3" to byteArrayOf(1, 2, 3, 4, 5), "c.np3" to byteArrayOf(9, 8, 7))
        val json = PresetBackup.encode(presets, binaries, "1.6.66", exportedAt = 12345L)

        val info = PresetBackup.inspect(json)
        assertTrue(info.isValid)
        assertEquals(PresetBackup.SCHEMA, info.schema)
        assertEquals(12345L, info.exportedAt)
        assertEquals("1.6.66", info.appVersion)
        assertEquals(4, info.countAll)
        assertEquals(2, info.countActive)   // a、b
        assertEquals(1, info.countFavorite) // 仅未删的收藏 a；d 已删不计入
        assertEquals(2, info.countDeleted)  // c、d
    }

    @Test
    fun decodeRestoresFullPresetFields() {
        val presets = listOf(
            samplePreset("a", "活跃A", favorite = true, note = "收藏"),
            samplePreset("c", "已删C", deleted = true, deletedAt = 9999L)
        )
        val binaries = mapOf("a.np3" to byteArrayOf(1, 2, 3))
        val payload = PresetBackup.decode(PresetBackup.encode(presets, binaries, "v"))

        assertEquals(2, payload.presets.size)
        val a = payload.presets.first { it.id == "a" }
        assertTrue(a.favorite)
        assertEquals("收藏", a.note)
        assertFalse(a.deleted)
        val c = payload.presets.first { it.id == "c" }
        assertTrue(c.deleted)
        assertEquals(9999L, c.deletedAt)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(payload.binaries["a.np3"]!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsUnsupportedSchema() {
        val bad = org.json.JSONObject(PresetBackup.encode(emptyList(), emptyMap(), "v"))
            .put("schema", 999).toString()
        PresetBackup.decode(bad)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inspectRejectsGarbage() {
        PresetBackup.inspect("not a json {{{")
    }

    @Test
    fun exportThenMutateThenRestoreMatchesBackupMomentExactly() = runBlocking {
        repository.load()
        val before = repository.presets.value
        val beforeDeleted = repository.deletedPresets.value
        assertTrue(before.isNotEmpty())

        // 造出有代表性的用户态：收藏 + 软删除 + 备注。
        val toFav = before.first()
        repository.update(toFav.copy(favorite = true, note = "备份备注"))
        val toDelete = before[1]
        repository.delete(toDelete.id)

        // 此刻为"备份时点"。
        val snapshotActive = repository.presets.value.sortedBy { it.id }
        val snapshotDeleted = repository.deletedPresets.value.sortedBy { it.id }
        val snapshotFavIds = snapshotActive.filter { it.favorite }.map { it.id }.toSet()
        val backupJson = repository.exportBackupJson()

        // 备份之后继续改动：再删一个、取消一个收藏。
        repository.delete(repository.presets.value.first().id)
        repository.update(snapshotActive.first { it.favorite }.copy(favorite = false))

        // 恢复。
        val result = repository.restoreFromBackup(backupJson)
        assertEquals(snapshotActive.size, result.countActive)
        assertEquals(snapshotDeleted.size, result.countDeleted)

        val afterActive = repository.presets.value.sortedBy { it.id }
        val afterDeleted = repository.deletedPresets.value.sortedBy { it.id }
        assertEquals("活跃列表整体（id+name）应与备份时点一致",
            snapshotActive.map { it.id to it.name }, afterActive.map { it.id to it.name })
        assertEquals("已删除列表整体（id+name）应与备份时点一致",
            snapshotDeleted.map { it.id to it.name }, afterDeleted.map { it.id to it.name })
        assertEquals("收藏集合应一模一样",
            snapshotFavIds, afterActive.filter { it.favorite }.map { it.id }.toSet())
        assertNotNull("备份里的备注应还原",
            afterActive.firstOrNull { it.id == toFav.id }?.let { if (it.note == "备份备注") it else null })
        assertTrue("备份时点被删除的项恢复后仍在已删除列表",
            afterDeleted.any { it.id == toDelete.id })
    }

    @Test
    fun restoreSurvivesRepositoryReopen() = runBlocking {
        repository.load()
        val favorite = repository.presets.value.first()
        repository.update(favorite.copy(favorite = true, note = "持久备注"))
        val gone = repository.presets.value[2]
        repository.delete(gone.id)
        val activeBefore = repository.presets.value.map { it.id }.toSet()
        val deletedBefore = repository.deletedPresets.value.map { it.id }.toSet()
        val backupJson = repository.exportBackupJson()

        // 模拟重装：清空应用数据后新建仓库，再导入备份。
        context.filesDir.deleteRecursively()
        val fresh = PresetRepository(context)
        fresh.load()
        fresh.restoreFromBackup(backupJson)

        assertEquals("重装后活跃列表应与备份一致", activeBefore, fresh.presets.value.map { it.id }.toSet())
        assertEquals("重装后已删除列表应与备份一致", deletedBefore, fresh.deletedPresets.value.map { it.id }.toSet())
        val f = fresh.presets.value.firstOrNull { it.id == favorite.id }
        assertNotNull(f)
        assertTrue(f!!.favorite)
        assertEquals("持久备注", f.note)

        // 恢复后再重启一次，状态仍稳定（索引写入生效）。
        val reopened = PresetRepository(context)
        reopened.load()
        assertEquals(activeBefore, reopened.presets.value.map { it.id }.toSet())
        assertEquals(deletedBefore, reopened.deletedPresets.value.map { it.id }.toSet())
    }

    @Test
    fun restoreKeepsBinaryFilesSoPresetsRemainUsable() = runBlocking {
        repository.load()
        val withFile = repository.presets.value.first { it.fileName.isNotBlank() }
        val originalBytes = repository.fileFor(withFile).readBytes()
        val backupJson = repository.exportBackupJson()

        // 模拟重装：清数据、导入。
        context.filesDir.deleteRecursively()
        val fresh = PresetRepository(context)
        fresh.load()
        val result = fresh.restoreFromBackup(backupJson)

        assertTrue("应还原至少一个二进制文件", result.binaryFilesRestored > 0)
        val restored = fresh.presets.value.firstOrNull { it.id == withFile.id }
        assertNotNull(restored)
        assertTrue("原二进制文件必须还原，预设才可用", fresh.fileFor(restored!!).exists())
        assertTrue("二进制内容应无损",
            originalBytes.contentEquals(fresh.fileFor(restored).readBytes()))
    }

    @Test
    fun restoreOldVersionBackupKeepsBuiltInsAddedByNewerVersion() = runBlocking {
        // 模拟"旧版本导出的备份"：一条用户预设 + 一批旧的内置预置集合（此时 FXN/OFM/Flash
        // 这些新版本才有的内置预置完全不在备份里），这正是用户反馈导致内置预置消失的场景。
        val userPreset = samplePreset("user-1", "我的预设", favorite = true, note = "用户备注")
        val oldBuiltIn = builtInSample("old-1", "旧内置", "nic:recipe_old")
        val oldBackup = PresetBackup.encode(
            listOf(userPreset, oldBuiltIn), emptyMap(), "1.6.59"
        )

        // 全新安装：load 会按当前版本播种全套内置预置。
        context.filesDir.deleteRecursively()
        val fresh = PresetRepository(context)
        fresh.load()
        val builtInHashesBefore = fresh.presets.value
            .filter { it.isSample && it.fileName.isBlank() }
            .map { it.contentHash }.toSet()
        assertTrue("当前版本应带有内置预置", builtInHashesBefore.isNotEmpty())
        assertTrue("FXN/OFM/Flash 应属于当前内置预置",
            builtInHashesBefore.any { it.startsWith("dazz3:") })

        // 恢复旧备份。
        val result = fresh.restoreFromBackup(oldBackup)

        // 用户数据原样恢复。
        val restoredUser = fresh.presets.value.firstOrNull { it.id == "user-1" }
        assertNotNull(restoredUser)
        assertTrue(restoredUser!!.favorite)
        assertEquals("用户备注", restoredUser.note)

        // 核心回归点：备份里没有、但当前版本新增的内置预置必须仍然在。
        val builtInHashesAfter = fresh.presets.value
            .filter { it.isSample && it.fileName.isBlank() }
            .map { it.contentHash }.toSet()
        assertTrue("恢复旧备份不应丢失当前版本的任何一个内置预置",
            builtInHashesAfter.containsAll(builtInHashesBefore))
        assertTrue("FXN R 应在恢复后保留",
            com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V3 in builtInHashesAfter)
        assertTrue("OFM R 应在恢复后保留",
            com.fujikon.app.dazz.DazzFujiPresets.OFM_HASH_V6 in builtInHashesAfter)
        assertTrue("直闪人像应在恢复后保留",
            com.fujikon.app.dazz.DazzFujiPresets.FLASH_HASH_V6 in builtInHashesAfter)
        // RestoreResult 计数也应包含补种后的内置预置，而非停留在备份里的 2 条。
        assertTrue("恢复计数应含补种的内置预置", result.countActive > 2)
    }

    @Test
    fun restoreOldVersionBackupUpgradesBuiltInRecipeInPlace() = runBlocking {
        // 旧版本备份里的 FXN R 用旧配方 hash（V2），恢复后应原地升级到最新 V3，
        // 同时保留用户对该内置预置的收藏 / 备注，id 不变。
        val oldFxn = builtInSample(
            "fxn-id", "FXN R · 经典负片",
            com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V2,
            favorite = true, note = "收藏的旧配方"
        )
        val oldBackup = PresetBackup.encode(listOf(oldFxn), emptyMap(), "1.6.83")

        context.filesDir.deleteRecursively()
        val fresh = PresetRepository(context)
        fresh.load()
        fresh.restoreFromBackup(oldBackup)

        val upgraded = fresh.presets.value.firstOrNull { it.id == "fxn-id" }
        assertNotNull(upgraded)
        assertEquals("旧配方 hash 应升级到最新 V3",
            com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V3, upgraded!!.contentHash)
        assertTrue("收藏态应保留", upgraded.favorite)
        assertEquals("备注应保留", "收藏的旧配方", upgraded.note)
        // 不应再残留旧 hash 的条目。
        assertTrue("旧 hash 不应残留",
            fresh.presets.value.none { it.contentHash == com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V2 })
    }

    @Test
    fun restoreOldVersionBackupDoesNotResurrectDeletedBuiltIn() = runBlocking {
        // 内置预置被软删除后，即使在旧备份恢复后也不应被"复活"。
        val deletedFxn = builtInSample(
            "fxn-deleted", "FXN R · 经典负片",
            com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V3,
            deleted = true, deletedAt = 12345L
        )
        val oldBackup = PresetBackup.encode(listOf(deletedFxn), emptyMap(), "1.6.90")

        context.filesDir.deleteRecursively()
        val fresh = PresetRepository(context)
        fresh.load()
        fresh.restoreFromBackup(oldBackup)

        assertTrue("被软删除的内置预置不应出现在活跃列表",
            fresh.presets.value.none { it.contentHash == com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V3 })
        assertTrue("被软删除的内置预置应保留在已删除列表",
            fresh.deletedPresets.value.any { it.contentHash == com.fujikon.app.dazz.DazzFujiPresets.FXN_HASH_V3 })
    }
}
