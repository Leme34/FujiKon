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
}
