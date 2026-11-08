package com.fujikon.app

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.ui.components.AdjustmentsSheet
import com.fujikon.app.ui.components.EditorBottomPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for edge-to-edge swipe-back: every editor sub-page must consume
 * the system back itself and close ONLY that page, returning to the preview
 * instead of letting the NavHost pop the editor back to the photo picker.
 *
 * The NavHost registers a predictive-back fallback on the Activity's
 * OnBackPressedDispatcher. An in-tree overlay that installs no BackHandler
 * leaves back unconsumed, so that fallback wins — exactly the 1.6.20 bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorBackNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    private fun pressBack(dispatcher: OnBackPressedDispatcher?) {
        assertNotNull("test host must expose a back dispatcher", dispatcher)
        rule.runOnIdle { dispatcher!!.onBackPressed() }
    }

    @Test
    fun editorBottomPanelConsumesBackAndClosesOnlyItself() {
        var dismissed = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            EditorBottomPanel(onDismiss = { dismissed++ }) {
                Text("写真配方")
            }
        }
        pressBack(dispatcher)
        assertEquals("system back must close the panel", 1, dismissed)
    }

    @Test
    fun panelBackHandlerWinsOverTheNavHostFallback() {
        var dismissed = 0
        var fallbackBacks = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // Stands in for the NavHost predictive-back fallback: registered
            // FIRST, so a correctly installed panel handler (registered later,
            // LIFO) must win and keep the user on the preview page.
            BackHandler { fallbackBacks++ }
            EditorBottomPanel(onDismiss = { dismissed++ }) {
                Text("预设")
            }
        }
        pressBack(dispatcher)
        assertEquals(1, dismissed)
        assertEquals("back must not fall through to the gallery", 0, fallbackBacks)
    }

    @Test
    fun topMostSubPageConsumesBackFirstAndUnwindsOneLayerAtATime() {
        var maskBacks = 0
        var pickerDismissed = 0
        var fallbackBacks = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // Real editor state: onDismiss removes the panel from the tree,
            // which is what frees the next back press for the layer below.
            var showPicker by remember { mutableStateOf(true) }
            var showMask by remember { mutableStateOf(true) }
            BackHandler { fallbackBacks++ }          // NavHost fallback
            if (showMask) {
                BackHandler { showMask = false; maskBacks++ } // full-screen mask page
            }
            if (showPicker) {
                EditorBottomPanel(onDismiss = { showPicker = false; pickerDismissed++ }) {
                    Text("预设面板")
                }
            }
        }
        // First back closes only the top-most page (the preset panel) ...
        pressBack(dispatcher)
        assertEquals(1, pickerDismissed)
        assertEquals(0, maskBacks)
        assertEquals(0, fallbackBacks)
        // ... a second back unwinds the mask layer, still not the gallery ...
        pressBack(dispatcher)
        assertEquals(1, maskBacks)
        assertEquals(0, fallbackBacks)
        // ... and only once every sub-page is closed does back reach NavHost.
        pressBack(dispatcher)
        assertEquals(1, fallbackBacks)
    }

    @Test
    fun adjustmentsSheetBackCommitsTheDraftAndDismisses() {
        var dismissed = 0
        var previews = 0
        var fallbackBacks = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { fallbackBacks++ }
            adjustmentsSheet(
                saving = false,
                onDismiss = { dismissed++ },
                onPreview = { _, _ -> previews++ }
            )
        }
        pressBack(dispatcher)
        assertEquals("back must close the adjustment page", 1, dismissed)
        assertTrue("back must commit the draft before closing", previews > 0)
        assertEquals(0, fallbackBacks)
    }

    @Test
    fun adjustmentsSheetSwallowsBackWhileSavingInsteadOfFallingThrough() {
        var dismissed = 0
        var fallbackBacks = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { fallbackBacks++ }
            adjustmentsSheet(
                saving = true,
                onDismiss = { dismissed++ },
                onPreview = { _, _ -> }
            )
        }
        pressBack(dispatcher)
        assertEquals("a save in progress must not be dismissed by back", 0, dismissed)
        assertEquals("back must not fall through to the gallery while saving", 0, fallbackBacks)
    }

    @androidx.compose.runtime.Composable
    private fun adjustmentsSheet(
        saving: Boolean,
        onDismiss: () -> Unit,
        onPreview: (EditAdjustments, String?) -> Unit
    ) {
        AdjustmentsSheet(
            presetName = "测试预设",
            initialAdjustments = EditAdjustments.Neutral,
            initialToneCurve = ToneCurve.linear(),
            presetToneCurve = ToneCurve.linear(),
            baseCurve = emptyList(),
            rendering = false,
            saving = saving,
            onPreview = onPreview,
            onToneCurvePreview = {},
            onClearToneCurve = {},
            onClearAllCurves = {},
            onSave = {},
            onDismiss = onDismiss,
            onOpenMaskEditor = {},
            sourceBitmap = null as Bitmap?,
            previewBitmap = null as Bitmap?
        )
    }
}
