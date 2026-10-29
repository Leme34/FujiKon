package com.fujikon.app.nikonpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest

/** Network client for the public community catalogue at https://nikonpc.com/. */
class NikonPcService(
    baseUrl: String = DEFAULT_BASE_URL,
    private val cacheDir: File? = null
) {

    private val base = baseUrl.trimEnd('/') + "/"

    suspend fun fetchCatalog(): List<NikonPcPreset> = withContext(Dispatchers.IO) {
        val bytes = request(
            url = base,
            method = "GET",
            body = null,
            accept = "text/html,application/xhtml+xml",
            maxBytes = MAX_CATALOG_BYTES
        )
        val presets = NikonPcCatalogParser.parse(String(bytes, Charsets.UTF_8))
        if (presets.isEmpty()) {
            throw NikonPcException("nikonpc.com 没有返回预设，页面结构可能已经更新")
        }
        presets
    }

    /**
     * Downloads one preset by asking nikonpc.com to regenerate its NCP file.
     * The generated file is canonical for the site and is cached locally so a
     * second "import all" does not download it again.
     */
    suspend fun download(remote: NikonPcPreset): ByteArray = withContext(Dispatchers.IO) {
        val cached = cacheFile(remote.path)
        if (cached != null && cached.isFile) {
            val bytes = runCatching { cached.readBytes() }.getOrNull()
            if (bytes != null && isPresetFile(bytes)) {
                val repaired = NikonPcNcpRepair.repair(bytes)
                if (repaired !== bytes) writeCache(cached, repaired)
                return@withContext repaired
            }
            cached.delete()
        }

        val metadataBytes = request(
            url = base + "?cmd=loadNpc",
            method = "POST",
            body = encodeForm(mapOf("name" to remote.path)),
            accept = "text/plain,*/*",
            maxBytes = MAX_METADATA_BYTES
        )
        val metadata = NikonPcMetadataParser.parse(String(metadataBytes, Charsets.UTF_8))
        val form = NikonPcFormBuilder.build(metadata, remote)
        val bytes = request(
            url = base + "?cmd=dowloadModefiedNpc&${System.currentTimeMillis()}&",
            method = "POST",
            body = encodeForm(form),
            accept = "application/octet-stream,*/*",
            maxBytes = MAX_PRESET_BYTES
        )
        val repaired = NikonPcNcpRepair.repair(bytes)
        if (!isPresetFile(repaired)) {
            throw NikonPcException("nikonpc 返回的文件不是有效的 NCP 预设")
        }
        writeCache(cached, repaired)
        repaired
    }

    private fun request(
        url: String,
        method: String,
        body: ByteArray?,
        accept: String,
        maxBytes: Int
    ): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            doInput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setFixedLengthStreamingMode(body.size)
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output -> output.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.use { it.readLimited(ERROR_BODY_BYTES) }
                throw NikonPcException("nikonpc.com 返回 HTTP $code")
            }
            val stream = connection.inputStream ?: throw NikonPcException("nikonpc.com 没有返回内容")
            return stream.use { it.readLimited(maxBytes) }
        } catch (e: NikonPcException) {
            throw e
        } catch (e: IOException) {
            throw NikonPcException(networkMessage(e), e)
        } finally {
            connection.disconnect()
        }
    }

    private fun networkMessage(error: IOException): String = when (error) {
        is UnknownHostException, is ConnectException ->
            "无法连接 nikonpc.com，请检查网络后重试"
        is SocketTimeoutException ->
            "连接 nikonpc.com 超时，请稍后重试"
        else ->
            "访问 nikonpc.com 失败：${error.message ?: "网络错误"}"
    }

    private fun cacheFile(path: String): File? {
        val directory = cacheDir ?: return null
        directory.mkdirs()
        return File(directory, sha256(path) + ".ncp")
    }

    private fun writeCache(file: File?, bytes: ByteArray) {
        if (file == null) return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun isPresetFile(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || bytes[3].toInt() != 0) return false
        return when (String(bytes, 0, 3, Charsets.US_ASCII)) {
            "NCP", "NP2", "NP3" -> true
            else -> false
        }
    }

    private fun encodeForm(values: Map<String, String>): ByteArray =
        values.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(Charsets.UTF_8)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > limit) throw NikonPcException("nikonpc 返回内容过大")
        }
        return output.toByteArray()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://nikonpc.com/"

        private const val USER_AGENT = "FujiKon/1.3.0 (Android; NikonPC importer)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 512 * 1024
        private const val MAX_PRESET_BYTES = 4 * 1024 * 1024
        private const val ERROR_BODY_BYTES = 4 * 1024
    }
}

class NikonPcException(message: String, cause: Throwable? = null) : Exception(message, cause)
