package com.fujikon.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared enter / exit transition for the four editor sub-pages
 * (preset picker / adjustments / tone curve / mask editor). They are plain
 * overlays inside the editor tree, not separate dialog windows, so until now
 * they appeared and disappeared instantly. A short fade + vertical slide
 * makes opening and closing feel continuous without delaying interaction.
 */
val EditorPageEnter: EnterTransition =
    fadeIn(animationSpec = tween(220)) +
        slideInVertically(animationSpec = tween(260), initialOffsetY = { it / 8 })

val EditorPageExit: ExitTransition =
    fadeOut(animationSpec = tween(160)) +
        slideOutVertically(animationSpec = tween(200), targetOffsetY = { it / 8 })

/** Wrap each sub-page so it fades/slides in and out instead of popping. */
@Composable
fun EditorPageTransition(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EditorPageEnter,
        exit = EditorPageExit,
        content = content
    )
}
