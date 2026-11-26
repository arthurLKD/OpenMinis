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
 *
 * Window-level drag interception adapted from Mika's OverlayHost
 * (https://github.com/wangzan101/mika, Apache-2.0). See NOTICE in the
 * repository root.
 */
package com.openminis.app.ui.vdisplay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * [T-vdisplay-floating] Native [FrameLayout] wrapper for the `WindowManager`
 * overlay that tracks drags in **raw screen coordinates** (`MotionEvent.rawX/rawY`).
 *
 * Why this must use `rawX/rawY` at the View boundary rather than Compose local
 * coordinates: moving a `WindowManager` window updates `LayoutParams.x/y` in
 * screen space, shifting the window's own local origin on subsequent events.
 * Subtracting local coordinates across frames creates a +/- feedback
 * oscillation; `rawX/rawY` come straight from the input dispatcher in screen
 * coordinates and move the window 1:1 with the finger.
 *
 * When [dragAnywhere] is false (expanded window with direct-touch ON), only
 * touches starting inside the top [dragHandleHeightPx] bar can turn into a
 * window drag; touches on the preview area below pass undisturbed to Compose
 * (`PreviewTouchMapper` / pinch-zoom). Even inside the top bar, taps on
 * buttons (`Back`, `Collapse`, `Close`, touch toggle) stay below `touchSlop`
 * and click normally without triggering a drag.
 */
internal class VDisplayOverlayTouchHost(context: Context) : FrameLayout(context) {

    var dragAnywhere: Boolean = true
    var dragHandleHeightPx: Int = 0
    var onDragStart: (() -> Unit)? = null
    var onDragBy: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragFinished: ((vx: Float, vy: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tracking = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var velocityTracker: VelocityTracker? = null

    private fun canDragAt(localY: Float): Boolean =
        dragAnywhere || (dragHandleHeightPx > 0 && localY <= dragHandleHeightPx)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = canDragAt(ev.y)
                dragging = false
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                velocityTracker?.recycle()
                velocityTracker = if (tracking) {
                    VelocityTracker.obtain().also { addMovementScreen(ev) }
                } else {
                    null
                }
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                addMovementScreen(ev)
                if (!dragging && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                    dragging = true
                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                    onDragStart?.invoke()
                    onDragBy?.invoke(ev.rawX - downRawX, ev.rawY - downRawY)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch (e.g. pinch-to-zoom) must not turn into a window drag.
                resetTouch()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetTouch()
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!tracking) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                addMovementScreen(ev)
                if (!dragging && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) {
                    val dx = ev.rawX - lastRawX
                    val dy = ev.rawY - lastRawY
                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                    if (dx != 0f || dy != 0f) onDragBy?.invoke(dx, dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging
                val tracker = velocityTracker
                var vx = 0f
                var vy = 0f
                if (wasDragging && tracker != null) {
                    addMovementScreen(ev)
                    tracker.computeCurrentVelocity(1000, 6000f)
                    vx = tracker.xVelocity
                    vy = tracker.yVelocity
                }
                resetTouch()
                if (wasDragging) onDragFinished?.invoke(vx, vy)
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                resetTouch()
                if (wasDragging) onDragFinished?.invoke(0f, 0f)
            }
        }
        return dragging
    }

    private fun addMovementScreen(ev: MotionEvent) {
        val tracker = velocityTracker ?: return
        // Feed VelocityTracker in screen space (offset by raw - local) so moving
        // the window underneath the finger never skews computed velocity.
        val copy = MotionEvent.obtain(ev)
        try {
            copy.offsetLocation(ev.rawX - ev.x, ev.rawY - ev.y)
            tracker.addMovement(copy)
        } finally {
            copy.recycle()
        }
    }

    private fun resetTouch() {
        tracking = false
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
    }
}

/**
 * Compose-side drag modifiers for the **in-app fallback** box (inside
 * `AppNavigation`). Because `Modifier.offset` moves the child box after each
 * drag delta, adding [currentOrigin] turns local coordinates into stable
 * container coordinates with zero feedback oscillation.
 */
internal object VDisplayOverlayDragFrame {

    fun Modifier.inAppWindowDrag(
        key: Any?,
        currentOrigin: () -> Offset,
        onDragBy: (dx: Float, dy: Float) -> Unit,
        onDragFinished: (vx: Float, vy: Float) -> Unit,
    ): Modifier = this.composed {
        val slop = LocalViewConfiguration.current.touchSlop
        this.pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                val startAbs = currentOrigin() + down.position
                var lastAbs = startAbs
                var moved = false
                tracker.addPosition(down.uptimeMillis, startAbs)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val abs = currentOrigin() + change.position
                    val total = abs - startAbs
                    if (!moved && sqrt(total.x * total.x + total.y * total.y) > slop) moved = true
                    if (moved) {
                        val delta = abs - lastAbs
                        if (delta.x != 0f || delta.y != 0f) onDragBy(delta.x, delta.y)
                        change.consume()
                    }
                    tracker.addPosition(change.uptimeMillis, abs)
                    lastAbs = abs
                }
                if (moved) {
                    val v = runCatching { tracker.calculateVelocity() }.getOrDefault(Velocity.Zero)
                    onDragFinished(v.x, v.y)
                }
            }
        }
    }

    fun Modifier.inAppWindowDragOrTap(
        key: Any?,
        currentOrigin: () -> Offset,
        onDragBy: (dx: Float, dy: Float) -> Unit,
        onDragFinished: (vx: Float, vy: Float) -> Unit,
        onTap: () -> Unit,
    ): Modifier = this.composed {
        val slop = LocalViewConfiguration.current.touchSlop
        var moved by remember(key) { mutableStateOf(false) }
        this.pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                moved = false
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                val startAbs = currentOrigin() + down.position
                var lastAbs = startAbs
                tracker.addPosition(down.uptimeMillis, startAbs)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val abs = currentOrigin() + change.position
                    val total = abs - startAbs
                    if (!moved && sqrt(total.x * total.x + total.y * total.y) > slop) moved = true
                    if (moved) {
                        val delta = abs - lastAbs
                        if (delta.x != 0f || delta.y != 0f) onDragBy(delta.x, delta.y)
                        change.consume()
                    }
                    tracker.addPosition(change.uptimeMillis, abs)
                    lastAbs = abs
                }
                if (moved) {
                    val v = runCatching { tracker.calculateVelocity() }.getOrDefault(Velocity.Zero)
                    onDragFinished(v.x, v.y)
                } else {
                    onTap()
                }
            }
        }
    }
}

/**
 * Animates [start] → [target] with a decelerate curve, reporting every
 * interpolated origin (screen px). Returns the [Job]; cancelling it stops
 * immediately, and [onEnd] fires exactly once.
 */
internal fun animateFling(
    scope: CoroutineScope,
    start: Pair<Int, Int>,
    target: Pair<Int, Int>,
    durationMs: Int = 260,
    onUpdate: (x: Int, y: Int) -> Unit,
    onEnd: () -> Unit,
): Job = scope.launch(Dispatchers.Main.immediate) {
    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = durationMs.toLong()
        interpolator = DecelerateInterpolator(1.6f)
    }
    try {
        animator.start()
        var lastFraction = -1f
        while (true) {
            val f = animator.animatedFraction
            if (f != lastFraction) {
                lastFraction = f
                onUpdate(
                    (start.first + (target.first - start.first) * f).toInt(),
                    (start.second + (target.second - start.second) * f).toInt(),
                )
            }
            if (!animator.isRunning || f >= 1f) break
            kotlinx.coroutines.delay(16)
        }
        onUpdate(target.first, target.second)
    } finally {
        if (animator.isRunning) animator.cancel()
        onEnd()
    }
}
