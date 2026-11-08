package com.fujikon.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.theme.InkElevated

/**
 * Shared container for the editor's bottom sub-pages (preset picker, tone
 * curve, history, export). These used to be [ModalBottomSheet]s, which live in
 * a separate window: they cover the whole screen with a dark scrim and swallow
 * every touch, so the editor's top action bar (preset switch / undo / redo /
 * version history) could never be tapped while a page was open.
 *
 * This container is deliberately different:
 *
 * - It is rendered **inside the editor's own Compose tree**, not a separate
 *   window, so the top bar stays at the very top of the same hierarchy.
 * - The full-screen root [Box] installs **no scrim, no background and no
 *   pointerInput/clickable at all**. The upper half of the screen is therefore
 *   transparent for both drawing and hit-testing: taps pass straight through
 *   to the TopAppBar and the preview underneath it.
 * - Only the bottom-aligned panel [Column] draws a background and consumes
 *   touches. Its height is capped so the top action bar is never covered.
 * - There is no swipe-to-dismiss gesture (per product requirement). Closing is
 *   explicit: an in-panel close button plus [BackHandler] for the back key.
 *
 * @param panelColor background of the panel itself; pass [Color.Transparent]
 *   when the content draws its own gradient background (tone curve sheet).
 * @param maxHeightFraction upper bound of the panel height as a fraction of
 *   the screen height, keeping the top action bar uncovered.
 */
@Composable
fun EditorBottomPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "editor_bottom_panel",
    panelColor: Color = InkElevated,
    maxHeightFraction: Float = 0.72f,
    content: @Composable ColumnScope.() -> Unit
) {
    // IMPORTANT: absolutely no scrim / background / pointerInput / clickable on
    // this root. Any of those would darken the upper half or steal taps from
    // the top action bar. Keep it a bare layout node.
    Box(
        modifier
            .fillMaxSize()
            .testTag("${testTag}_host")
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(maxHeightFraction)
                // Rounded corners via background(shape) only — a plain draw
                // modifier. Do NOT use clip(): its ClipNode also clips pointer
                // hit-testing, which silently makes every control inside the
                // panel unclickable.
                .background(
                    panelColor,
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .navigationBarsPadding()
                .testTag(testTag),
            content = content
        )
    }
}
