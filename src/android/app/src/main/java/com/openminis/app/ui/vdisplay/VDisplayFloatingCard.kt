/*
 * Copyright 2026 OpenMinis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openminis.app.ui.vdisplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import kotlin.math.roundToInt

/**
 * [T-vdisplay-floating] Floating preview card for the background virtual
 * display — collapsed bubble and expanded interactive viewer. Pure UI: side
 * effects go through [actions], and window-level drag is supplied via
 * [collapsedDragModifier] / [expandedHandleModifier] so the global
 * `WindowManager` host can drive raw-coordinate dragging at View level while
 * the in-app fallback uses container-relative Compose dragging.
 */

internal val VDisplayExpandedChromeDp = 36.dp
internal val VDisplayZoomBarDp = 40.dp

private val BubbleCornerDp = 16.dp
private val WindowCornerDp = 18.dp
private val ActiveDotGreen = Color(0xFF34C759)
private val WarningDotAmber = Color(0xFFFF9500)

@Composable
internal fun VDisplayFloatingCard(
    state: VDisplayFloatingUiState,
    directTouch: Boolean,
    contentWidthDp: Float,
    contentHeightDp: Float,
    actions: VDisplayFloatingActions,
    collapsedModifier: Modifier = Modifier,
    expandedHandleModifier: Modifier = Modifier,
    expandedWatchModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    if (state.expanded) {
        VDisplayExpandedCard(
            state = state,
            directTouch = directTouch,
            contentWidthDp = contentWidthDp,
            contentHeightDp = contentHeightDp,
            actions = actions,
            handleModifier = expandedHandleModifier,
            watchModifier = expandedWatchModifier,
            modifier = modifier,
        )
    } else {
        VDisplayCollapsedBubble(
            state = state,
            contentWidthDp = contentWidthDp,
            contentHeightDp = contentHeightDp,
            actions = actions,
            bodyModifier = collapsedModifier,
            modifier = modifier,
        )
    }
}

@Composable
private fun VDisplayCollapsedBubble(
    state: VDisplayFloatingUiState,
    contentWidthDp: Float,
    contentHeightDp: Float,
    actions: VDisplayFloatingActions,
    bodyModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(BubbleCornerDp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(BubbleCornerDp),
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(contentWidthDp.dp, contentHeightDp.dp)
                    .clickable(onClick = actions::onExpand)
                    .then(bodyModifier),
            ) {
                FloatingPreviewImage(
                    state = state,
                    contentDescription = stringResource(R.string.vdisplay_floating_preview_cd),
                )
                VDisplayStatusDot(
                    waiting = state.waitingForFrame,
                    trusted = state.trusted,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
        }
        // Small ✕ at the bubble's top corner; closes the overlay for this
        // display id only — never touches the virtual display itself.
        FloatingCloseButton(
            onClick = actions::onClose,
            contentDescription = stringResource(R.string.vdisplay_floating_close_cd),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp),
        )
    }
}

@Composable
private fun VDisplayExpandedCard(
    state: VDisplayFloatingUiState,
    directTouch: Boolean,
    contentWidthDp: Float,
    contentHeightDp: Float,
    actions: VDisplayFloatingActions,
    handleModifier: Modifier,
    watchModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(WindowCornerDp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = RoundedCornerShape(WindowCornerDp),
        ),
    ) {
        Column(modifier = Modifier.width(contentWidthDp.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VDisplayExpandedChromeDp)
                    .then(handleModifier)
                    .padding(horizontal = 4.dp),
            ) {
                VDisplayStatusDot(
                    waiting = state.waitingForFrame,
                    trusted = state.trusted,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = stringResource(R.string.vdisplay_floating_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(
                    text = stringResource(
                        if (directTouch) R.string.vdisplay_floating_mode_touch
                        else R.string.vdisplay_floating_mode_watch,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (directTouch) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .background(
                            color = if (directTouch) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { actions.onSetDirectTouch(!directTouch) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Box(modifier = Modifier.weight(1f))
                ChromeIcon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.vdisplay_floating_back_cd),
                    onClick = actions::onBackKey,
                )
                ChromeIcon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.vdisplay_floating_collapse_cd),
                    onClick = actions::onCollapse,
                )
                ChromeIcon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.vdisplay_floating_close_cd),
                    onClick = actions::onClose,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp.dp)
                    .pinchZoom { actions.onPinch(it) }
                    .then(
                        if (directTouch) {
                            Modifier.directTouch(state, actions)
                        } else {
                            watchModifier
                        },
                    ),
            ) {
                FloatingPreviewImage(
                    state = state,
                    contentDescription = stringResource(R.string.vdisplay_floating_preview_cd),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VDisplayZoomBarDp),
            ) {
                ChromeIcon(
                    imageVector = Icons.Outlined.Remove,
                    contentDescription = stringResource(R.string.vdisplay_floating_zoom_out_cd),
                    onClick = actions::onZoomOut,
                )
                Text(
                    text = stringResource(
                        R.string.vdisplay_floating_zoom_value,
                        (state.scale * 100f).roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChromeIcon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.vdisplay_floating_zoom_in_cd),
                    onClick = actions::onZoomIn,
                )
                ChromeIcon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.vdisplay_floating_zoom_reset_cd),
                    onClick = actions::onZoomReset,
                )
            }
        }
    }
}

@Composable
private fun FloatingPreviewImage(
    state: VDisplayFloatingUiState,
    contentDescription: String,
) {
    val frame = state.frame
    if (frame != null) {
        Image(
            bitmap = frame.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = stringResource(R.string.vdisplay_floating_waiting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VDisplayStatusDot(
    waiting: Boolean,
    trusted: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (!waiting && trusted) ActiveDotGreen else WarningDotAmber
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun FloatingCloseButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(22.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ChromeIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Two-finger pinch zoom; intercepts at Initial pass only once >=2 fingers are down. */
private fun Modifier.pinchZoom(onPinch: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f && zoom > 0f && zoom.isFinite()) onPinch(zoom)
                pressed.forEach { it.consume() }
            }
        }
    }
}

private fun Modifier.directTouch(
    state: VDisplayFloatingUiState,
    actions: VDisplayFloatingActions,
): Modifier = composed {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val frame = state.frame
    val frameWidth = frame?.width ?: 1
    val frameHeight = frame?.height ?: 1

    fun map(offset: Offset): Pair<Int, Int>? = PreviewTouchMapper.map(
        localX = offset.x,
        localY = offset.y,
        boxWidth = boxSize.width,
        boxHeight = boxSize.height,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    )

    this
        .onSizeChanged { boxSize = it }
        .pointerInput(frameWidth, frameHeight) {
            val swipeThresholdPx = 12.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val start = map(down.position)
                var last = down.position
                var moved = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        val end = map(change.position) ?: map(last)
                        if (start != null && end != null) {
                            if (moved > swipeThresholdPx) {
                                actions.onSwipe(start.first, start.second, end.first, end.second, 300L)
                            } else {
                                actions.onTap(start.first, start.second)
                            }
                        }
                        change.consume()
                        break
                    }
                    moved += (change.position - last).getDistance()
                    last = change.position
                    change.consume()
                }
            }
        }
}
