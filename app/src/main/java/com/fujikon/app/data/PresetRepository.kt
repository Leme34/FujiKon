package com.fujikon.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fujikon.app.ncp.PictureControlParser
import com.fujikon.app.nic.CloudOfficialNames
import com.fujikon.app.nic.OfficialCloudPresets
import com.fujikon.app.nic.WorkStyle16Preset
import com.fujikon.app.nic.builtInCloudPresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class PresetRepository(private val context: Context) {

    data class ImportResult(
        val imported: List<Preset>,
        val duplicates: List<String>,
        val errors: List<String>
    ) {
        val total: Int get() = imported.size + duplicates.size + errors.size
    }

    /**
     * A preset downloaded from outside the app. [key] lets a batch importer map
     * each result back to its remote row even when two presets share a name.
     */
    data class IncomingPreset(
        val key: String,
        val displayName: String,
        val bytes: ByteArray,
        val sourceName: String = displayName
    )

    sealed interface BinaryImportResult {
        data class Imported(val preset: Preset) : BinaryImportResult
        data class Duplicate(val name: String) : BinaryImportResult
        data class Failed(val message: String) : BinaryImportResult
    }

    private val mutex = Mutex()
    private val rootDir = File(context.filesDir, "presets")
    private val filesDir = File(rootDir, "files")
    private val indexFile = File(rootDir, "index.json")

    /**
     * 全量索引（含软删除条目）。只在 [mutex] 保护下读写；`publish` 是唯一
     * 写入口，保证「活跃列表」「已删除列表」「磁盘 index」三者永远同源。
     */
    @Volatile
    private var all: List<Preset> = emptyList()

    /** 对外只暴露未删除的预设：编辑器、相册、设置、选择预设列表语义不变。 */
    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    /** 「已删除」列表，按删除时间倒序。 */
    private val _deletedPresets = MutableStateFlow<List<Preset>>(emptyList())
    val deletedPresets: StateFlow<List<Preset>> = _deletedPresets.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun load() {
        globalLoadMutex.withLock {
            mutex.withLock {
                if (loaded) return
                withContext(Dispatchers.IO) {
                    rootDir.mkdirs()
                    filesDir.mkdirs()
                    var list = readIndex()
                    if (!indexFile.exists()) {
                        list = seedSamplesLocked()
                        writeIndex(list)
                    } else {
                        // An existing install keeps its own index, so a sample
                        // removed from assets would otherwise survive upgrades.
                        list = dropRetiredSamplesLocked(list)
                        // Built-in presets ship in code, not assets: an app update
                        // that changes their render recipe would otherwise never
                        // reach users who launched a previous version once.
                        list = upgradeBuiltInSamplesLocked(list)
                        // 1.6.47：把旧 cloudrecipe:* 身份迁移为 nic:<recipeId> + 官方全称，
                        // 合并重复缩写变体，并把此前在线导入的失真条目升级为真实 NP3 数据。
                        list = migrateOfficialCloudIdentityLocked(list)
                        // Presets ADDED in a newer app version (e.g. the seven
                        // nikonpc.com official presets) must also be seeded on an
                        // existing install, otherwise users who already launched
                        // once would never see them. Content-hash de-dup keeps
                        // this idempotent and never overwrites user state.
                        list = ensureBuiltInsLocked(list)
                    }
                    publish(list)
                    loaded = true
                    _ready.value = true
                }
            }
        }
    }

    /**
     * Retires built-in samples that are no longer shipped in `assets/samples`.
     * Matching by name AND by content hash also catches entries the user
     * renamed. Only entries seeded by the app (`isSample`) are removed, so a
     * preset the user imported under the same name (or with identical bytes) is
     * never touched. Idempotent: the index is written back only when rows were
     * actually removed.
     */
    private fun dropRetiredSamplesLocked(list: List<Preset>): List<Preset> {
        val retiredNames = setOf("NC 400 MATCH")
        val retiredHashes = setOf(
            // sha256 of the retired assets/samples/nc400_match.ncp
            "925f3ee0a142e3642df7c67e911e0d99169b11322af3c61801c9bf16db7efb16"
        )
        val retiredIds = list.filter {
            it.isSample && (it.name in retiredNames || it.contentHash in retiredHashes)
        }.mapTo(mutableSetOf()) { it.id }
        if (retiredIds.isEmpty()) return list
        list.filter { it.id in retiredIds }.forEach { preset ->
            if (preset.fileName.isNotBlank()) File(filesDir, preset.fileName).delete()
        }
        val kept = list.filterNot { it.id in retiredIds }
        writeIndex(kept)
        return kept
    }

    /**
     * Replaces a seeded built-in preset whose recipe changed between app
     * versions (e.g. 「通透胶片」v1 逐通道 curve -> v2 保色亮度耦合 LUT).
     * Only app-seeded, file-less entries are touched, so an imported preset
     * with the same name is never replaced. User state (id / favourite /
     * note / importedAt) is preserved. Idempotent: once the old hash is gone
     * nothing matches and the index is left untouched.
     */
    private fun upgradeBuiltInSamplesLocked(list: List<Preset>): List<Preset> {
        // old content hash -> the new template that replaces it
        val upgrades = mapOf(
            "local:workstyle16_tonal_v1" to WorkStyle16Preset.CONTENT_HASH
        )
        if (list.none { it.isSample && it.fileName.isBlank() && it.contentHash in upgrades }) {
            return list
        }
        val templates = builtInCloudPresets().associateBy { it.contentHash }
        // If the new template is somehow already present, just drop the stale one.
        val present = list.map { it.contentHash }.toMutableSet()
        val result = list.mapNotNull { stored ->
            if (!stored.isSample || stored.fileName.isNotBlank()) return@mapNotNull stored
            val newHash = upgrades[stored.contentHash] ?: return@mapNotNull stored
            val template = templates[newHash] ?: return@mapNotNull stored
            if (newHash in present) null else {
                present.add(newHash)
                template.copy(
                    id = stored.id,
                    favorite = stored.favorite,
                    note = stored.note,
                    importedAt = stored.importedAt,
                    isSample = true,
                    fileName = "",
                    fileSize = 0,
                    // 升级配方不能把用户已软删除的预置「复活」。
                    deleted = stored.deleted,
                    deletedAt = stored.deletedAt
                )
            }
        }
        if (result.size != list.size || result.zip(list).any { (a, b) -> a !== b }) {
            writeIndex(result)
        }
        return result
    }

    /**
     * 1.6.47 一次性身份迁移。旧版把附件 NP3 预置记为 `cloudrecipe:recipe_XXX`
     * （缩写名），而在线导入记为 `nic:<recipeId>`（官方名）—— 同一官方滤镜
     * 因此会出现两个。这里按 [CloudOfficialNames] 的官方对照表收敛：
     *
     *  - app 播种的旧 `cloudrecipe:*` 条目 → 改为 `nic:<recipeId>`、官方全称与
     *    真实 NP3 渲染数据；6 个重复缩写变体退役（代表会被补种）；2 个无官方
     *    对应的文件保持独立身份不变；
     *  - 此前用户在线导入、命中官方映射但数据是失真简化参数的 `nic:*` 条目 →
     *    就地升级为真实 NP3 数据；
     *  - 用户状态（id / 收藏 / 备注 / 删除 / 导入时间）全部保留，软删除的内置
     *    预置不会被「复活」，用户自己改名/加备注的条目也不会被覆盖显示状态。
     *
     * 幂等：迁移后旧 hash 不再出现，官方条目的 sourceName 与真实数据一致，
     * 第二次加载不会重复改写索引。
     */
    private fun migrateOfficialCloudIdentityLocked(list: List<Preset>): List<Preset> {
        val templates = OfficialCloudPresets.all(context).associateBy { it.contentHash }
        val officialSource = OfficialCloudPresets.RECIPE_SOURCE
        val officialIds = CloudOfficialNames.SPEC_BY_RECIPE_ID.keys

        /** 每条记录的迁移规划：目标官方 hash；retire=true 表示退役；null 表示保持原样。 */
        data class Plan(val targetHash: String?, val retire: Boolean)

        fun plan(p: Preset): Plan {
            val hash = p.contentHash
            // 已是官方身份（内置在线预置或用户在线导入）：命中映射则升级数据。
            if (hash.startsWith(CloudOfficialNames.HASH_PREFIX) &&
                hash.removePrefix(CloudOfficialNames.HASH_PREFIX) in officialIds
            ) return Plan(hash, false)
            // 旧版 app 播种的附件 NP3 预置。
            if (hash.startsWith("cloudrecipe:") && p.isSample && p.fileName.isBlank()) {
                val specId = hash.removePrefix("cloudrecipe:")
                return when {
                    specId in CloudOfficialNames.INDEPENDENT_IDS -> Plan(null, false)
                    specId in CloudOfficialNames.MERGED_INTO -> Plan(null, true)
                    else -> {
                        val identity = CloudOfficialNames.IDENTITY_FOR_ANY_SPEC[specId]
                            ?: return Plan(null, false)
                        Plan(CloudOfficialNames.HASH_PREFIX + identity.recipeId, false)
                    }
                }
            }
            return Plan(null, false)
        }

        val plans = list.map(::plan)

        // 同一个官方 hash 可能同时存在「旧 cloudrecipe 条目」「旧内置在线预置」
        // 「用户导入条目」。只保留一个胜者：优先用户导入（isSample=false，带用户
        // 收藏/备注），其次保留先出现者；其余退役，避免列表里出现两个同名滤镜。
        val winner = HashMap<String, Int>()
        for (i in list.indices) {
            val target = plans[i].targetHash ?: continue
            val current = winner[target]
            if (current == null || (!list[i].isSample && list[current].isSample)) {
                winner[target] = i
            }
        }

        var changed = false
        val result = ArrayList<Preset>(list.size)
        for (i in list.indices) {
            val stored = list[i]
            val plan = plans[i]
            val target = plan.targetHash
            when {
                plan.retire -> changed = true
                target == null -> result.add(stored)
                winner[target] != i -> changed = true // 同 hash 的落选条目退役
                else -> {
                    val template = templates[target]
                    if (template == null) {
                        result.add(stored)
                    } else if (stored.contentHash == target &&
                        stored.sourceName == officialSource &&
                        stored.description == template.description
                    ) {
                        // 幂等：已是真实数据 + 官方身份，无需改写索引。
                        result.add(stored)
                    } else {
                        result.add(
                            template.copy(
                                id = stored.id,
                                // 用户自行改过名的导入条目保留其显示名；app 播种的用官方名。
                                name = if (stored.isSample) template.name else stored.name,
                                favorite = stored.favorite,
                                note = stored.note,
                                importedAt = stored.importedAt,
                                isSample = stored.isSample,
                                fileName = stored.fileName,
                                fileSize = stored.fileSize,
                                deleted = stored.deleted,
                                deletedAt = stored.deletedAt
                            )
                        )
                        changed = true
                    }
                }
            }
        }

        if (changed) writeIndex(result)
        return result
    }

    /** 已删除的预设不应对外可查（详情页、编辑器都不该再用到它）。 */
    fun find(id: String?): Preset? = all.firstOrNull { !it.deleted && it.id == id }

    suspend fun importUris(uris: List<Uri>): ImportResult {
        load()
        val imported = mutableListOf<Preset>()
        val duplicates = mutableListOf<String>()
        val errors = mutableListOf<String>()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // 已删除条目不参与去重：删掉一个导入预设后重新导入同一文件
                // 仍应成功（旧条目留在「已删除」列表里，可随时恢复）。
                val existingHashes = all.filterNot { it.deleted }
                    .map { it.contentHash }.filter { it.isNotBlank() }.toMutableSet()
                val list = all.toMutableList()
                uris.forEach { uri ->
                    val displayName = displayName(uri) ?: "preset_${System.currentTimeMillis()}"
                    try {
                        val bytes = readAllLimited(uri, MAX_FILE_BYTES)
                            ?: throw IllegalArgumentException("无法读取文件")
                        val control = PictureControlParser.parse(bytes, displayName)
                        val hash = sha256(bytes)
                        if (hash in existingHashes) {
                            duplicates.add(displayName)
                            return@forEach
                        }
                        val id = UUID.randomUUID().toString()
                        val extension = when (control.format) {
                            com.fujikon.app.ncp.PictureControlFormat.NP3 -> "np3"
                            com.fujikon.app.ncp.PictureControlFormat.NP2 -> "np2"
                            else -> "ncp"
                        }
                        val storedName = "$id.$extension"
                        File(filesDir, storedName).writeBytes(bytes)
                        val preset = Preset.from(
                            control = control,
                            id = id,
                            fileName = storedName,
                            fileSize = bytes.size,
                            isSample = false,
                            contentHash = hash,
                            sourceName = displayName
                        )
                        list.add(preset)
                        imported.add(preset)
                        existingHashes.add(hash)
                    } catch (e: Exception) {
                        errors.add("$displayName：${e.message ?: "解析失败"}")
                    }
                }
                publish(list)
                writeIndex(list)
            }
        }
        return ImportResult(imported, duplicates, errors)
    }

    /**
     * Imports already-downloaded preset bytes as one batch. The index is written
     * once, and [IncomingPreset.key] is used to report an outcome per remote row.
     */
    suspend fun importBinaries(files: List<IncomingPreset>): Map<String, BinaryImportResult> {
        load()
        if (files.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, BinaryImportResult>()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existingHashes = all.filterNot { it.deleted }
                    .map { it.contentHash }
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                val list = all.toMutableList()
                var changed = false

                files.forEach { file ->
                    val displayName = file.displayName.ifBlank { "nikonpc_preset" }
                    try {
                        if (file.bytes.size > MAX_FILE_BYTES) {
                            throw IllegalArgumentException("文件超过 4MB，可能不是预设文件")
                        }
                        val control = PictureControlParser.parse(file.bytes, displayName)
                        val hash = sha256(file.bytes)
                        if (hash in existingHashes) {
                            results[file.key] = BinaryImportResult.Duplicate(displayName)
                            return@forEach
                        }
                        val id = UUID.randomUUID().toString()
                        val extension = when (control.format) {
                            com.fujikon.app.ncp.PictureControlFormat.NP3 -> "np3"
                            com.fujikon.app.ncp.PictureControlFormat.NP2 -> "np2"
                            else -> "ncp"
                        }
                        val storedName = "$id.$extension"
                        File(filesDir, storedName).writeBytes(file.bytes)
                        val preset = Preset.from(
                            control = control,
                            id = id,
                            fileName = storedName,
                            fileSize = file.bytes.size,
                            isSample = false,
                            contentHash = hash,
                            sourceName = file.sourceName.ifBlank { displayName }
                        )
                        list.add(preset)
                        existingHashes.add(hash)
                        results[file.key] = BinaryImportResult.Imported(preset)
                        changed = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        results[file.key] = BinaryImportResult.Failed(
                            "$displayName：${e.message ?: "解析失败"}"
                        )
                    }
                }

                if (changed) {
                    publish(list)
                    writeIndex(list)
                }
            }
        }
        return results
    }

    /**
     * Adds an in-app preset (for example a curve-adjusted copy). The ID and
     * display name are made unique here so callers cannot overwrite an imported
     * NCP/NP3 preset by accident.
     */
    suspend fun add(preset: Preset): Preset {
        load()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val list = all.toMutableList()
                val saved = preset.copy(
                    id = UUID.randomUUID().toString(),
                    name = uniqueName(preset.name, list)
                )
                list.add(saved)
                publish(list)
                writeIndex(list)
                saved
            }
        }
    }

    suspend fun update(preset: Preset) {
        load()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val list = all.toMutableList()
                val index = list.indexOfFirst { it.id == preset.id }
                if (index >= 0) {
                    list[index] = preset
                    publish(list)
                    writeIndex(list)
                }
            }
        }
    }

    /**
     * 软删除：预设从「我的预设」消失并进入「已删除」列表，可随时 [restore]。
     * 索引与二进制文件都保留，因此恢复后参数、收藏、备注、曲线完全不变；
     * 条目仍留在全量索引中，覆盖安装的 `ensureBuiltInsLocked` 不会把一个被
     * 用户删掉的内置预置重新播种回来。
     */
    suspend fun delete(id: String) {
        load()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val target = all.firstOrNull { it.id == id } ?: return@withContext
                if (target.deleted) return@withContext
                val now = System.currentTimeMillis()
                val list = all.map { stored ->
                    if (stored.id == id) stored.copy(deleted = true, deletedAt = now) else stored
                }
                publish(list)
                writeIndex(list)
            }
        }
    }

    /** 从「已删除」列表恢复，回到「我的预设」，用户态完整保留。 */
    suspend fun restore(id: String) {
        load()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val target = all.firstOrNull { it.id == id } ?: return@withContext
                if (!target.deleted) return@withContext
                val list = all.map { stored ->
                    if (stored.id == id) stored.copy(deleted = false, deletedAt = 0L) else stored
                }
                publish(list)
                writeIndex(list)
            }
        }
    }

    /** 彻底删除：仅「已删除」列表里的该操作会真正移除索引与二进制文件。 */
    suspend fun deletePermanently(id: String) {
        load()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val preset = all.firstOrNull { it.id == id } ?: return@withContext
                if (preset.fileName.isNotBlank()) {
                    File(filesDir, preset.fileName).delete()
                }
                val list = all.filterNot { it.id == id }
                publish(list)
                writeIndex(list)
            }
        }
    }

    suspend fun restoreSamples(): Int {
        load()
        var count = 0
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val list = all.toMutableList()
                // 用全量索引去重：被软删除的内置/示例仍在 index 里，不应被
                // 「加载示例预设」重新播种成第二条（要恢复请走 restore）。
                val existing = list.map { it.contentHash }.toMutableSet()
                sampleAssets().forEach { assetName ->
                    val bytes = runCatching { context.assets.open(assetName).use { it.readBytes() } }.getOrNull()
                        ?: return@forEach
                    val hash = sha256(bytes)
                    if (hash in existing) return@forEach
                    val control = runCatching { PictureControlParser.parse(bytes, assetName) }.getOrNull()
                        ?: return@forEach
                    val id = UUID.randomUUID().toString()
                    val extension = if (control.format.name == "NP3") "np3" else "ncp"
                    val storedName = "$id.$extension"
                    File(filesDir, storedName).writeBytes(bytes)
                    list.add(
                        Preset.from(
                            control = control,
                            id = id,
                            fileName = storedName,
                            fileSize = bytes.size,
                            isSample = true,
                            contentHash = hash
                        )
                    )
                    existing.add(hash)
                    count++
                }
                count += appendBuiltIns(list, existing)
                if (count > 0) {
                    publish(list)
                    writeIndex(list)
                }
            }
        }
        return count
    }

    fun fileFor(preset: Preset): File = File(filesDir, preset.fileName)

    // ------------------------------------------------------------ internals

    private fun seedSamplesLocked(): List<Preset> {
        val result = mutableListOf<Preset>()
        val hashes = mutableSetOf<String>()
        sampleAssets().forEach { assetName ->
            val bytes = runCatching { context.assets.open(assetName).use { it.readBytes() } }.getOrNull()
                ?: return@forEach
            val control = runCatching { PictureControlParser.parse(bytes, assetName) }.getOrNull()
                ?: return@forEach
            val id = UUID.randomUUID().toString()
            val extension = if (control.format.name == "NP3") "np3" else "ncp"
            val storedName = "$id.$extension"
            File(filesDir, storedName).writeBytes(bytes)
            val hash = sha256(bytes)
            result.add(
                Preset.from(
                    control = control,
                    id = id,
                    fileName = storedName,
                    fileSize = bytes.size,
                    isSample = true,
                    contentHash = hash
                )
            )
            hashes.add(hash)
        }
        appendBuiltIns(result, hashes)
        return result
    }

    /**
     * 全部内置预置：资料库附件逐一落地的 99 个官方命名 NP3 + 2 个独立 NP3 +
     * 9 个 3D LUT，加上原有内置（社区近似 / 通透胶片 / 7 个 nikonpc 官方等）。
     *
     * 1.6.47 起官方方案优先：旧内置在线预置「Vitality Film by Peng Mango」与新
     * 的 `nic:<recipeId>` 指向同一官方滤镜，这里按 contentHash 排除重复项，让
     * 零拟合 NP3 版本作为唯一来源，避免同一滤镜在列表里出现两个。
     */
    private fun allBuiltIns(): List<Preset> {
        val official = OfficialCloudPresets.all(context)
        val officialHashes = official.mapTo(HashSet()) { it.contentHash }
        val legacy = builtInCloudPresets().filterNot { it.contentHash in officialHashes }
        return official + legacy
    }

    /**
     * Adds the built-in cloud presets that ship with the app. They have no
     * binary file, so de-duplication goes by their `nic:<recipeId>` content hash
     * (the same key a manual cloud import uses).
     */
    private fun appendBuiltIns(list: MutableList<Preset>, hashes: MutableSet<String>): Int {
        var added = 0
        allBuiltIns().forEach { preset ->
            val hash = preset.contentHash
            if (hash.isBlank() || !hashes.add(hash)) return@forEach
            list.add(
                preset.copy(
                    id = UUID.randomUUID().toString(),
                    fileName = "",
                    fileSize = 0,
                    isSample = true,
                    importedAt = System.currentTimeMillis()
                )
            )
            added++
        }
        return added
    }

    /**
     * Seeds any built-in preset that a newer app version added but an existing
     * install's index is still missing. Matching by content hash makes it
     * idempotent and keeps user edits (id / favourite / note) on presets that
     * are already present untouched.
     */
    private fun ensureBuiltInsLocked(list: List<Preset>): List<Preset> {
        val present = list.map { it.contentHash }.toMutableSet()
        val missing = allBuiltIns().filter { present.add(it.contentHash) }
        if (missing.isEmpty()) return list
        val now = System.currentTimeMillis()
        val result = list.toMutableList()
        missing.forEach { preset ->
            result.add(
                preset.copy(
                    id = UUID.randomUUID().toString(),
                    fileName = "",
                    fileSize = 0,
                    isSample = true,
                    importedAt = now
                )
            )
        }
        writeIndex(result)
        return result
    }

    private fun sampleAssets(): List<String> {
        val names = runCatching { context.assets.list("samples")?.toList() }.getOrNull() ?: emptyList()
        return names.filter { it.endsWith(".ncp", true) || it.endsWith(".np3", true) || it.endsWith(".np2", true) }
            .sorted()
            .map { "samples/$it" }
    }

    /**
     * 单一写入口：全量索引、活跃列表、已删除列表同步更新，三者不会漂移。
     */
    private fun publish(list: List<Preset>) {
        all = list
        _presets.value = sortActive(list.filterNot { it.deleted })
        _deletedPresets.value = sortDeleted(list.filter { it.deleted })
    }

    /** 活跃列表：收藏优先，其余按名称。 */
    private fun sortActive(list: List<Preset>): List<Preset> = list.sortedWith(
        compareByDescending<Preset> { it.favorite }
            .thenBy { it.name.lowercase() }
    )

    /** 已删除列表：最近删除的排在最前，便于恢复。 */
    private fun sortDeleted(list: List<Preset>): List<Preset> = list.sortedWith(
        compareByDescending<Preset> { it.deletedAt }
            .thenBy { it.name.lowercase() }
    )

    private fun uniqueName(desired: String, list: List<Preset>): String {
        val base = desired.trim().ifBlank { "曲线调整" }.take(40)
        if (list.none { it.name.equals(base, ignoreCase = true) }) return base
        var index = 2
        while (true) {
            val suffix = " ($index)"
            val candidate = base.take(40 - suffix.length).trimEnd() + suffix
            if (list.none { it.name.equals(candidate, ignoreCase = true) }) return candidate
            index++
        }
    }

    private fun readIndex(): List<Preset> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val array = root.optJSONArray("presets") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                runCatching { Preset.fromJson(array.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(list: List<Preset>) {
        runCatching {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            val root = JSONObject().put("version", 1).put("presets", array)
            val temp = File(rootDir, "index.json.tmp")
            if (temp.exists()) temp.delete()
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (indexFile.exists()) indexFile.delete()
            if (!temp.renameTo(indexFile)) {
                temp.copyTo(indexFile, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun readAllLimited(uri: Uri, limit: Int): ByteArray? {
        val input: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > limit) throw IllegalArgumentException("文件超过 4MB，可能不是预设文件")
            }
            return output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024

        // Two repositories can exist in one process (app container plus tests or
        // a second Activity). Serialize their first load so samples are seeded once.
        private val globalLoadMutex = Mutex()
    }
}
