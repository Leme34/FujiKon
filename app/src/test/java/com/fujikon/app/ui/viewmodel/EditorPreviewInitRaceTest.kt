package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * 1.6.54 regression for「选择照片后偶发黑屏 + Attempt to invoke interface
 * method 'java.lang.Object kotlin.Lazy.getValue()' on a null object reference」.
 *
 * Root cause: EditorViewModel.previewMaxDimension was `by lazy` and declared
 * AFTER the init blocks. One init block launches a coroutine that calls
 * renderCurrent() during construction; renderCurrent() hops to the injected
 * renderDispatcher and reads previewMaxDimension from a background thread,
 * which can reach the property before the constructor assigned the generated
 * `previewMaxDimension$delegate` field. The resulting NPE was caught by
 * renderCurrent() and shown as the editor's error screen.
 *
 * Fix: compute the value eagerly at construction, before every init block, so
 * it is published to all threads as soon as the object exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorPreviewInitRaceTest {

    /** Runs the render block inline, maximising the constructor-time race. */
    private class ImmediateDispatcher : CoroutineDispatcher() {
        val dispatches = AtomicInteger(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            block.run()
        }
        override fun toString(): String = "ImmediateDispatcher"
    }

    private lateinit var context: Context
    private lateinit var cacheDir: File

    private val photo = PhotoItem(
        id = 21L,
        uri = Uri.parse("file:///fujikon_race/photo.jpg"),
        displayName = "photo.jpg",
        dateTaken = 0L,
        dateAdded = 0L,
        bucketId = "b",
        bucketName = "b",
        width = 64,
        height = 64,
        mimeType = "image/jpeg",
        size = 1L
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        cacheDir = File(context.cacheDir, "race_test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // Let PresetRepository.load() (Dispatchers.IO) and any render
        // continuation drain before swapping Main back.
        Thread.sleep(120)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun newViewModel(renderDispatcher: CoroutineDispatcher): EditorViewModel {
        val settings = AppSettings(context)
        return EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = SelectionStore().apply {
                setPhotos(listOf(photo))
                setPreset(Preset.NONE_ID)
            },
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir,
            renderDispatcher = renderDispatcher
        )
    }

    /** Structural lock: the property must never go back to `by lazy`. */
    @Test
    fun previewDimensionMustNotUseLazyDelegate() {
        val delegates = EditorViewModel::class.java.declaredFields
            .map { it.name }
            .filter { it.startsWith("previewMaxDimension") && it.contains("delegate") }
        assertTrue(
            "previewMaxDimension 不能在构造期用 by lazy：init 块的后台渲染会与 " +
                "delegate 字段赋值竞态，实际=$delegates",
            delegates.isEmpty()
        )
        val vm = newViewModel(ImmediateDispatcher())
        val value = vm.previewMaxDimension
        assertTrue("构造完成后必须立即可读且 >=1600，实际=$value", value >= 1600)
        assertTrue("预览最长边应 <=4096，实际=$value", value <= 4096)
    }

    /**
     * Simulate the race: an immediate dispatcher runs the render block
     * synchronously as soon as the init coroutine resumes on the IO thread
     * from PresetRepository.load(), i.e. while the main thread may still be
     * inside the constructor. No Lazy-delegate NPE may surface.
     */
    @Test
    fun constructorTimeRenderNeverFailsWithLazyNpe() = runBlocking {
        val dispatcher = ImmediateDispatcher()
        val vm = newViewModel(dispatcher)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val st = vm.state.value
            if (!st.loading && dispatcher.dispatches.get() > 0) break
            delay(10)
        }
        val err = vm.state.value.error
        assertTrue(
            "构造期首屏渲染不得报 kotlin.Lazy.getValue() 空指针，实际=$err",
            err == null || !err.contains("Lazy.getValue()")
        )
        assertTrue("首屏渲染必须完成（loading=false）", !vm.state.value.loading)
        assertTrue("必须真的发生过渲染分发", dispatcher.dispatches.get() > 0)
    }
}
