package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesTextService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * End-to-end AI retouching on a Robolectric virtual Android device: a real
 * photo is decoded and rendered, the preview is encoded and posted over a real
 * socket to a local Agnes-compatible server, and the returned image is decoded,
 * written to disk and shown as the final photo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorViewModelAiTest {

    private lateinit var context: android.content.Context
    private lateinit var settings: AppSettings
    private lateinit var selection: SelectionStore
    private lateinit var repository: PresetRepository
    private lateinit var renderer: ImageRenderer
    private lateinit var exportManager: ExportManager
    private lateinit var cacheDir: File
    private lateinit var photo: PhotoItem
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var vm: EditorViewModel

    @Volatile private var lastBody = ""
    @Volatile private var lastHeaders = emptyMap<String, String>()
    @Volatile private var requestCount = 0
    @Volatile private var status = 200
    @Volatile private var response = ""

    private val png: ByteArray = Base64.getDecoder().decode(ONE_PIXEL_PNG)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = AppSettings(context)
        settings.agnesApiKey = ""

        val source = File(context.cacheDir, "ai_source.jpg")
        javaClass.classLoader!!.getResourceAsStream("test_photo.jpg")!!.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        photo = PhotoItem(
            id = 42L,
            uri = Uri.fromFile(source),
            displayName = "test_photo.jpg",
            dateTaken = 0L,
            dateAdded = 0L,
            bucketId = "bucket",
            bucketName = "bucket",
            width = 48,
            height = 64,
            mimeType = "image/jpeg",
            size = source.length()
        )
        selection = SelectionStore().apply {
            setPhotos(listOf(photo))
            setPreset(Preset.NONE_ID)
        }
        repository = PresetRepository(context)
        renderer = ImageRenderer()
        exportManager = ExportManager(context, renderer, settings)
        cacheDir = File(context.cacheDir, "ai_edit_test").apply { mkdirs() }

        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use(::handle)
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(2_000)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun service() = AgnesImageService(
        baseUrl = "http://127.0.0.1:${server.localPort}",
        connectTimeoutMs = 3_000,
        readTimeoutMs = 5_000
    )

    private fun b64Response(): String =
        """{"created":1,"data":[{"url":null,"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""

    private fun textService() = AgnesTextService(
        baseUrl = "http://127.0.0.1:${server.localPort}",
        connectTimeoutMs = 3_000,
        readTimeoutMs = 5_000
    )

    private suspend fun createViewModel() {
        vm = EditorViewModel(
            context = context,
            presetRepository = repository,
            selectionStore = selection,
            renderer = renderer,
            exportManager = exportManager,
            settings = settings,
            agnesImageService = service(),
            aiCacheDir = cacheDir,
            agnesTextService = textService()
        )
        // Let the initial render settle before seeding a preview (Robolectric
        // decodeStream bitmaps cannot be read by the CPU renderer).
        await("首屏渲染结束") { it.error != null || it.rendered != null }
        injectRendered()
    }

    private fun injectRendered() {
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val field = EditorViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as MutableStateFlow<EditorViewModel.UiState>
        flow.value = flow.value.copy(
            original = bitmap,
            rendered = bitmap,
            cropBase = bitmap,
            loading = false,
            error = null
        )
    }

    private suspend fun await(label: String, timeoutMs: Long = 20_000, predicate: (EditorViewModel.UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(vm.state.value)) return
            delay(25)
        }
        val state = vm.state.value
        throw AssertionError(
            "等待超时：$label | ai=${state.aiState} loading=${state.loading} " +
                "error=${state.error} rendered=${state.rendered != null}"
        )
    }

    @Test
    fun withoutKeyThePromptEditorStillOpensButRetouchAsksForSettings() = runBlocking {
        createViewModel()
        assertNotNull(vm.state.value.rendered)

        // The AI dialog is a prompt editor: it stays reachable without a key
        // so "生成提示词" can fall back to on-device analysis.
        assertTrue(vm.requestAiEdit())
        assertFalse(vm.state.value.hasApiKey)
        assertTrue(vm.state.value.aiState is EditorViewModel.AiEditState.Idle)

        // Only starting a real retouch requires the key; no request is sent.
        vm.runAiEdit("任意提示词")
        assertTrue(vm.state.value.aiState is EditorViewModel.AiEditState.NeedApiKey)
        assertEquals(0, requestCount)
    }

    @Test
    fun oneTapAiEditStoresARealFinalImageAndCanBeReverted() = runBlocking {
        settings.agnesApiKey = "sk-e2e-key"
        response = b64Response()
        createViewModel()
        assertTrue(vm.requestAiEdit())
        assertFalse(vm.state.value.aiState is EditorViewModel.AiEditState.NeedApiKey)

        val prompt = "把天空改成星空，保留原构图"
        vm.runAiEdit(prompt)
        await("AI 修图完成/失败") {
            it.aiState is EditorViewModel.AiEditState.Done ||
                it.aiState is EditorViewModel.AiEditState.Failed
        }

        val state = vm.state.value
        assertTrue(
            "AI 修图应成功，实际=${state.aiState}",
            state.aiState is EditorViewModel.AiEditState.Done
        )
        val uri = state.aiResultUris[photo.id]
        assertNotNull("应缓存 AI 成品图", uri)
        val file = File(uri!!.path!!)
        assertTrue("AI 成品图应写入磁盘", file.exists())
        assertTrue("AI 成品图不应为空", file.length() > 0)
        assertTrue(state.hasAiResult)

        assertEquals(1, requestCount)
        assertEquals("Bearer sk-e2e-key", lastHeaders["authorization"])
        assertEquals(prompt, JSONObject(lastBody).getString("prompt"))
        assertEquals(AgnesImageService.MODEL, JSONObject(lastBody).getString("model"))
        assertEquals(prompt, settings.agnesPrompt)
        assertEquals(prompt, state.aiPrompt)

        // The final image also drives the preview (no second preset pass).
        await("AI 结果刷新预览") { !it.loading && it.rendered != null && it.hasAiResult }

        vm.clearAiResult()
        await("撤销 AI 结果") { !it.aiResultUris.containsKey(photo.id) }
        assertFalse(vm.state.value.hasAiResult)
    }

    @Test
    fun invalidKeySurfacesAReadableErrorState() = runBlocking {
        settings.agnesApiKey = "bad-key"
        status = 401
        response = """{"error":{"message":"invalid api key"}}"""
        createViewModel()

        vm.runAiEdit("测试提示词")
        await("错误状态") { it.aiState is EditorViewModel.AiEditState.Failed }

        val failed = vm.state.value.aiState as EditorViewModel.AiEditState.Failed
        assertTrue(failed.message.contains("密钥无效"))
        assertTrue(vm.state.value.aiResultUris.isEmpty())
    }


    @Test
    fun generateEditPromptCallsAgnesTextModelWithThePhoto() = runBlocking {
        settings.agnesApiKey = "sk-e2e-key"
        response = """{"choices":[{"message":{"content":"提升曝光，校正偏蓝，保留原构图"}}]}"""
        createViewModel()

        vm.generateEditPrompt()
        await("提示词生成完成") {
            it.promptGen is EditorViewModel.PromptGenState.Done ||
                it.promptGen is EditorViewModel.PromptGenState.Failed
        }
        val done = vm.state.value.promptGen
        assertTrue("实际=$done", done is EditorViewModel.PromptGenState.Done)
        done as EditorViewModel.PromptGenState.Done
        assertEquals("提升曝光，校正偏蓝，保留原构图", done.prompt)
        assertEquals(EditorViewModel.PromptSource.AGNES, done.source)
        assertEquals(1, requestCount)
        assertEquals("Bearer sk-e2e-key", lastHeaders["authorization"])

        val body = JSONObject(lastBody)
        assertEquals("agnes-3.0-flash", body.getString("model"))
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val url = parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue("必须上传当前预览图", url.startsWith("data:image/jpeg;base64,"))

        // The result is a one-shot event: consuming it returns to Idle.
        vm.consumePromptGen()
        assertTrue(vm.state.value.promptGen is EditorViewModel.PromptGenState.Idle)
    }

    @Test
    fun modelUnavailableFallsBackToOnDeviceAnalysis() = runBlocking {
        settings.agnesApiKey = "sk"
        status = 404
        response = """{"error":{"message":"model agnes-3.0-flash not found"}}"""
        createViewModel()

        vm.generateEditPrompt()
        await("降级提示词") { it.promptGen is EditorViewModel.PromptGenState.Done }
        val done = vm.state.value.promptGen as EditorViewModel.PromptGenState.Done
        assertEquals(EditorViewModel.PromptSource.LOCAL, done.source)
        assertTrue("应是可执行的中文提示词: ${done.prompt}", done.prompt.contains("保留原构图"))
        assertTrue(done.prompt.length <= 500)
    }

    @Test
    fun authErrorIsNotMaskedByTheLocalFallback() = runBlocking {
        settings.agnesApiKey = "bad"
        status = 401
        response = """{"error":{"message":"无效的令牌"}}"""
        createViewModel()

        vm.generateEditPrompt()
        await("认证错误") { it.promptGen is EditorViewModel.PromptGenState.Failed }
        val failed = vm.state.value.promptGen as EditorViewModel.PromptGenState.Failed
        assertTrue(failed.message.contains("密钥") || failed.message.contains("无效的令牌"))
    }

    @Test
    fun withoutKeyPromptGenerationStillWorksOffline() = runBlocking {
        settings.agnesApiKey = ""
        createViewModel()

        vm.generateEditPrompt()
        await("离线提示词") { it.promptGen is EditorViewModel.PromptGenState.Done }
        val done = vm.state.value.promptGen as EditorViewModel.PromptGenState.Done
        assertEquals(EditorViewModel.PromptSource.LOCAL, done.source)
        assertEquals(0, requestCount)
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        lastHeaders = headers
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        lastBody = String(buffer, 0, offset, StandardCharsets.UTF_8)
        requestCount++

        val body = (response.ifBlank { "{}" }).toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val char = input.read()
            if (char == -1) return null
            if (char == '\n'.code) break
            if (char != '\r'.code) out.write(char)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        private const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    }
}
