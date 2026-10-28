package com.fujikon.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.components.ZoomableImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomableImageTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun holdToCompareSurvivesTheBitmapSwap() {
        val rendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val original = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        var comparing by mutableStateOf(false)

        rule.setContent {
            ZoomableImage(
                bitmap = if (comparing) original else rendered,
                original = original,
                comparing = comparing,
                onComparingChange = { comparing = it },
                modifier = Modifier
                    .size(180.dp)
                    .testTag("zoom"),
                resetKey = "photo"
            )
        }

        rule.onNodeWithTag("zoom").performTouchInput { down(center) }
        rule.waitUntil(3_000) { comparing }
        rule.waitForIdle()
        assertTrue("holding should reveal the original", comparing)

        rule.onNodeWithTag("zoom").performTouchInput { up() }
        rule.waitUntil(3_000) { !comparing }
        assertFalse("releasing should return to the rendered image", comparing)
    }
}
