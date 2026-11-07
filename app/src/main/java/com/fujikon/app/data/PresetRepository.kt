package com.fujikon.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fujikon.app.ncp.PictureControlParser
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

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

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

    fun find(id: String?): Preset? = _presets.value.firstOrNull { it.id == id }

    suspend fun importUris(uris: List<Uri>): ImportResult {
        load()
        val imported = mutableListOf<Preset>()
        val duplicates = mutableListOf<String>()
        val errors = mutableListOf<String>()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existingHashes = _presets.value.map { it.contentHash }.filter { it.isNotBlank() }.toMutableSet()
                val list = _presets.value.toMutableList()
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
                val existingHashes = _presets.value
                    .map { it.contentHash }
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                val list = _presets.value.toMutableList()
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
                val list = _presets.value.toMutableList()
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
                val list = _presets.value.toMutableList()
                val index = list.indexOfFirst { it.id == preset.id }
                if (index >= 0) {
                    list[index] = preset
                    publish(list)
                    writeIndex(list)
                }
            }
        }
    }

    suspend fun delete(id: String) {
        load()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val preset = _presets.value.firstOrNull { it.id == id } ?: return@withContext
                if (preset.fileName.isNotBlank()) {
                    File(filesDir, preset.fileName).delete()
                }
                val list = _presets.value.filterNot { it.id == id }
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
                val list = _presets.value.toMutableList()
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
     * Adds the built-in cloud presets that ship with the app. They have no
     * binary file, so de-duplication goes by their `nic:<recipeId>` content hash
     * (the same key a manual cloud import uses).
     */
    private fun appendBuiltIns(list: MutableList<Preset>, hashes: MutableSet<String>): Int {
        var added = 0
        builtInCloudPresets().forEach { preset ->
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

    private fun sampleAssets(): List<String> {
        val names = runCatching { context.assets.list("samples")?.toList() }.getOrNull() ?: emptyList()
        return names.filter { it.endsWith(".ncp", true) || it.endsWith(".np3", true) || it.endsWith(".np2", true) }
            .sorted()
            .map { "samples/$it" }
    }

    private fun publish(list: List<Preset>) {
        _presets.value = sort(list)
    }

    private fun sort(list: List<Preset>): List<Preset> = list.sortedWith(
        compareByDescending<Preset> { it.favorite }
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
