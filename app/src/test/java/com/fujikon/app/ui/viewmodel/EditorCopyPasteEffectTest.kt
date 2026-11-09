package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.LensBlur
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurveChannel
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.io.File

/**
 * 1.6.29 "复制效果 / 粘贴效果":
 *
 * - Paste is a no-op (and the menu item is greyed out) until a look has been
 *   copied. Only ONE snapshot is kept; copying again overwrites it, so paste
 *   always uses the latest copy.
 * - The clipboard holds the WHOLE editable look: preset, master / channel
 *   curve overrides and every adjustment (including crop, lens blur, masks).
 *   AI retouch results are photo-specific images and are not copied.
 * - Paste is an INDIVIDUAL, undoable edit on the CURRENT photo only, in line
 *   with the per-photo grade + history semantics introduced in 1.6.28. The
 *   clipboard itself survives switching photos, so one look can be carried
 *   across the selection one photo at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorCopyPasteEffectTest {

    private lateinit var context: Context
    private lateinit var vm: EditorViewModel
    private lateinit var cacheDir: File

    private val testPhotos = (1L..3L).map { id ->
        PhotoItem(
            id = id,
            uri = Uri.parse("file:///fujikon_copy_paste/photo$id.jpg"),
            displayName = "photo$id.jpg",
            dateTaken = 0L,
            dateAdded = 0L,
            bucketId = "b",
            bucketName = "b",
            width = 64,
            height = 64,
            mimeType = "image/jpeg",
            size = 1L
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val settings = AppSettings(context)
        // NONE_ID keeps the baseline neutral, so preview renders stay cheap
        // and don't leave long-lived Dispatchers.Default jobs behind.
        val selection = SelectionStore().apply {
            setPhotos(testPhotos)
            setPreset(Preset.NONE_ID)
        }
        cacheDir = File(context.cacheDir, "copy_paste_test").apply { mkdirs() }
        vm = EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = selection,
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir
        )
    }

    @After
    fun tearDown() {
        // Preview renders run on Dispatchers.Default; let in-flight jobs drain
        // before swapping the Main dispatcher, otherwise resetMain() races
        // with a continuation that is still touching Dispatchers.Main.
        Thread.sleep(150)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private suspend fun awaitInit() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (vm.state.value.historyIndex == 0 && vm.state.value.photos.size == 3) return
            delay(10)
        }
        throw AssertionError("初始化未完成: ${vm.state.value}")
    }

    private fun customCurve() = ToneCurve(
        listOf(
            ToneCurvePoint(0f, 0f),
            ToneCurvePoint(0.5f, 0.72f),
            ToneCurvePoint(1f, 1f)
        )
    )

    /** No copy yet: paste must be disabled in state and do absolutely nothing. */
    @Test
    fun pasteIsNoopBeforeAnyCopy() = runBlocking {
        awaitInit()
        assertFalse(vm.state.value.hasCopiedEffect)
        assertNull(vm.state.value.copiedEffect)

        val before = vm.state.value.adjustments
        assertFalse("未复制时粘贴不应生效", vm.pasteEffect())
        assertEquals(before, vm.state.value.adjustments)
        assertEquals(0, vm.state.value.historyIndex)
        assertEquals(1, vm.state.value.historyEntries.size)
    }

    /** Copy captures preset, both curve overrides and the full adjustments. */
    @Test
    fun copyCapturesTheWholeEditableLook() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.selectPreset(Preset.NONE_ID)
        vm.setToneCurve(customCurve())
        vm.setChannelCurves(ToneCurveChannel.RED, customCurve())
        vm.setAdjustments(
            vm.state.value.adjustments.copy(
                exposure = 0.6f,
                contrast = 0.3f,
                vibrance = 0.2f,
                lensBlur = LensBlur(amount = 40f)
            ),
            "曝光度"
        )

        val before = vm.state.value
        vm.copyEffect()
        val copied = vm.state.value.copiedEffect
        assertNotNull("复制后剪贴板不应为空", copied)
        copied!!
        assertEquals(before.presetId, copied.presetId)
        assertEquals(before.toneCurveOverride, copied.toneCurveOverride)
        assertEquals(before.channelCurvesOverride, copied.channelCurvesOverride)
        assertEquals(before.adjustments, copied.adjustments)
        assertTrue(vm.state.value.hasCopiedEffect)
        assertEquals(40f, copied.adjustments.lensBlur.amount, 0.001f)
        assertEquals(0.6f, copied.adjustments.exposure, 0.001f)
        assertNotNull(copied.channelCurvesOverride)
    }

    /** A second copy replaces the first one — paste uses the latest snapshot. */
    @Test
    fun latestCopyOverwritesThePreviousOne() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.2f), "曝光度")
        vm.copyEffect()
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.8f), "曝光度")
        vm.copyEffect()

        assertEquals(0.8f, vm.state.value.copiedEffect!!.adjustments.exposure, 0.001f)
        vm.show(2)
        assertTrue(vm.pasteEffect())
        assertEquals("粘贴应以最新一次复制为准", 0.8f, vm.state.value.adjustments.exposure, 0.001f)
    }

    /** Paste applies every copied effect to the photo currently previewed. */
    @Test
    fun pasteAppliesTheWholeCopiedLookToCurrentPhoto() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.selectPreset(Preset.NONE_ID)
        vm.setToneCurve(customCurve())
        vm.setChannelCurves(ToneCurveChannel.GREEN, customCurve())
        vm.setAdjustments(
            vm.state.value.adjustments.copy(
                exposure = 0.55f,
                vibrance = 0.25f,
                lensBlur = LensBlur(amount = 35f)
            ),
            "曝光度"
        )
        vm.copyEffect()
        val copied = vm.state.value.copiedEffect!!

        vm.show(1)
        vm.setAdjustments(
            vm.state.value.adjustments.copy(exposure = -0.5f, contrast = 0.9f),
            "曝光度"
        )
        val before = vm.state.value.adjustments

        assertTrue("粘贴应生效", vm.pasteEffect())
        val after = vm.state.value
        assertEquals(copied.presetId, after.presetId)
        assertEquals(copied.toneCurveOverride, after.toneCurveOverride)
        assertEquals(copied.channelCurvesOverride, after.channelCurvesOverride)
        assertEquals(copied.adjustments, after.adjustments)
        assertTrue("粘贴应覆盖该照片原有的本地调节", before != after.adjustments)
        assertEquals("粘贴不应切换当前照片", 1, after.index)
    }

    /** Paste never leaks into other photos and never moves the preview index. */
    @Test
    fun pasteOnlyAffectsTheCurrentPhoto() = runBlocking {
        awaitInit()
        val baseline = vm.state.value.adjustments.exposure
        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.7f), "曝光度")
        vm.copyEffect()

        vm.show(1)
        assertTrue(vm.pasteEffect())
        assertEquals(1, vm.state.value.index)

        vm.show(2)
        assertEquals(2, vm.state.value.index)
        assertEquals("第三张应保持自己的基线", baseline, vm.state.value.adjustments.exposure, 0.001f)
        assertEquals(0, vm.state.value.historyIndex)

        vm.show(1)
        assertEquals("切回第二张应保留粘贴结果", 0.7f, vm.state.value.adjustments.exposure, 0.001f)
    }

    /** Paste is one undoable step in the CURRENT photo's own history. */
    @Test
    fun pasteIsAnUndoableHistoryStep() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.7f), "曝光度")
        vm.copyEffect()

        vm.show(1)
        val before = vm.state.value.adjustments
        assertTrue(vm.pasteEffect())
        assertEquals(1, vm.state.value.historyIndex)
        assertEquals("粘贴效果", vm.state.value.currentHistoryLabel)

        vm.undo()
        assertEquals(0, vm.state.value.historyIndex)
        assertEquals("撤销应回到粘贴前的参数", before, vm.state.value.adjustments)
    }

    /** Clipboard survives photo switches and can be pasted repeatedly. */
    @Test
    fun clipboardSurvivesPhotoSwitchAndCanBeReused() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.65f), "曝光度")
        vm.copyEffect()
        val copied = vm.state.value.copiedEffect!!

        vm.show(1)
        assertEquals("切换照片不应清空剪贴板", copied, vm.state.value.copiedEffect)
        assertTrue(vm.pasteEffect())

        vm.show(2)
        assertTrue("同一份剪贴板可继续贴到第三张", vm.pasteEffect())
        assertEquals(0.65f, vm.state.value.adjustments.exposure, 0.001f)
    }

    /** Pasting an identical look adds no history noise. */
    @Test
    fun pastingAnIdenticalLookIsANoOp() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.4f), "曝光度")
        vm.copyEffect()
        assertFalse("当前照片已与剪贴板一致，重复粘贴应无效", vm.pasteEffect())
        assertEquals(1, vm.state.value.historyIndex)

        vm.show(1)
        assertTrue(vm.pasteEffect())
        assertFalse("重复粘贴不应再记一步", vm.pasteEffect())
        assertEquals(1, vm.state.value.historyIndex)
    }
}
